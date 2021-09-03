package com.logonbox.maven.plugins.generator;

import java.io.File;
import java.io.IOException;

import org.apache.maven.plugin.logging.SystemStreamLog;
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
	
	public static void main(String[] args) throws Exception {
		System.setProperty("sshapi.logLevel", "DEBUG");
		UploadDeb deb = new UploadDeb();
		deb.username = "jenkins";
		deb.password = "jenkins";
		deb.host = "packager.hypersocket.io";
		deb.port = 4022;
		deb.setLog(new SystemStreamLog());
		deb.repository = "hypersocketdebiantesting";
		deb.codename= "umbra";
		deb.source = new File("/home/tanktarta/Documents/Git/HS-2.4/hypersocket.install4j/install4j-logonbox-vpn-client/target/media");
		deb.onExecute();
	}

}