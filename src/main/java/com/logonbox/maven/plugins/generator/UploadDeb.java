package com.logonbox.maven.plugins.generator;

import java.io.File;
import java.io.IOException;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.sonatype.inject.Description;

import net.sf.sshapi.SshException;
import net.sf.sshapi.sftp.SftpClient;

/**
 * Upload Deb packages to SSH server (packager).
 */
@Mojo(defaultPhase = LifecyclePhase.DEPLOY, name = "upload-deb", requiresProject = true, requiresDirectInvocation = false, executionStrategy = "once-per-session", threadSafe = false)
@Description("Upload debian packages to repository")
public class UploadDeb extends AbstractSSHUploadMojo {

	/**
	 * The maven project.
	 */
	@Parameter(required = true, readonly = true, property = "project")
	protected MavenProject project;

	@Parameter(property = "upload-deb.repository", required = true, defaultValue = "hypersocketdebiantesting")
	private String repository;

	@Parameter(property = "upload-deb.codename", required = true, defaultValue = "umbra")
	private String codename;

	@Parameter(defaultValue = "${project.build.directory}", property = "upload-deb.source", required = true)
	protected File source;

	@Parameter(defaultValue = "${project.build.directory}", property = "upload-deb.dir", required = true)
	protected File dir;

	@Override
	protected void upload(SftpClient sftp) throws IOException, SshException {
		for (File file : source.listFiles()) {
			if (file.getName().endsWith(".deb")) {
				sftp.put(file, sftp.getDefaultPath()  + "/" + repository + "/" + codename + "/" + file.getName());
			}
		}
	}

}