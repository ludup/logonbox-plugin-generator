package com.logonbox.maven.plugins.generator;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.apache.maven.model.FileSet;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.io.FileUtils;
import org.eclipse.sisu.Description;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.transfer.MultipleFileUpload;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;

/**
 * Upload packages to S3.
 */
@Mojo(defaultPhase = LifecyclePhase.DEPLOY, name = "upload-packages", requiresProject = true, requiresDirectInvocation = false, executionStrategy = "once-per-session", threadSafe = false)
@Description("Upload installer packages")
public class UploadPackages extends AbstractS3UploadMojo {

	@Parameter
	protected FileSet files;

	/**
	 * The maven project.
	 */
	@Parameter(required = true, readonly = true, property = "project")
	protected MavenProject project;

	@Override
	protected void upload(AmazonS3 amazonS3) throws IOException {
		for (File file : toFileList(files)) {
			TransferManager xfer_mgr = TransferManagerBuilder.standard().withS3Client(amazonS3).build();
			MultipleFileUpload xfer = xfer_mgr.uploadDirectory(bucketName, keyPrefix, file, true);
			try {
				XferMgrProgress.showTransferProgress(xfer);
			} finally {
				xfer_mgr.shutdownNow();
			}
		}

	}

	static List<File> toFileList(FileSet fileSet) throws IOException {
		File directory = new File(fileSet.getDirectory());
		String includes = toString(fileSet.getIncludes());
		String excludes = toString(fileSet.getExcludes());
		return FileUtils.getFiles(directory, includes, excludes);
	}

	static String toString(List<String> strings) {
		StringBuilder sb = new StringBuilder();
		for (String string : strings) {
			if (sb.length() > 0)
				sb.append(", ");
			sb.append(string);
		}
		return sb.toString();
	}
}