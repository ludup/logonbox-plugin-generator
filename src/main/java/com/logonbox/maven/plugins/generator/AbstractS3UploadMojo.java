package com.logonbox.maven.plugins.generator;

import java.io.IOException;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;

public abstract class AbstractS3UploadMojo extends AbstractBaseExtensionsMojo {

	@Parameter(property = "s3upload.bucketName", required = true, defaultValue = "hypersocket-extensions")
	protected String bucketName;

	@Parameter(property = "s3upload.keyPrefix", required = false)
	protected String keyPrefix;

	@Parameter(property = "s3upload.region", required = true, defaultValue = "eu-west-1")
	private String region;

	@Parameter(property = "s3upload.secretKey", required = true)
	private String secretKey;

	@Parameter(property = "s3upload.accessKey", required = true)
	private String accessKey;

	protected void onExecute() throws MojoExecutionException, MojoFailureException {
		AmazonS3 amazonS3 = newClient();

		getLog().info("Uploading to S3");
		try {
			amazonS3 = upload(amazonS3);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to upload to S3.", e);
		}
		getLog().info("Upload to S3 complete");
	}
	
	protected String getPublicURI() {
		return "https://" + bucketName + ".s3." + region + ".amazonaws.com/" + keyPrefix;
	}

	protected AmazonS3 newClient() {
		AWSCredentials creds = new BasicAWSCredentials(accessKey, secretKey);
		AmazonS3 amazonS3 = AmazonS3Client.builder()
			    .withRegion(region)
			    .withCredentials(new AWSStaticCredentialsProvider(creds))
			    .build();
		return amazonS3;
	}

	protected abstract AmazonS3 upload(AmazonS3 amazonS3) throws IOException;

	@Override
	protected boolean isSnapshotVersionAsBuildNumber() {
		return true;
	}

}