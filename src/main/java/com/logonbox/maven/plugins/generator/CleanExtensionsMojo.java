package com.logonbox.maven.plugins.generator;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.sisu.Description;

/**
 * Clean extension dependency files.
 */
@Mojo(name = "clean", requiresProject = true, requiresDirectInvocation = false, executionStrategy = "once-per-session", threadSafe = true)
@Description("Cleans extension files")
public class CleanExtensionsMojo extends AbstractMojo {

	/**
	 * The directory where the generated flattened POM file will be written to.
	 */
	@Parameter(defaultValue = "${project.basedir}")
	private File outputDirectory;

	public void execute() throws MojoExecutionException, MojoFailureException {
		new File(outputDirectory, "dependencies.ser").delete();
		try {
			FileUtils.deleteDirectory(new File(outputDirectory, "extension-def"));
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to delete extension-def", e);
		}
		try {
			FileUtils.deleteDirectory(new File(outputDirectory, "extension-store"));
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to delete extension-def", e);
		}
	}

}