package com.logonbox.maven.plugins.generator;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;

public abstract class AbstractBaseExtensionsMojo extends AbstractMojo {


	protected String getArtifactVersion(Artifact artifact) {
		return getArtifactVersion(artifact, true);
	}
	
	protected String getArtifactVersion(Artifact artifact, boolean processSnapshotVersions) {
		String v = artifact.getVersion();
		if (artifact.isSnapshot()) {
			if (v.contains("-SNAPSHOT") || !processSnapshotVersions)
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

	protected abstract boolean isSnapshotVersionAsBuildNumber();
}
