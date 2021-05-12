package com.logonbox.maven.plugins.generator;

import java.io.IOException;
import java.net.URL;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.sisu.Description;

/**
 * Public extension version to update server
 */
@Mojo(defaultPhase = LifecyclePhase.DEPLOY, name = "publish-extensions", requiresProject = true, requiresDirectInvocation = false, executionStrategy = "once-per-session", threadSafe = false)
@Description("Public extension version to update server")
public class PublishMojo extends AbstractBaseExtensionsMojo {

	@Parameter(property = "s3upload.updateServerURL", required = true, defaultValue = "https://updates2.hypersocket.com/app/api/webhooks/publish/generateNightly[[majorMinorTag]]?version=[[artifactVersion]]")
	private String updateServerURL;

	@Parameter(required = true, readonly = true, property = "project")
	protected MavenProject project;

	public void execute() throws MojoExecutionException, MojoFailureException {
		String artifactVersion = getArtifactVersion(project.getArtifact());
		try {
			String urlTxt = updateServerURL;
			String[] versionParts = artifactVersion.replace(".", "_").split("_");
			urlTxt = urlTxt.replace("[[majorMinorTag]]", String.format("%s_%s", versionParts[0], versionParts[1]));
			urlTxt = urlTxt.replace("[[artifactVersion]]", artifactVersion);
			URL url = new URL(urlTxt);
			getLog().info("Notify update server using " + url);
			url.getContent();
			getLog().info("Notified update server using " + url);
		} catch (IOException ioe) {
			throw new MojoExecutionException("Failed to notify update server of version " + artifactVersion);
		}
	}

	@Override
	protected boolean isSnapshotVersionAsBuildNumber() {
		return true;
	}

}