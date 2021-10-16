package com.logonbox.maven.plugins.generator;

import java.io.IOException;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.sonatype.inject.Description;

import com.amazonaws.services.s3.AmazonS3;

/**
 * Copy objects on S3.
 */
@Mojo(defaultPhase = LifecyclePhase.DEPLOY, name = "copy-s3-objects", requiresProject = true, requiresDirectInvocation = false, threadSafe = true)
@Description("Copy S3 objects")
public class CopyS3ObjectsMojo extends AbstractS3UploadMojo {

	@Parameter(required = true)
	protected String sourceBucketName;

	@Parameter(required = true)
	protected String sourceKey;

	@Parameter
	protected String destinationBucketName;

	@Parameter(required = true)
	protected String destinationKey;

	/**
	 * The maven project.
	 */
	@Parameter(required = true, readonly = true, property = "project")
	protected MavenProject project;

	@Override
	protected AmazonS3 upload(AmazonS3 amazonS3) throws IOException {
		String destinationBucketName = this.destinationBucketName;
		if(destinationBucketName == null || destinationBucketName.length() == 0)
			destinationBucketName = sourceBucketName;
		getLog().info(String.format("Copying %s/%s to %s/%s", sourceBucketName, sourceKey, destinationBucketName, destinationKey));
		amazonS3.copyObject(sourceBucketName, sourceKey, destinationBucketName, destinationKey);
		return amazonS3;
	}

}