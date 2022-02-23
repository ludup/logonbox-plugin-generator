package com.logonbox.maven.plugins.generator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.sonatype.inject.Description;

@Mojo(threadSafe = true, name = "generate-pf4j-plugin", requiresProject = true, defaultPhase = LifecyclePhase.COMPILE, requiresDependencyResolution = ResolutionScope.RUNTIME, requiresDependencyCollection = ResolutionScope.RUNTIME)
@Description("Generates the dependencies for PF4J plugin generation")
public class PF4JPluginMojo extends AbstractPF4JPluginMojo {

	@Component
	private MavenProjectHelper projectHelper;

	/**
	 * Skip generating for POM types
	 */
	@Parameter(defaultValue = "true", property = "plugin-generator.attach")
	private boolean attach = true;

	@Override
	protected void doPF4JPlugin(Map<String, File> artifactMap, Map<String, String> versionMap,
			Map<String, String> coreVersionMap, Properties properties, Map<String, List<String>> appendFolderMap)
			throws IOException {
		File storeTarget = new File(project.getParent().getBasedir(),
				"target" + File.separator + "extensions" + File.separator + project.getArtifactId() + File.separator
						+ project.getArtifactId() + "-" + project.getVersion() + ".zip");

		storeTarget.getParentFile().mkdirs();

		File extensionDef = new File(project.getBasedir(),
				"target" + File.separator + "extension-def" + File.separator + project.getArtifactId());
		extensionDef.mkdirs();

		File zipfile = new File(extensionDef, project.getArtifactId() + "-" + project.getVersion() + ".zip");

		List<Artifact> artifacts = new ArrayList<>();

		getLog().info("Adding " + extraArtifacts.size() + " extra artifacts ");
		artifacts.addAll(extraArtifacts);

		getLog().info("Adding " + project.getArtifacts().size() + " primary artifacts ");
		artifacts.addAll(project.getArtifacts());

		generatePF4JZip(versionMap, artifactMap, coreVersionMap, properties, zipfile, artifacts, appendFolderMap,
				extensionDef);

		// Generate an MD5 hash
		File md5File = new File(extensionDef, project.getArtifactId() + "-" + project.getVersion() + ".md5");

		try (FileInputStream zin = new FileInputStream(zipfile)) {
			FileUtils.fileWrite(md5File.getAbsolutePath(), DigestUtils.md5Hex(zin));
		}

		try (FileInputStream zin = new FileInputStream(md5File)) {
			getLog().info("MD5 sum value is " + IOUtil.toString(zin));
		}

		getLog().info("Copying archive to local store " + storeTarget.getAbsolutePath());

		FileUtils.copyFile(zipfile, storeTarget);

		if (attach) {
			getLog().info("Attaching artifact as extension-archive zip");
			projectHelper.attachArtifact(project, "zip", "extension-archive", storeTarget);
		}

	}

	protected void generatePF4JZip(Map<String, String> versionMap, Map<String, File> artifactMap,
			Map<String, String> coreVersionMap, Properties properties, File zipfile, List<Artifact> artifacts,
			Map<String, List<String>> appendFolderMap, File extensionDef) throws IOException, FileNotFoundException {

		try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(zipfile))) {

			ZipEntry e = new ZipEntry("plugin.properties");
			zip.putNextEntry(e);
			;
			properties.store(zip, "LogonBox PF4J Plugin");
			zip.closeEntry();

			getLog().info("Adding PF4J project artifact " + project.getBuild().getOutputDirectory());
			File projectBuildDir = new File(project.getBuild().getOutputDirectory());
			for (File file : projectBuildDir.listFiles()) {
				zipAndRecurse(file, projectBuildDir.getParentFile(), zip);
			}

//			e = new ZipEntry("classes/" + project.getArtifact().getFile().getName());
//			zip.putNextEntry(e);
//
//			try (FileInputStream fin = new FileInputStream(project.getArtifact().getFile())) {
//				IOUtil.copy(fin, zip);
//			}
//
//			zip.closeEntry();

			Set<String> addedPaths = new HashSet<String>();
			for (Artifact a : artifacts) {

				String artifactKey = ResolveDependenciesMojo.makeKey(a);
				File resolvedFile = null;

				if (isExclude(a)) {
					getLog().info("PF4J Artifact " + artifactKey + " is excluded");
					continue;
				} else if (extraArtifacts.contains(a)) {
					getLog().info("PF4J Artifact " + artifactKey + " is an extra");
					resolvedFile = a.getFile();
				} else if (!versionMap.containsKey(artifactKey)) {
					getLog().info("PF4J Artifact " + artifactKey + " IS MISSING from version map. Is "
							+ project.getArtifactId()
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

						getLog().info("PF4J Adding " + resolvedFile.getName() + " to folder " + folder + " plugin zip");

						String path = "lib/" + folder + "/" + resolvedFile.getName();

						if (addedPaths.contains(path)) {
							getLog().info("Already added " + path);
							continue;
						}

						addedPaths.add(path);

						e = new ZipEntry(path);

						zip.putNextEntry(e);

						try (FileInputStream fin = new FileInputStream(resolvedFile)) {
							IOUtil.copy(fin, zip);
						}

						zip.closeEntry();
					}

				} else {

					getLog().info("PF4J Adding " + resolvedFile.getName() + " to plugin zip");

					String path = "lib/" + resolvedFile.getName();

					if (addedPaths.contains(path)) {
						getLog().info("Already added " + path);
						continue;
					}

					addedPaths.add(path);

					e = new ZipEntry(path);

					zip.putNextEntry(e);

					try (FileInputStream fin = new FileInputStream(resolvedFile)) {
						IOUtil.copy(fin, zip);
					}

					zip.closeEntry();

				}

			}
		}
	}

	private void zipAndRecurse(File file, File parent, ZipOutputStream zip) throws FileNotFoundException, IOException {

		if (file.isDirectory()) {
			for (File child : file.listFiles()) {
				zipAndRecurse(child, parent, zip);
			}
		} else {
			zip.putNextEntry(new ZipEntry(file.getAbsolutePath().replace(parent.getAbsolutePath(), "")));
			try (FileInputStream fin = new FileInputStream(file)) {
				IOUtil.copy(fin, zip);
			}
			zip.closeEntry();
		}
	}
}