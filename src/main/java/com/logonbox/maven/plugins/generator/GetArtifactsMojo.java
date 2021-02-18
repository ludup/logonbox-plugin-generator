package com.logonbox.maven.plugins.generator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResult;
import org.apache.maven.shared.transfer.dependencies.DefaultDependableCoordinate;
import org.apache.maven.shared.transfer.dependencies.resolve.DependencyResolverException;
import org.codehaus.plexus.util.StringUtils;

/**
 * Resolves a list of artifacts artifact, eventually transitively, from the
 * specified remote repositories and place the resulting jars in a specific
 * diretory. Based on {@link GetMojo}.
 */
@Mojo(name = "get-artifacts", requiresProject = false, threadSafe = true)
public class GetArtifactsMojo extends AbstractExtensionsMojo {

	/**
	 * A string of the form groupId:artifactId:version[:packaging[:classifier]].
	 */
	@Parameter(property = "get-artifacts.artifact")
	private List<String> artifacts;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (isSkip()) {
			getLog().info("Skipping plugin execution");
			return;
		}

		for (String artifact : artifacts) {
			getLog().info("Getting " + artifact);
			String[] tokens = StringUtils.split(artifact, ":");
			if (tokens.length < 3 || tokens.length > 5) {
				throw new MojoFailureException("Invalid artifact, you must specify "
						+ "groupId:artifactId:version[:packaging[:classifier]] " + artifact);
			}
			coordinate.setGroupId(tokens[0]);
			coordinate.setArtifactId(tokens[1]);
			coordinate.setVersion(tokens[2]);
			if (tokens.length >= 4) {
				coordinate.setType(tokens[3]);
			}
			if (tokens.length == 5) {
				coordinate.setClassifier(tokens[4]);
			}

			try {
				doCoordinate();
			} catch (MojoFailureException | DependencyResolverException | ArtifactResolverException e) {
				throw new MojoExecutionException("Failed to process an artifact.", e);
			}

			coordinate = new DefaultDependableCoordinate();
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
			Path target = checkDir(output.toPath()).resolve(getFileName(result.getArtifact(), includeVersion, false));
			copy(extensionZip, target, Files.getLastModifiedTime(extensionZip).toInstant());
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to copy extension to staging area.", e);
		}

	}

}
