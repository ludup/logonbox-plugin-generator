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
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.sisu.Description;

/**
 * Generates the dependencies properties file
 */
@Mojo(threadSafe = true, name = "resolve-dependencies", defaultPhase = LifecyclePhase.COMPILE, requiresDependencyResolution = ResolutionScope.RUNTIME)
@Description("Generates the dependencies for plugin generation")
public class ResolveDependenciesMojo extends AbstractMojo {

	protected static final String SEPARATOR = "/";

	/**
	 * The maven project.
	 */
	@Parameter(required = true, readonly = true, property = "project")
	protected MavenProject project;

	public void execute() throws MojoExecutionException, MojoFailureException {

		File outputFile = new File(project.getBasedir(), "target" + File.separator + "dependencies.ser");
		outputFile.getParentFile().mkdirs();

		try (@SuppressWarnings("resource")
		FileChannel channel = new RandomAccessFile(outputFile, "rw").getChannel()) {
			OutputStream out = Channels.newOutputStream(channel);
			FileLock lock = getLock(channel);
			try {

				Map<String, String> versionMap = new HashMap<String, String>();
				Map<String, File> artifactMap = new HashMap<String, File>();

				getLog().info("Generating dependencies map");

				Set<Artifact> artifacts = project.getArtifacts();
				for (Artifact a : artifacts) {

					String key = makeKey(a);

					if (a.getScope().equalsIgnoreCase("system")) {
						getLog().info("Ignoring system scope artifact " + key);
						continue;
					}

					getLog().info("Dependency " + key + " version=" + a.getBaseVersion() + " scope=" + a.getScope()
							+ " type=" + a.getType() + " url=" + a.getDownloadUrl() + " file="
							+ a.getFile().getAbsolutePath());

					versionMap.put(key, a.getBaseVersion());
					artifactMap.put(key, a.getFile());
				}

				getLog().info("Adding project " + project.getGroupId() + "/" + project.getArtifactId());

				versionMap.put(project.getGroupId() + "/" + project.getArtifactId(), project.getVersion());
				artifactMap.put(project.getGroupId() + "/" + project.getArtifactId(), project.getFile());

				ObjectOutputStream obj = new ObjectOutputStream(out);
				obj.writeObject(versionMap);
				obj.writeObject(artifactMap);
				obj.flush();

				getLog().info("Created: " + outputFile);
			} finally {
				lock.release();
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

	public static String makeKey(Artifact a) {
		if (a.getClassifier() == null || a.getClassifier().equals(""))
			return a.getGroupId() + "/" + a.getArtifactId();
		else
			return a.getGroupId() + "/" + a.getArtifactId() + ":" + a.getClassifier();
	}

}