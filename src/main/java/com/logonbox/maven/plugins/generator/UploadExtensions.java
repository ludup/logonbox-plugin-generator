package com.logonbox.maven.plugins.generator;

import java.io.File;
import java.io.IOException;
import java.net.URL;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.sisu.Description;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.transfer.MultipleFileUpload;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;

/**
 * Upload to S3.
 * 
 * Need to set credentials externally at the moment,
 * https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/setup.html#setup-credentials
 */
@Mojo(defaultPhase = LifecyclePhase.DEPLOY, name = "upload-to-s3", requiresProject = true, requiresDirectInvocation = false, executionStrategy = "once-per-session", threadSafe = false)
@Description("Upload an extension store to S3 and inform the update server")
public class UploadExtensions extends AbstractBaseExtensionsMojo {

	@Parameter(defaultValue = "${project.build.directory}/extension-store", property = "upload-to-s3.output", required = true)
	protected File output;

	@Parameter(property = "s3upload.bucketName", required = true, defaultValue = "hypersocket-extensions")
	private String bucketName;

	@Parameter(property = "s3upload.keyPrefix", required = false)
	private String keyPrefix;

	@Parameter(property = "s3upload.updateServerURL", required = true, defaultValue = "https://updates2.hypersocket.com/app/api/webhooks/publish/generateBeta")
	private String updateServerURL;

	@Parameter(property = "s3upload.region", required = true, defaultValue = "eu-west-1")
	private String region;

	@Parameter(property = "s3upload.secretKey", required = true)
	private String secretKey;

	@Parameter(property = "s3upload.accessKey", required = true)
	private String accessKey;

	/**
	 * The maven project.
	 */
	@Parameter(required = true, readonly = true, property = "project")
	protected MavenProject project;

	public void execute() throws MojoExecutionException, MojoFailureException {
		AWSCredentials creds = new BasicAWSCredentials(accessKey, secretKey);
		AmazonS3 amazonS3 = AmazonS3Client.builder()
			    .withRegion(region)
			    .withCredentials(new AWSStaticCredentialsProvider(creds))
			    .build();
		
		TransferManager xfer_mgr = TransferManagerBuilder.standard().withS3Client(amazonS3).build();
		getLog().info("Uploading to S3");
		MultipleFileUpload xfer = xfer_mgr.uploadDirectory(bucketName, keyPrefix, output, true);
		// loop with Transfer.isDone()
		try {
			XferMgrProgress.showTransferProgress(xfer);
		} finally {
			xfer_mgr.shutdownNow();
		}
		getLog().info("Upload to S3 complete");
		String artifactVersion = getArtifactVersion(project.getArtifact());
		try {
			URL url = new URL(updateServerURL + "?version=" + artifactVersion);
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