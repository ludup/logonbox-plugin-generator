package com.logonbox.maven.plugins.generator;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

public abstract class AbstractBaseExtensionsMojo extends AbstractMojo {

	/**
	 * The maven project.
	 */
	@Parameter(required = true, readonly = true, property = "project")
	protected MavenProject project;

	@Parameter(defaultValue = "false")
	protected boolean skipPoms;
	
	protected final boolean isSkipPoms() {
		return skipPoms;
	}

	@Override
	public final void execute() throws MojoExecutionException, MojoFailureException {
		if(!isSkipPoms() || ( isSkipPoms() && ( project == null || !project.getPackaging().equals("pom")))) {
			onExecute();
		}
		else
			getLog().info(String.format("Skipping %s, it is a POM and we are configured to skip these.", project.getArtifact().getArtifactId()));
	}

	protected void onExecute() throws MojoExecutionException, MojoFailureException {
		
	}

	protected String getArtifactVersion(Artifact artifact) {
		return getArtifactVersion(artifact, true);
	}
	
	protected String getArtifactVersion(Artifact artifact, boolean processSnapshotVersions) {
		String v = artifact.getVersion();
		if (artifact.isSnapshot()) {
			if (v.contains("-SNAPSHOT") || !processSnapshotVersions)
				if(v.contains("-SNAPSHOT"))
					return v.substring(0, v.indexOf("-SNAPSHOT")) + "-" + getSnapshotVersionSuffix();
				else
				return v;
			else {
				int idx = v.lastIndexOf("-");
				if (idx == -1) {
					return v;
				} else {
					idx = v.lastIndexOf(".", idx - 1);
					if (idx == -1)
						return v;
					else {
						idx = v.lastIndexOf("-", idx - 1);
						if (idx == -1)
							return v;
						else {
							return v.substring(0, idx) + "-" + getSnapshotVersionSuffix();
						}
					}
				}
			}
		} else
			return v;
	}

	protected final String getSnapshotVersionSuffix() {
		if(isSnapshotVersionAsBuildNumber()) {
			String buildNumber = System.getenv("BUILD_NUMBER");
			if(buildNumber == null || buildNumber.equals(""))
				return "0";
			else
				return buildNumber;
		}
		else
			return "SNAPSHOT";
	}

	protected boolean isSnapshotVersionAsBuildNumber() {
		return false;
	}
}
