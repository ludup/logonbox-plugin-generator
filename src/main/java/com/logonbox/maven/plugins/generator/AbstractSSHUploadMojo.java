package com.logonbox.maven.plugins.generator;

import java.io.IOException;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;

//import com.sshtools.client.SshClient;
//import com.sshtools.client.sftp.SftpClientTask;
//import com.sshtools.client.sftp.TransferCancelledException;
//import com.sshtools.common.logger.Log;
//import com.sshtools.common.logger.Log.Level;
//import com.sshtools.common.permissions.PermissionDeniedException;
//import com.sshtools.common.sftp.SftpStatusException;
//import com.sshtools.common.ssh.SshException;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.SFTPClient;

public abstract class AbstractSSHUploadMojo extends AbstractBaseExtensionsMojo /* implements FileTransferProgress */ {

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

	@Override
	protected boolean isSnapshotVersionAsBuildNumber() {
		return true;
	}

	protected void onExecute() throws MojoExecutionException, MojoFailureException {
//		Log.getDefaultContext().enableConsole(Level.DEBUG);
		
		getLog().info("Uploading to SSH server");

		try(final SSHClient ssh = new SSHClient()) {
	        ssh.loadKnownHosts();
	        ssh.connect(host, port);
        	ssh.authPassword(username, password);
        	try(final SFTPClient sftp = ssh.newSFTPClient()) {
        		upload(sftp);
        	}
		}
		catch(IOException ioe) {
			throw new MojoExecutionException("Failed to upload.", ioe);
		}
		
//		try (SshClient ssh = new SshClient(host, port, username, password.toCharArray())) {
//			ssh.runTask(new SftpClientTask(ssh) {
//				protected void doSftp() {
//					try {
//						upload(this);
//					} catch (IOException | SshException | SftpStatusException | TransferCancelledException
//							| PermissionDeniedException e) {
//						throw new IllegalStateException("Failed to upload.", e);
//					}
//				}
//			});
//		} catch (IOException | SshException sshe) {
//			throw new MojoExecutionException("Failed to upload to SSH server.", sshe);
//		}
		getLog().info("Uploaded to SSH server");
	}
	
	protected abstract void upload(SFTPClient amazonS3) throws IOException;

//	protected abstract void upload(SftpClientTask amazonS3) throws IOException, SshException, SftpStatusException,
//		TransferCancelledException, PermissionDeniedException;
	
//	@Override
//	public void started(long bytesTotal, String remoteFile) {
//		getLog().info("Starting to transfer " + remoteFile);
//		pc = -1;
//		transferring = remoteFile;
//		total = bytesTotal;
//	}
//
//	@Override
//	public boolean isCancelled() {
//		return false;
//	}
//
//	@Override
//	public void progressed(long bytesSoFar) {
//		int tpc = (int)(((double)bytesSoFar / (double)total ) * 100d);
//		if(tpc != pc) {
//			pc = tpc;
//			getLog().info(pc + "% complete");
//		}
//	}
//
//	@Override
//	public void completed() {
//		getLog().info("Transferred " + transferring);
//	}

}