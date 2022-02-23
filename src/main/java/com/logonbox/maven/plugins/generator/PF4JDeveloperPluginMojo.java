package com.logonbox.maven.plugins.generator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.codehaus.plexus.util.FileUtils;
import org.sonatype.inject.Description;

@Mojo(threadSafe = true, name = "generate-pf4j-developer-plugin", requiresProject = true, defaultPhase = LifecyclePhase.COMPILE, requiresDependencyResolution = ResolutionScope.RUNTIME, requiresDependencyCollection = ResolutionScope.RUNTIME)
@Description("Generates the dependencies for PF4J Developer mode plugin generation")
public class PF4JDeveloperPluginMojo extends AbstractPF4JPluginMojo {

	@Override
	protected void doPF4JPlugin(Map<String, File> artifactMap, Map<String, String> versionMap,
			Map<String, String> coreVersionMap, Properties properties, Map<String, List<String>> appendFolderMap)
			throws IOException {

		File projectTargetDir = new File(project.getBasedir(), "target");
		File pluginsDir = new File(new File(projectTargetDir, properties.getProperty("plugin.id")), "plugins");
		if(!pluginsDir.exists() && !pluginsDir.mkdirs()) {
			throw new IOException("Could not create " + pluginsDir);
		}
		getLog().info("Developer plugin target dir " + pluginsDir);
		File targetDir = new File(pluginsDir, "target");
		if(!targetDir.exists() && !targetDir.mkdirs()) {
			throw new IOException("Could not create " + pluginsDir);
		}
		File classesTarget = new File(targetDir, "classes");
		File libTarget = new File(targetDir, "lib");
		File pluginPropertiesTarget = new File(pluginsDir, "plugin.properties");
		
		try {
			File linkTarget = new File(project.getBuild().getOutputDirectory());
			getLog().info("Linking " + classesTarget + " to " + linkTarget);
			Files.createSymbolicLink(classesTarget.toPath(), linkTarget.toPath());
		}
		catch(UnsupportedOperationException uoe) {
			getLog().warn("Falling back to copying classes, hot code replace may not work with this plugin.");
			FileUtils.copyDirectory(projectTargetDir, classesTarget);
		}
			

		getLog().info("Saving developer plugin properties to " + pluginPropertiesTarget);
		try (OutputStream out = new FileOutputStream(pluginPropertiesTarget)) {
			properties.store(out, "LogonBox PF4J Plugin");
		}

		Set<String> addedPaths = new HashSet<String>();
		for (Artifact a : project.getArtifacts()) {

			String artifactKey = ResolveDependenciesMojo.makeKey(a);
			File resolvedFile = null;

			if (isExclude(a)) {
				getLog().info("PF4J Artifact " + artifactKey + " is excluded");
				continue;
			} else if (extraArtifacts.contains(a)) {
				getLog().info("PF4J Artifact " + artifactKey + " is an extra");
				resolvedFile = a.getFile();
			} else if (!versionMap.containsKey(artifactKey)) {
				getLog().info(
						"PF4J Artifact " + artifactKey + " IS MISSING from version map. Is " + project.getArtifactId()
								+ " included in the application group dependency pom i.e. app-enterprise?");
				continue;
			} else {

				String resolvedVersion = versionMap.get(artifactKey);
				if (coreVersionMap.containsKey(artifactKey)) {
					getLog().info("PF4J Artifact " + artifactKey + " " + resolvedVersion
							+ " (omitted due to being part of core dependencies)");
					continue;
				} else {
					if (!resolvedVersion.equals(a.getBaseVersion())) {
						getLog().info("Artifact " + artifactKey + " " + resolvedVersion + " (omitted version "
								+ a.getBaseVersion() + ")");
					} else {
						getLog().info("Artifact " + artifactKey + " " + resolvedVersion);
					}

					resolvedFile = artifactMap.get(artifactKey);
				}
			}

			if (!resolvedFile.exists()) {
				getLog().warn("PF4J  " + resolvedFile.getAbsolutePath() + " does not exist!");
				continue;
			}
			if (resolvedFile.isDirectory()) {
				getLog().warn("PF4J " + resolvedFile.getAbsolutePath() + " is a directory");
				resolvedFile = a.getFile();
			}

			List<String> folderMaps = getAppendFolderMap(appendFolderMap, a);
			if (folderMaps != null) {
				for (String folder : folderMaps) {
					getLog().info("PF4J Adding " + resolvedFile.getName() + " to folder " + folder + " developer plugin");

					String path = "lib/" + folder + "/" + resolvedFile.getName();

					if (addedPaths.contains(path)) {
						getLog().info("Already added " + path);
						continue;
					}

					addedPaths.add(path);
					FileUtils.copyFile(resolvedFile, new File(libTarget, resolvedFile.getName()));
				}

			} else {
				getLog().info("PF4J Adding " + resolvedFile.getName() + " to developer plugin lib");
				String path = "lib/" + resolvedFile.getName();
				if (addedPaths.contains(path)) {
					getLog().info("Already added " + path);
					continue;
				}
				addedPaths.add(path);
				FileUtils.copyFile(resolvedFile, new File(libTarget, resolvedFile.getName()));
			}

		}
	}

}