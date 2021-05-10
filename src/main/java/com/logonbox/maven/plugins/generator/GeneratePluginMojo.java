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
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResult;
import org.apache.maven.shared.transfer.dependencies.DefaultDependableCoordinate;
import org.apache.maven.shared.transfer.dependencies.resolve.DependencyResolverException;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.sisu.Description;

/**
 * Generates the dependencies properties file
 */
@Mojo(threadSafe = true, name = "generate-plugin", requiresProject = true, defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.RUNTIME, requiresDependencyCollection = ResolutionScope.RUNTIME)
@Description("Generates the dependencies for plugin generation")
public class GeneratePluginMojo extends AbstractExtensionsMojo {

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

	/**
	 * Skip generating for POM types
	 */
	@Parameter(defaultValue = "true", property = "plugin-generator.attach")
	private boolean attach = true;
	/**
	 *
	 */
	@Parameter(property = "plugin-generator.classifiersTargets")
	protected Map<String, String> classifiersTargets = new HashMap<>();

	/**
	 * Additional artifacts to add to the extension. A string of the form
	 * groupId:artifactId:version[:packaging[:classifier]].
	 */
	@Parameter(property = "plugin-generator.artifacts")
	private List<String> artifacts;

	@Component
	private MavenProjectHelper projectHelper;

	private List<Artifact> extraArtifacts = new ArrayList<>();

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

			/*
			 * Download extra artifacts (e.g. platform specific jars for SWT, JavaFX etc
			 * without adding them to the primary POM
			 */
			for (String artifact : artifacts) {
				getLog().info("Getting " + artifact);
				String[] tokens = StringUtils.split(artifact, ":");
				if (tokens.length < 3 || tokens.length > 5) {
					throw new MojoFailureException("Invalid artifact, you must specify "
							+ "groupId:artifactId:version[:packaging[:classifier]] " + artifact);
				}
				coordinate.setGroupId(tokens[0]);
				coordinate.setArtifactId(tokens[1]);
				coordinate.setVersion(tokens[2]);
				if (tokens.length >= 4) {
					coordinate.setType(tokens[3]);
				}
				if (tokens.length == 5) {
					coordinate.setClassifier(tokens[4]);
				}

				try {
					doCoordinate();
				} catch (MojoFailureException | DependencyResolverException | ArtifactResolverException e) {
					throw new MojoExecutionException("Failed to process an artifact.", e);
				}

				coordinate = new DefaultDependableCoordinate();
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
					try(FileInputStream fin = new FileInputStream(sourceDef)) {
						props.load(fin);
					}

					File sourceImage = new File(project.getBasedir(), "target" + File.separator + "classes"
							+ File.separator + props.getProperty("extension.image"));

					if (sourceImage.exists()) {
						File destImage = new File(extensionDef, props.getProperty("extension.image"));
						FileUtils.copyFile(sourceImage, destImage);
					}

					File zipfile = new File(extensionDef,
							project.getArtifactId() + "-" + project.getVersion() + ".zip");

					try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(zipfile))) {

						ZipEntry e = new ZipEntry(project.getArtifactId() + "/");
						zip.putNextEntry(e);

						List<Artifact> artifacts = new ArrayList<>();

						getLog().info("Adding " + extraArtifacts.size() + " extra artifacts ");
						artifacts.addAll(extraArtifacts);

						getLog().info("Adding " + project.getArtifacts().size() + " primary artifacts ");
						artifacts.addAll(project.getArtifacts());

						getLog().info("Adding project artifact " + project.getArtifact().getFile().getName());

						e = new ZipEntry(project.getArtifactId() + "/" + project.getArtifact().getFile().getName());
						zip.putNextEntry(e);

						try(FileInputStream fin = new FileInputStream(project.getArtifact().getFile())) {
							IOUtil.copy(fin, zip);
						}

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

							if (isExclude(a)) {
								getLog().info("Artifact " + artifactKey + " is excluded");
								continue;
							} else if (extraArtifacts.contains(a)) {
								getLog().info("Artifact " + artifactKey + " is an extra");
								resolvedFile = a.getFile();
							} else if (!versionMap.containsKey(artifactKey)) {
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

							List<String> folderMaps = getAppendFolderMap(appendFolderMap, a);
							if (folderMaps != null) {

								for (String folder : folderMaps) {

									getLog().info("Adding " + resolvedFile.getName() + " to folder " + folder
											+ " plugin zip");

									String path = project.getArtifactId() + "/" + folder + "/" + resolvedFile.getName();

									if (addedPaths.contains(path)) {
										getLog().info("Already added " + path);
										continue;
									}

									addedPaths.add(path);

									e = new ZipEntry(path);

									zip.putNextEntry(e);

									try(FileInputStream fin = new FileInputStream(resolvedFile)) {
										IOUtil.copy(fin, zip);
									}

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

								try(FileInputStream fin = new FileInputStream(resolvedFile)) {
									IOUtil.copy(fin, zip);
								}

								zip.closeEntry();

							}

						}

						if (archiveFiles != null) {
							for (File file : archiveFiles) {
								zipAndRecurse(file, project.getBasedir(), zip);
							}
						}
					}

					// Generate an MD5 hash
					File md5File = new File(extensionDef,
							project.getArtifactId() + "-" + project.getVersion() + ".md5");
					
					try(FileInputStream zin = new FileInputStream(zipfile)) {
						FileUtils.fileWrite(md5File.getAbsolutePath(), DigestUtils.md5Hex(zin));
					}

					try(FileInputStream zin = new FileInputStream(md5File)) {
						getLog().info("MD5 sum value is " + IOUtil.toString(zin));
					}

					getLog().info("Copying archive to local store " + storeTarget.getAbsolutePath());

					FileUtils.copyFile(zipfile, storeTarget);

					if (attach) {
						getLog().info("Attaching artifact as extension-archive zip");
						projectHelper.attachArtifact(project, "zip", "extension-archive", storeTarget);
					}
				} finally {
					lock.release();
				}
			}

		} catch (Exception e) {
			getLog().error(e);
			throw new MojoExecutionException("Unable to create dependencies file: " + e, e);
		}
	}

	private List<String> getAppendFolderMap(Map<String, List<String>> appendFolderMap, Artifact a) {
		List<String> maps = new ArrayList<>();
		for (Map.Entry<String, List<String>> en : appendFolderMap.entrySet()) {
			if (matches(a, en.getKey())) {
				maps.addAll(en.getValue());
			}
		}
		return maps.isEmpty() ? null : maps;
	}

	private boolean matches(Artifact a, String key) {
		String[] args = key.split(":");
		if (args.length == 2) {
			return (args[0].equals("") || a.getGroupId().equals(args[0]))
					&& (args[1].equals("") || a.getArtifactId().equals(args[1]));
		} else if (args.length == 3) {
			return (args[0].equals("") || a.getGroupId().equals(args[0]))
					&& (args[1].equals("") || a.getArtifactId().equals(args[1]))
					&& (args[2].equals("") || args[2].equals(a.getClassifier()));
		}
		return a.getArtifactId().equals(key);
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
			try(FileInputStream fin = new FileInputStream(file)) {
				IOUtil.copy(fin, zip);
			}
			zip.closeEntry();
		}
	}

	@Override
	protected void doHandleResult(ArtifactResult result)
			throws MojoExecutionException, DependencyResolverException, ArtifactResolverException, IOException {
		/* Only used for extra artifacts */
		extraArtifacts.add(result.getArtifact());
	}

	@Override
	protected boolean isSnapshotVersionAsBuildNumber() {
		return false;
	}
}