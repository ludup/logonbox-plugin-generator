package com.logonbox.maven.plugins.generator;

import java.io.IOException;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import com.sshtools.client.SshClient;
import com.sshtools.client.sftp.SftpClientTask;
import com.sshtools.client.sftp.TransferCancelledException;
import com.sshtools.common.permissions.PermissionDeniedException;
import com.sshtools.common.sftp.SftpStatusException;
import com.sshtools.common.ssh.SshException;

public abstract class AbstractSSHUploadMojo extends AbstractBaseExtensionsMojo {

	@Parameter(property = "sshupload.host", required = true, defaultValue = "packager.hypersocket.io")
	protected String host;

	@Parameter(property = "sshupload.port", required = true, defaultValue = "4022")
	protected int port;

	@Parameter(property = "sshupload.username", required = true, defaultValue = "${user.name}")
	protected String username;

	@Parameter(property = "sshupload.password", required = true)
	protected String password;

	/**
	 * The maven project.
	 */
	@Parameter(required = true, readonly = true, property = "project")
	protected MavenProject project;

	public void execute() throws MojoExecutionException, MojoFailureException {

		getLog().info("Uploading to SSH server");
		try (SshClient ssh = new SshClient(host, port, username, password.toCharArray())) {
			ssh.runTask(new SftpClientTask(ssh) {
				protected void doSftp() {
					try {
						upload(this);
					} catch (IOException | SshException | SftpStatusException | TransferCancelledException
							| PermissionDeniedException e) {
						throw new IllegalStateException("Failed to upload.", e);
					}
				}
			});
		} catch (IOException | SshException sshe) {
			throw new MojoExecutionException("Failed to upload to SSH server.", sshe);
		}
		getLog().info("Uploaded to SSH server");
	}

	protected abstract void upload(SftpClientTask amazonS3) throws IOException, SshException, SftpStatusException,
			TransferCancelledException, PermissionDeniedException;

	@Override
	protected boolean isSnapshotVersionAsBuildNumber() {
		return true;
	}

}