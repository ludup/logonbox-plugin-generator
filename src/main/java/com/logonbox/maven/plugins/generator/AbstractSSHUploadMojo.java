package com.logonbox.maven.plugins.generator;

import java.io.IOException;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;

import net.sf.sshapi.Ssh;
import net.sf.sshapi.SshClient;
import net.sf.sshapi.SshException;
import net.sf.sshapi.SshFileTransferListener;
import net.sf.sshapi.sftp.SftpClient;
import net.sf.sshapi.util.SimplePasswordAuthenticator;

public abstract class AbstractSSHUploadMojo extends AbstractBaseExtensionsMojo implements SshFileTransferListener {

	@Parameter(property = "sshupload.host", required = true, defaultValue = "packager.hypersocket.io")
	protected String host;

	@Parameter(property = "sshupload.port", required = true, defaultValue = "4022")
	protected int port;

	@Parameter(property = "sshupload.username", required = true, defaultValue = "${user.name}")
	protected String username;

	@Parameter(property = "sshupload.password", required = true)
	protected String password;

	private long total;
	private String transferring;
	private int pc;

	protected void onExecute() throws MojoExecutionException, MojoFailureException {
		
		getLog().info("Uploading to SSH server");
		try(SshClient client = Ssh.open(username, host, port, new SimplePasswordAuthenticator(password))) {
			try(SftpClient sftp = client.sftp()) {
				sftp.addFileTransferListener(this);
				upload(sftp);
			}
		}
		catch (IOException sshe) {
			throw new MojoExecutionException("Failed to upload to SSH server.", sshe);
		}
		getLog().info("Uploaded to SSH server");
	}

	protected abstract void upload(SftpClient amazonS3) throws IOException, SshException;

	@Override
	protected boolean isSnapshotVersionAsBuildNumber() {
		return true;
	} 

	@Override
	public void startedTransfer(String sourcePath, String targetPath, long length) {
		getLog().info("Starting to transfer " + sourcePath);
		pc = -1;
		transferring = sourcePath;
		total = length;
	}

	@Override
	public void transferProgress(String sourcePath, String targetPath, long progress) {
		int tpc = (int)(((double)progress / (double)total ) * 100d);
		if(tpc != pc) {
			pc = tpc;
			getLog().info(pc + "% complete");
		}
		
	}

	@Override
	public void finishedTransfer(String sourcePath, String targetPath) {
		getLog().info("Transferred " + transferring);
	}
}