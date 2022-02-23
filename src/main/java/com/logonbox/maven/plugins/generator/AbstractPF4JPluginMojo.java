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
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResult;
import org.apache.maven.shared.transfer.dependencies.DefaultDependableCoordinate;
import org.apache.maven.shared.transfer.dependencies.resolve.DependencyResolverException;
import org.codehaus.plexus.util.StringUtils;

import edu.emory.mathcs.backport.java.util.Arrays;

/**
 * Generates the dependencies properties file
 */
public abstract class AbstractPF4JPluginMojo extends AbstractExtensionsMojo {

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
	 * Skip generating for POM types
	 */
	@Parameter(defaultValue = "true", property = "plugin-generator.skip-poms")
	private boolean skipPoms = true;

	/**
	 * Move artifacts into specific folders
	 */
	@Parameter(property = "plugin-generator.append-folders")
	protected String[] appendFolders;
	
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

	protected List<Artifact> extraArtifacts = new ArrayList<>();

	@SuppressWarnings("unchecked")
	protected void onExecute() throws MojoExecutionException, MojoFailureException {
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
				throw new MojoExecutionException("There is no dependency map '" + allDependenciesFile
						+ "' to generate plugins. Did you execute resolve-dependencies goal first?");
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

					if (ignores != null) {
						for (String ignore : ignores) {
							coreVersionMap.put(ignore, "IGNORED");
							versionMap.put(ignore, "IGNORED");
						}
					}
					
					doPF4JPlugin(artifactMap, versionMap, coreVersionMap, loadProperties(project.getBasedir()), appendFolderMap);

				} finally {
					lock.release();
				}
			}

		} catch (Exception e) {
			getLog().error(e);
			throw new MojoExecutionException("Unable to create dependencies file: " + e, e);
		}
	}
	
	protected Properties loadProperties(File dir) throws IOException {
		Properties extProperties = new Properties();
		Properties pluginProperties = new Properties();
		File properties = new File(new File(new File(new File(dir, "src"), "main"), "plugin"), "plugin.properties");
		File extDefFile = new File(new File(new File(new File(dir, "src"), "main"), "resources"), "extension.def");
		
		if(properties.exists()) {
			getLog().info("Found plugin.properties file");
			try(InputStream in = new FileInputStream(properties)) {
				pluginProperties.load(in);
			}	
		}
		
		if(extDefFile.exists()) {
			getLog().info("Found legacy extension.def file");
			try(InputStream in = new FileInputStream(extDefFile)) {
				extProperties.load(in);
			}
			
			if(extProperties.containsKey("extension.plugin") && !pluginProperties.contains("plugin.class")) {
				pluginProperties.put("plugin.class", extProperties.get("extension.plugin"));
			}

			if(!extProperties.getProperty("extension.depends", "").trim().equals("") && !pluginProperties.contains("plugin.dependencies")) {
				@SuppressWarnings("unchecked")
				List<String> depends = new ArrayList<String>(Arrays.asList(extProperties.getProperty("extension.depends").split(",")));
				depends.remove("server-core");
				if(!depends.isEmpty())
					pluginProperties.put("plugin.dependencies", String.join(", ", depends));
			}
		}
		if(!pluginProperties.containsKey("plugin.class"))
			pluginProperties.put("plugin.class", "com.hypersocket.plugins.DefaultPlugin");
		if(!pluginProperties.containsKey("plugin.id"))
			pluginProperties.put("plugin.id", project.getArtifactId());
		if(!pluginProperties.containsKey("plugin.version"))
			pluginProperties.put("plugin.version", stripSnapshot(project.getVersion()));
		if(!pluginProperties.containsKey("plugin.requires"))
			pluginProperties.put("plugin.requires", stripSnapshot(project.getVersion()));
		if(!pluginProperties.containsKey("plugin.description"))
			pluginProperties.put("plugin.description", project.getDescription() == null ? ( project.getName() == null ? project.getArtifactId() : project.getName() ) : project.getDescription());
		if(!pluginProperties.containsKey("plugin.provider"))
			pluginProperties.put("plugin.provider", project.getOrganization() == null || project.getOrganization().getName() == null ? "LogonBox" :  project.getOrganization().getName());
		if(!pluginProperties.containsKey("plugin.license"))
			pluginProperties.put("plugin.license", project.getLicenses().isEmpty() ? "Commercial" : project.getLicenses().get(0).getName());
	
		return pluginProperties;
	}

	protected abstract void doPF4JPlugin(Map<String, File> artifactMap, Map<String, String> versionMap,
			Map<String, String> coreVersionMap, Properties properties, Map<String, List<String>> appendFolderMap) throws IOException;

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

	protected List<String> getAppendFolderMap(Map<String, List<String>> appendFolderMap, Artifact a) {
		List<String> maps = new ArrayList<>();
		for (Map.Entry<String, List<String>> en : appendFolderMap.entrySet()) {
			if (matches(a, en.getKey())) {
				maps.addAll(en.getValue());
			}
		}
		return maps.isEmpty() ? null : maps;
	}

	protected String stripSnapshot(String version) {
		int idx = version.indexOf("-SNAPSHOT");
		if(idx == -1)
			return version;
		else
			return version.substring(0, idx);
	}

}