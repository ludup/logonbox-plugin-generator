package com.logonbox.maven.plugins.generator;

import java.io.File;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.sisu.Description;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.transfer.MultipleFileUpload;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;

/**
 * Upload extensions to S3.
 */
@Mojo(defaultPhase = LifecyclePhase.DEPLOY, name = "upload-to-s3", requiresProject = true, requiresDirectInvocation = false, executionStrategy = "once-per-session", threadSafe = false)
@Description("Upload an extension store to S3 and inform the update server")
public class UploadExtensions extends AbstractS3UploadMojo {

	@Parameter(defaultValue = "${project.build.directory}/extension-store", property = "upload-to-s3.extensions", required = true)
	protected File extensions;

	/**
	 * The maven project.
	 */
	@Parameter(required = true, readonly = true, property = "project")
	protected MavenProject project;

	@Override
	protected void upload(AmazonS3 amazonS3) {

		TransferManager xfer_mgr = TransferManagerBuilder.standard().withS3Client(amazonS3).build();
		MultipleFileUpload xfer = xfer_mgr.uploadDirectory(bucketName, keyPrefix, extensions, true);
		try {
			XferMgrProgress.showTransferProgress(xfer);
		} finally {
			xfer_mgr.shutdownNow();
		}

	}

}