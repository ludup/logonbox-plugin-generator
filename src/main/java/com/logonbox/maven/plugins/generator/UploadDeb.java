package com.logonbox.maven.plugins.generator;

import java.io.File;
import java.io.IOException;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.sisu.Description;

import com.sshtools.client.sftp.SftpClientTask;
import com.sshtools.client.sftp.TransferCancelledException;
import com.sshtools.common.permissions.PermissionDeniedException;
import com.sshtools.common.sftp.SftpStatusException;
import com.sshtools.common.ssh.SshException;

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

	@Override
	protected void upload(SftpClientTask ssh) throws IOException, SshException, SftpStatusException, TransferCancelledException, PermissionDeniedException {
		ssh.cd(repository + "/" + codename);
		ssh.putFiles(source.getPath() + "/*.deb");
	}

}