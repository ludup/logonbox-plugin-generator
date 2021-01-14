package com.logonbox.maven.plugins.generator;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.eclipse.sisu.Description;

/**
 * Generates the dependencies properties file
 */
@Mojo(threadSafe = true, name = "generate-plugin", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.RUNTIME)
@Description("Generates the dependencies for plugin generation")
public class GeneratePluginMojo extends AbstractMojo {

	protected static final String SEPARATOR = "/";

	/**
	 * The maven project.
	 * 
	 * @parameter expression="${project}"
	 * @required
	 * @readonly
	 */
	@Parameter(required = true, readonly = true, property = "project")
	protected MavenProject project;

	/**
	 * The file to generate
	 */
	@Parameter(required = true, property = "plugin-generator.resolved-dependencies-project")
	private String resolvedDependenciesProject;

	/**
	 * The dependencies to process for duplicate artifacts
	 * 
	 */
	@Parameter(property = "plugin-generator.dependencies")
	private String[] dependencies;

	/**
	 * The additional files to archive
	 */
	@Parameter(property = "plugin-generator.archive-files")
	private File[] archiveFiles;

	/**
	 * Ignore specific groupId/artifactId
	 */
	@Parameter(property = "plugin-generator.ignores")
	private String[] ignores;

	/**
	 * Move artifacts into specific folders
	 */
	@Parameter(property = "plugin-generator.append-folders")
	private String[] appendFolders;

	/**
	 * Skip generating for POM types
	 */
	@Parameter(defaultValue = "true", property = "plugin-generator.skip-poms")
	private boolean skipPoms = true;

	@SuppressWarnings("unchecked")
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (skipPoms && "pom".equals(project.getPackaging())) {
			getLog().info("Skipping POM project " + project.getName());
			return;
		}

		getLog().info(project.getBasedir().getAbsolutePath());
		getLog().info(project.getExecutionProject().getBasedir().getAbsolutePath());

		File allDependenciesFile = new File(project.getBasedir().getParentFile(),
				resolvedDependenciesProject + File.separator + "target" + File.separator + "dependencies.ser");

		getLog().info("Using all dependencies file " + allDependenciesFile.getAbsolutePath());

		try {

			if (!allDependenciesFile.exists()) {
				throw new MojoExecutionException(
						"There is no dependency map to generate plugins. Did you execute resolve-dependencies goal first?");
			}

			try (@SuppressWarnings("resource")
			FileChannel channel = new RandomAccessFile(allDependenciesFile, "rw").getChannel()) {
				InputStream in = Channels.newInputStream(channel);
				FileLock lock = getLock(channel);
				try {

					ObjectInputStream obj = new ObjectInputStream(in);
					Map<String, String> versionMap = (Map<String, String>) obj.readObject();
					Map<String, File> artifactMap = (Map<String, File>) obj.readObject();

					Map<String, String> coreVersionMap = new HashMap<String, String>();

					if (dependencies != null) {
						for (String dependency : dependencies) {

							File dependencyFile = new File(project.getBasedir().getParentFile(),
									dependency + File.separator + "target" + File.separator + "dependencies.ser");
							if (dependencyFile.exists()) {

								try (@SuppressWarnings("resource")
								FileChannel channel2 = new RandomAccessFile(dependencyFile, "rw").getChannel()) {
									InputStream in2 = Channels.newInputStream(channel2);
									FileLock lock2 = getLock(channel2);
									try {
										ObjectInputStream obj2 = new ObjectInputStream(in2);
										coreVersionMap.putAll((Map<String, String>) obj2.readObject());
									} finally {
										lock2.release();
									}
								}
							} else {
								getLog().info(dependencyFile.getAbsolutePath() + " does not exist");
							}
						}
					}

					if (ignores != null) {
						for (String ignore : ignores) {
							coreVersionMap.put(ignore, "IGNORED");
							versionMap.put(ignore, "IGNORED");
						}
					}

					File storeTarget = new File(project.getParent().getBasedir(),
							"target" + File.separator + "extensions" + File.separator + project.getArtifactId()
									+ File.separator + project.getArtifactId() + "-" + project.getVersion() + ".zip");

					storeTarget.getParentFile().mkdirs();

					File extensionDef = new File(project.getBasedir(),
							"target" + File.separator + "extension-def" + File.separator + project.getArtifactId());
					extensionDef.mkdirs();

					File sourceDef = new File(project.getBasedir(),
							"target" + File.separator + "classes" + File.separator + "extension.def");
					File destDef = new File(extensionDef, project.getArtifactId() + ".def");
					FileUtils.copyFile(sourceDef, destDef);

					File sourceI18n = new File(project.getBasedir(),
							"target" + File.separator + "classes" + File.separator + "i18n");
					File destI18n = new File(extensionDef, "i18n");

					FileUtils.copyDirectory(sourceI18n, destI18n, project.getArtifactId() + "*", null);

					Properties props = new Properties();
					props.load(new FileInputStream(sourceDef));

					File sourceImage = new File(project.getBasedir(), "target" + File.separator + "classes"
							+ File.separator + props.getProperty("extension.image"));

					if (sourceImage.exists()) {
						File destImage = new File(extensionDef, props.getProperty("extension.image"));
						FileUtils.copyFile(sourceImage, destImage);
					}

					File zipfile = new File(extensionDef,
							project.getArtifactId() + "-" + project.getVersion() + ".zip");

					ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(zipfile));

					ZipEntry e = new ZipEntry(project.getArtifactId() + "/");
					zip.putNextEntry(e);

					Set<Artifact> artifacts = project.getArtifacts();

					getLog().info("Adding project artifact " + project.getArtifact().getFile().getName());

					e = new ZipEntry(project.getArtifactId() + "/" + project.getArtifact().getFile().getName());
					zip.putNextEntry(e);

					IOUtil.copy(new FileInputStream(project.getArtifact().getFile()), zip);

					zip.closeEntry();
					Set<String> addedPaths = new HashSet<String>();
					Map<String, List<String>> appendFolderMap = new HashMap<String, List<String>>();

					if (appendFolders != null) {
						for (String af : appendFolders) {
							int idx = af.indexOf('=');
							String artifactId = af.substring(0, idx);
							String folder = af.substring(idx + 1);

							getLog().info("Will append folder " + folder + " to artifact " + artifactId);

							if (!appendFolderMap.containsKey(artifactId)) {
								appendFolderMap.put(artifactId, new ArrayList<String>());
							}

							appendFolderMap.get(artifactId).add(folder);
						}
					}
					for (Artifact a : artifacts) {

						String artifactKey = ResolveDependenciesMojo.makeKey(a);
						File resolvedFile = null;

						if (!versionMap.containsKey(artifactKey)) {
							getLog().info("Artifact " + artifactKey + " IS MISSING from version map. Is "
									+ project.getArtifactId()
									+ " included in the application group dependency pom i.e. app-enterprise?");
							continue;
						} else {

							String resolvedVersion = versionMap.get(artifactKey);
							if (coreVersionMap.containsKey(artifactKey)) {
								getLog().info("Artifact " + artifactKey + " " + resolvedVersion
										+ " (omitted due to being part of core dependencies)");
								continue;
							} else {
								if (!resolvedVersion.equals(a.getBaseVersion())) {
									getLog().info("Artifact " + artifactKey + " " + resolvedVersion
											+ " (omitted version " + a.getBaseVersion() + ")");
								} else {
									getLog().info("Artifact " + artifactKey + " " + resolvedVersion);
								}

								resolvedFile = artifactMap.get(artifactKey);
							}
						}

						if (!resolvedFile.exists()) {
							getLog().warn(resolvedFile.getAbsolutePath() + " does not exist!");
							continue;
						}
						if (resolvedFile.isDirectory()) {
							getLog().warn(resolvedFile.getAbsolutePath() + " is a directory");
							resolvedFile = a.getFile();
						}

						if (appendFolderMap.containsKey(a.getArtifactId())) {

							for (String folder : appendFolderMap.get(a.getArtifactId())) {

								getLog().info(
										"Adding " + resolvedFile.getName() + " to folder " + folder + " plugin zip");

								String path = project.getArtifactId() + "/" + folder + "/" + resolvedFile.getName();

								if (addedPaths.contains(path)) {
									getLog().info("Already added " + path);
									continue;
								}

								addedPaths.add(path);

								e = new ZipEntry(path);

								zip.putNextEntry(e);

								IOUtil.copy(new FileInputStream(resolvedFile), zip);

								zip.closeEntry();
							}

						} else {

							getLog().info("Adding " + resolvedFile.getName() + " to plugin zip");

							String path = project.getArtifactId() + "/" + resolvedFile.getName();

							if (addedPaths.contains(path)) {
								getLog().info("Already added " + path);
								continue;
							}

							addedPaths.add(path);

							e = new ZipEntry(path);

							zip.putNextEntry(e);

							IOUtil.copy(new FileInputStream(resolvedFile), zip);

							zip.closeEntry();

						}

					}

					if (archiveFiles != null) {
						for (File file : archiveFiles) {
							zipAndRecurse(file, project.getBasedir(), zip);
						}
					}

					zip.close();

					// Generate an MD5 hash
					File md5File = new File(extensionDef,
							project.getArtifactId() + "-" + project.getVersion() + ".md5");
					FileUtils.fileWrite(md5File.getAbsolutePath(), DigestUtils.md5Hex(new FileInputStream(zipfile)));

					getLog().info("MD5 sum value is " + IOUtil.toString(new FileInputStream(md5File)));

					getLog().info("Copying archive to local store " + storeTarget.getAbsolutePath());

					FileUtils.copyFile(zipfile, storeTarget);
				} finally {
					lock.release();
				}
			}

		} catch (Exception e) {
			getLog().error(e);
			throw new MojoExecutionException("Unable to create dependencies file: " + e, e);
		}
	}

	public FileLock getLock(FileChannel channel) throws IOException {
		while (true) {
			try {
				return channel.tryLock();
			} catch (OverlappingFileLockException ofl) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					throw new IOException("Interrupted.", e);
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
			zip.putNextEntry(new ZipEntry(
					project.getArtifactId() + file.getAbsolutePath().replace(parent.getAbsolutePath(), "")));
			IOUtil.copy(new FileInputStream(file), zip);
			zip.closeEntry();
		}
	}
}