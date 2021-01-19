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
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResult;
import org.apache.maven.shared.transfer.dependencies.resolve.DependencyResolverException;

/**
 * Resolves and downloads all of a projects extensions and place the resulting
 * zips in a specific diretory. Based on {@link GetMojo}.
 */
@Mojo(name = "project-extensions", requiresProject = false, threadSafe = true)
public class ProjectExtensionsMojo extends AbstractExtensionsMojo {

	/**
	 * The maven project.
	 */
	@Parameter(required = true, readonly = true, property = "project")
	protected MavenProject project;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (isSkip()) {
			getLog().info("Skipping plugin execution");
			return;
		}

		Set<Artifact> artifacts = project.getArtifacts();
		for (Artifact artifact : artifacts) {
			getLog().info("Getting " + artifact);
			if (artifact != null) {
				coordinate.setGroupId(artifact.getGroupId());
				coordinate.setArtifactId(artifact.getArtifactId());
				coordinate.setVersion(artifact.getVersion());
				if (artifact.getType() != null) {
					coordinate.setType(artifact.getType());
				}
				if (artifact.getType() != null) {
					coordinate.setClassifier(artifact.getType());
				}
			}

			try {
				doCoordinate();
			} catch (MojoFailureException | DependencyResolverException | ArtifactResolverException e) {
				throw new MojoExecutionException("Failed to process an artifact.", e);
			}
		}
	}

	protected void handleResult(ArtifactResult result) throws MojoExecutionException {
		File file = result.getArtifact().getFile();
		if (file == null || !file.exists()) {
			getLog().warn("Artifact " + result.getArtifact().getArtifactId()
					+ " has no attached file. Its content will not be copied in the target model directory.");
			return;
		}

		Path extensionZip = file.toPath();
		try {
			Path target = checkDir(
					output.toPath().resolve(result.getArtifact().getVersion()).resolve(result.getArtifact().getId()))
							.resolve(getFileName(result.getArtifact(), includeVersion, false));
			copy(extensionZip, target, Files.getLastModifiedTime(extensionZip).toInstant());
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to copy extension to staging area.", e);
		}

	}

}
