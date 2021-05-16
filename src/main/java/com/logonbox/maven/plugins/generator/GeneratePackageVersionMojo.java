package com.logonbox.maven.plugins.generator;

import java.text.SimpleDateFormat;
import java.util.Date;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.sonatype.inject.Description;
 
/**
 * Generates a package version (based on the project version) into a variable to
 * is appropriate for a Debian package.
 * 
 */
@Mojo(threadSafe = true, name = "generate-debian-package-version", defaultPhase = LifecyclePhase.INITIALIZE)
@Description("Generates a Debian package version the project version")
public class GeneratePackageVersionMojo extends AbstractBaseExtensionsMojo {

	/**
	 * The property name to generate
	 */
	@Parameter(defaultValue =  "debianPackageVersion", property = "plugin-generator.property-name")
	private String propertyName;
	
	protected void onExecute() throws MojoExecutionException, MojoFailureException {

		int idx = project.getVersion().lastIndexOf("-");
		String suffix = null;
		if (idx == -1) {
			suffix = "-GA";
		}
		String buildNo = new SimpleDateFormat("yyyyMMddHHmm").format(new Date());
		project.getProperties().setProperty(propertyName, project.getVersion() + (suffix == null ? "" : suffix) + "-" + buildNo);
	}

}