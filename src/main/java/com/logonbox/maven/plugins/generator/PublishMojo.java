package com.logonbox.maven.plugins.generator;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.sonatype.inject.Description;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

	protected void onExecute() throws MojoExecutionException, MojoFailureException {
		String artifactVersion = getArtifactVersion(project.getArtifact());
		try {
			String urlTxt = updateServerURL;
			String[] versionParts = artifactVersion.replace(".", "_").split("_");
			urlTxt = urlTxt.replace("[[majorMinorTag]]", String.format("%s_%s", versionParts[0], versionParts[1]));
			urlTxt = urlTxt.replace("[[artifactVersion]]", artifactVersion);
			URL url = new URL(urlTxt);
			HttpURLConnection conx = (HttpURLConnection)url.openConnection();
			getLog().info("Notify update server using " + url);
			if(conx.getResponseCode() == 200) {
				try(InputStream in = conx.getInputStream()) {
					ObjectMapper mapper = new ObjectMapper();
					JsonNode tree = mapper.readTree(in);
					getLog().info("Notified update server using " + url + ". Reply " + tree);
				}
			}
			else {
				throw new IOException("Invalid response code " + conx.getResponseCode() + ". " + conx.getResponseMessage());
			}
		} catch (IOException ioe) {
			throw new MojoExecutionException("Failed to notify update server of version " + artifactVersion, ioe);
		}
	}

	@Override
	protected boolean isSnapshotVersionAsBuildNumber() {
		return true;
	}

}