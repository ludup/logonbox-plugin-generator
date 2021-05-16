package com.logonbox.maven.plugins.generator;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.sonatype.inject.Description;

/**
 * Set a property
 */
@Mojo(name = "set-property", requiresProject = true, threadSafe = true)
@Description("Sets a property")
public class SetPropertyMojo extends AbstractBaseExtensionsMojo {

	@Parameter(required = true)
	private String name;

	@Parameter(required = true)
	private String value;

	protected void onExecute() throws MojoExecutionException, MojoFailureException {
		getLog().info(String.format("Setting '%s' to '%s'", name, value));
		project.getProperties().setProperty(name, value);
	}

}