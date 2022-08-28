package com.logonbox.maven.plugins.generator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResult;
import org.apache.maven.shared.transfer.dependencies.DefaultDependableCoordinate;
import org.apache.maven.shared.transfer.dependencies.resolve.DependencyResolverException;
import org.codehaus.plexus.util.StringUtils;

import edu.emory.mathcs.backport.java.util.concurrent.atomic.AtomicInteger;

/**
 * Resolves a list of artifacts artifact, eventually transitively, from the
 * specified remote repositories and place the resulting jars in a specific
 * diretory. Based on {@link GetMojo}.
 */
@Mojo(name = "get-artifacts", requiresProject = false, threadSafe = true)
public class GetArtifactsMojo extends AbstractExtensionsMojo {

	/**
	 * A string list of the form
	 * groupId:artifactId:[version[:packaging[:classifier]]].
	 */
	@Parameter(property = "get-artifacts.artifacts")
	private List<String> artifacts;

	/**
	 * A single string of the form
	 * groupId:artifactId:[version[:packaging[:classifier]]]<SPACING>groupId:artifactId:[version[:packaging[:classifier]]]....
	 */
	@Parameter(property = "get-artifacts.artifactList")
	private String artifactList;

	/**
	 * Default classifier
	 */
	@Parameter(property = "get-artifacts.defaultClassifer")
	private String defaultClassifier;

	/**
	 * Default classifier
	 */
	@Parameter(property = "get-artifacts.defaultType")
	private String defaultType;

	@Parameter(defaultValue = "true")
	protected boolean snapshotVersionAsBuildNumber;

	@Parameter(defaultValue = "true")
	protected boolean includeClassifier;

	/**
	 * The maven project.
	 * 
	 * @parameter expression="${project}"
	 * @required
	 * @readonly
	 */
	@Parameter(required = true, readonly = true, property = "project")
	protected MavenProject project;

	@Override
	protected void onExecute() throws MojoExecutionException, MojoFailureException {
		if (isSkip()) {
			getLog().info("Skipping plugin execution");
			return;
		}

		List<String> allArtifacts = new ArrayList<>();
		if (artifacts != null)
			allArtifacts.addAll(artifacts);
		if (artifactList != null) {
			for (String a : artifactList.split("\\s+")) {
				a = a.trim();
				if (!a.equals("")) {
					allArtifacts.add(a);
				}
			}
		}

		for (String artifact : allArtifacts) {
			getLog().info("Getting " + artifact);
			String[] tokens = StringUtils.split(artifact, ":");
			if (tokens.length < 3 || tokens.length > 5) {
				throw new MojoFailureException("Invalid artifact, you must specify "
						+ "groupId:artifactId:version[:packaging[:classifier]] " + artifact);
			}
			coordinate.setGroupId(tokens[0]);
			coordinate.setArtifactId(tokens[1]);
			if (tokens.length >= 3) {
				coordinate.setVersion(tokens[2]);
			} else {
				if (project != null)
					coordinate.setVersion(project.getVersion());
				else
					throw new MojoExecutionException("Need a project if not version is specified.");
			}

			if (tokens.length >= 4) {
				coordinate.setType(tokens[3]);
				if (tokens.length == 5) {
					coordinate.setClassifier(tokens[4]);
				} else {
					if (defaultClassifier != null && defaultClassifier.length() > 0)
						coordinate.setClassifier(defaultClassifier);
				}
			} else {
				if (defaultType != null && defaultType.length() > 0)
					coordinate.setType(defaultType);
			}

			try {
				doCoordinate();
			} catch (MojoFailureException | DependencyResolverException | ArtifactResolverException e) {
				throw new MojoExecutionException("Failed to process an artifact.", e);
			}

			coordinate = new DefaultDependableCoordinate();
		}
	}

	@Override
	protected void doHandleResult(ArtifactResult result) throws MojoExecutionException {
		Artifact artifact = result.getArtifact();
		File file = artifact.getFile();
		if (file == null || !file.exists()) {
			getLog().warn("Artifact " + artifact.getArtifactId()
					+ " has no attached file. Its content will not be copied in the target model directory.");
			return;
		}

		Path extensionZip = file.toPath();
		try {
			Path target = checkDir(output.toPath()).resolve(getFileName(artifact, includeVersion, includeClassifier));
			if (processExtensionVersions && "extension-archive".equals(artifact.getClassifier())
					&& "zip".equals(artifact.getType())) {
				getLog().debug("Process versions in extension artifact " + artifact.getArtifactId() + " to " + target + " from " + artifact.getFile());
				runIfNeedVersionProcessedArchive(artifact, target, () -> {
					try(var in = new ZipInputStream(Files.newInputStream(extensionZip)); 
						var out = new ZipOutputStream(Files.newOutputStream(target))) {
						processVersionsInExtensionArchives(artifact, in, out);
					}
				}, Files.getLastModifiedTime(extensionZip));
			} else if (processExtensionVersions && "jar".equals(artifact.getType())) {
				getLog().debug("Process versions in jar artifact " + artifact.getArtifactId() + " to " + target);
				runIfNeedVersionProcessedArchive(artifact, target, () -> {
					try(var in = new ZipInputStream(Files.newInputStream(extensionZip)); 
						var out = new ZipOutputStream(Files.newOutputStream(target))) {
						processVersionsInJarFile(artifact, new AtomicInteger(), in, out);
					}
				}, Files.getLastModifiedTime(extensionZip));
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to copy extension to staging area.", e);
		}

	}

	@Override
	protected boolean isSnapshotVersionAsBuildNumber() {
		return snapshotVersionAsBuildNumber;
	}
}
