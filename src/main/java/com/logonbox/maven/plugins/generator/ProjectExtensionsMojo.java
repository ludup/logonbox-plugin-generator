package com.logonbox.maven.plugins.generator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResult;
import org.apache.maven.shared.transfer.dependencies.resolve.DependencyResolverException;

/**
 * Resolves and downloads all of a projects extensions and place the resulting
 * zips in a specific diretory. Based on {@link GetMojo}.
 */
@Mojo(name = "project-extensions", requiresProject = true, threadSafe = true, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class ProjectExtensionsMojo extends AbstractExtensionsMojo {

	@Parameter(defaultValue = "true")
	protected boolean snapshotVersionAsBuildNumber;

	/**
	 * The maven project.
	 */
	@Parameter(required = true, readonly = true, property = "project")
	protected MavenProject project;

	@Parameter(defaultValue = "${project.build.directory}/extension-store", property = "project-extensions.output", required = true)
	protected File output;

	{
		transitive = false;
	}

	@Override
	protected void onExecute() throws MojoExecutionException, MojoFailureException {
		if (isSkip()) {
			getLog().info("Skipping plugin execution");
			return;
		}

		Set<Artifact> artifacts = project.getArtifacts();
		for (Artifact artifact : artifacts) {
			if (isProcessedGroup(artifact) && isJarExtension(artifact)) {
				getLog().info("Getting " + artifact);
				coordinate.setGroupId(artifact.getGroupId());
				coordinate.setArtifactId(artifact.getArtifactId());
				coordinate.setVersion(artifact.getVersion());
				coordinate.setType("zip");
				coordinate.setClassifier(EXTENSION_ARCHIVE);

				try {
					doCoordinate();
				} catch (MojoFailureException | DependencyResolverException | ArtifactResolverException e) {
					getLog().debug("Failed to process an artifact, assuming it's not an extension.", e);
				}
			}
		}
	}

	protected void doHandleResult(ArtifactResult result) throws MojoExecutionException {
		File file = result.getArtifact().getFile();
		if (file == null || !file.exists()) {
			getLog().warn("Artifact " + result.getArtifact().getArtifactId()
					+ " has no attached file. Its content will not be copied in the target model directory.");
			return;
		}

		Path extensionZip = file.toPath();
		try {
			Artifact a = result.getArtifact();
			String version = getArtifactVersion(a, true);
			Path versionPath = output.toPath().resolve(version);
			Path artifactPath = versionPath.resolve(a.getArtifactId());
			String fileName = getFileName(a.getArtifactId(), getArtifactVersion(a, true), a.getClassifier(), a.getType(), includeVersion,
					false);
			Path target = checkDir(artifactPath).resolve(fileName);
			processVersionsInExtensionArchives(a, copy(extensionZip, target, Files.getLastModifiedTime(extensionZip).toInstant()));
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to copy extension to staging area.", e);
		}

	}

	@Override
	protected boolean isSnapshotVersionAsBuildNumber() {
		return snapshotVersionAsBuildNumber;
	}
}
