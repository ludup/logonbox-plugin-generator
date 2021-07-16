package com.logonbox.maven.plugins.generator;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.settings.Settings;
import org.apache.maven.shared.transfer.artifact.ArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResult;
import org.apache.maven.shared.transfer.dependencies.DefaultDependableCoordinate;
import org.apache.maven.shared.transfer.dependencies.DependableCoordinate;
import org.apache.maven.shared.transfer.dependencies.resolve.DependencyResolver;
import org.apache.maven.shared.transfer.dependencies.resolve.DependencyResolverException;
import org.codehaus.plexus.util.StringUtils;
import org.w3c.dom.Document;

import edu.emory.mathcs.backport.java.util.concurrent.atomic.AtomicInteger;

/**
 * Resolves and downloads all of a projects extensions and place the resulting
 * zips in a specific diretory. Based on {@link GetMojo}.
 */
public abstract class AbstractExtensionsMojo extends AbstractBaseExtensionsMojo {
	
	protected static final String EXTENSION_ARCHIVE = "extension-archive";

	private static final List<String> DEFAULT_GROUPS = Arrays.asList("com.hypersocket", "com.logonbox", "com.nervepoint", "com.sshtools", "com.jadaptive");
	private static final Pattern ALT_REPO_SYNTAX_PATTERN = Pattern.compile("(.+)::(.*)::(.+)");

	@Parameter(defaultValue = "${session}", required = true, readonly = true)
	protected MavenSession session;

	/**
	 *
	 */
	@Component
	protected ArtifactResolver artifactResolver;

	/**
	 *
	 */
	@Component
	protected DependencyResolver dependencyResolver;

	@Component
	protected ArtifactHandlerManager artifactHandlerManager;

	/**
	 * Map that contains the layouts.
	 */
	@Component(role = ArtifactRepositoryLayout.class)
	protected Map<String, ArtifactRepositoryLayout> repositoryLayouts;

	/**
	 * The repository system.
	 */
	@Component
	protected RepositorySystem repositorySystem;

	protected DefaultDependableCoordinate coordinate = new DefaultDependableCoordinate();

	/**
	 * Repositories in the format id::[layout]::url or just url, separated by comma.
	 * ie.
	 * central::default::https://repo.maven.apache.org/maven2,myrepo::::https://repo.acme.com,https://repo.acme2.com
	 */
	@Parameter(property = "extensions.remoteRepositories")
	protected String remoteRepositories;

	/**
	 *
	 */
	@Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true, required = true)
	protected List<ArtifactRepository> pomRemoteRepositories;
	/**
	 *
	 */
	@Parameter(property = "extensions.excludeClassifiers")
	protected List<String> excludeClassifiers;
	/**
	 * Which groups can contain extensions. This can massively speed up dependency 
	 * by not needlessly contacting a Maven repository to determine if an artifact has
	 * a extension archive artifact as well (which it tries to do for ALL dependencies
	 * including 3rd party ones that will never has an extension archive). This provides
	 * a way to optimise this, as we only have a few group names that have extensions. 
	 */
	@Parameter(property = "extensions.groups")
	protected List<String> groups;

	/**
	 * Location of the file.
	 */
	@Parameter(defaultValue = "${project.build.directory}/artifacts", property = "extensions.output", required = true)
	protected File output;

	/**
	 * Skip plugin execution completely.
	 *
	 * @since 2.7
	 */
	@Parameter(property = "extensions.skip", defaultValue = "false")
	protected boolean skip;

	@Parameter(defaultValue = "true")
	protected boolean includeVersion;

	@Parameter(defaultValue = "true")
	protected boolean processSnapshotVersions;

	/**
	 * Download transitively, retrieving the specified artifact and all of its
	 * dependencies.
	 */
	@Parameter(property = "extensions.transitive", defaultValue = "true")
	protected boolean transitive = true;

	/**
	 * Download transitively, retrieving the specified artifact and all of its
	 * dependencies.
	 */
	@Parameter(property = "extensions.useRemoteRepositories", defaultValue = "true")
	protected boolean useRemoteRepositories = true;

	/**
	 * Update policy.
	 */
	@Parameter(property = "extensions.updatePolicy")
	private String updatePolicy;

	/**
	 * Update policy.
	 */
	@Parameter(property = "extensions.checksumPolicy")
	private String checksumPolicy;

	@Parameter(defaultValue = "true")
	protected boolean processExtensionVersions;

	protected Set<String> artifactsDone = new HashSet<>();

	private void handleResult(ArtifactResult result)
			throws MojoExecutionException, DependencyResolverException, ArtifactResolverException {

		Artifact artifact = result.getArtifact();
		String id = toCoords(artifact);
		
		if(isExclude(artifact)) {
			getLog().info(String.format("Skipping %s because it's classifier is excluded.", id));
			return;
		}
			
		if (artifactsDone.contains(id))
			return;
		else
			artifactsDone.add(id);
		try {
			doHandleResult(result);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to handle.", e);
		}
	}

	protected boolean isExclude(Artifact artifact) {
		return artifact != null && artifact.getClassifier() != null && artifact.getClassifier().length() > 0 && excludeClassifiers != null && excludeClassifiers.contains(artifact.getClassifier());
	}
	
	protected boolean isProcessedGroup(Artifact artifact) {
		if(groups == null || groups.isEmpty()) {
			return DEFAULT_GROUPS.contains(artifact.getGroupId());
		}
		else
			return groups.contains(artifact.getGroupId());
	}

	protected boolean isJarExtension(Artifact artifact) throws MojoExecutionException {
		if ("jar".equals(artifact.getType())) {
			try (JarFile jarFile = new JarFile(artifact.getFile())) {
				if (jarFile.getEntry("extension.def") != null) {
					return true;
				}
			} catch (IOException ioe) {
				throw new MojoExecutionException("Failed to test for extension jar.", ioe);
			}
		}
		return false;
	}

	protected abstract void doHandleResult(ArtifactResult result)
			throws MojoExecutionException, DependencyResolverException, ArtifactResolverException, IOException;

	protected void doCoordinate() throws MojoFailureException, MojoExecutionException, IllegalArgumentException,
			DependencyResolverException, ArtifactResolverException {
		
//		ArtifactRepositoryPolicy always = new ArtifactRepositoryPolicy(true,
//				updatePolicy == null ? ArtifactRepositoryPolicy.UPDATE_POLICY_INTERVAL : updatePolicy, 
//				checksumPolicy == null ? ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE : checksumPolicy );
		ArtifactRepositoryPolicy always = new ArtifactRepositoryPolicy(true,
				updatePolicy == null ? ArtifactRepositoryPolicy.UPDATE_POLICY_NEVER : updatePolicy, 
				checksumPolicy == null ? ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE : checksumPolicy );
		List<ArtifactRepository> repoList = new ArrayList<>();

		if (pomRemoteRepositories != null && useRemoteRepositories) {
			repoList.addAll(pomRemoteRepositories);
		}

		if (remoteRepositories != null) {
			// Use the same format as in the deploy plugin id::layout::url
			String[] repos = StringUtils.split(remoteRepositories, ",");
			for (String repo : repos) {
				repoList.add(parseRepository(repo, always));
			}
		}

		ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());

		Settings settings = session.getSettings();
		repositorySystem.injectMirror(repoList, settings.getMirrors());
		repositorySystem.injectProxy(repoList, settings.getProxies());
		repositorySystem.injectAuthentication(repoList, settings.getServers());

		buildingRequest.setRemoteRepositories(repoList);

		if (transitive) {
			getLog().debug("Resolving " + coordinate + " with transitive dependencies");
			for (ArtifactResult result : dependencyResolver.resolveDependencies(buildingRequest, coordinate, null)) {

				/*
				 * If the coordinate is for an extension zip, then we only we transitive
				 * dependencies that also have an extension zip
				 */
				if (EXTENSION_ARCHIVE.equals(coordinate.getClassifier())) {
					if(isProcessedGroup(result.getArtifact())){
						getLog().debug("Resolving " + toCoords(result.getArtifact()) + " with transitive dependencies");
						try {
							handleResult(artifactResolver.resolveArtifact(buildingRequest,
									toExtensionCoordinate(result.getArtifact())));
						} catch (ArtifactResolverException arfe) {
							getLog().debug("Failed to resolve " + result.getArtifact().getArtifactId()
									+ " as an extension, assuming it isn't one");
						}
					}
				} else {
					handleResult(result);
				}
			}
		} else {
			getLog().debug("Resolving " + coordinate);
			handleResult(artifactResolver.resolveArtifact(buildingRequest, toArtifactCoordinate(coordinate)));
		}

	}

	private String toCoords(Artifact artifact) {
		return artifact.getArtifactId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion() + (artifact.getClassifier() == null ? "" : ":" + artifact.getClassifier());
	}

	protected Path copy(Path p1, Path p2, Instant mod) throws IOException {
		getLog().info("Copying " + p1 + " to " + p2);
		Files.createDirectories(p2.getParent());
		if (Files.isSymbolicLink(p1)) {
			Files.createSymbolicLink(p2, Files.readSymbolicLink(p1));
		} else {
			try (OutputStream out = Files.newOutputStream(p2)) {
				Files.copy(p1, out);
			}
		}
		Files.setLastModifiedTime(p2, FileTime.from(mod));
		return p2;
	}

	protected String getFileName(Artifact a, boolean includeVersion, boolean includeClassifier) {
		return getFileName(a.getArtifactId(), getArtifactVersion(a, processSnapshotVersions), a.getClassifier(), a.getType(), includeVersion,
				includeClassifier);
	}

	protected String getFileName(String artifactId, String version, String classifier, String type,
			boolean includeVersion, boolean includeClassifier) {
		StringBuilder fn = new StringBuilder();
		fn.append(artifactId);
		if (includeVersion) {
			fn.append("-");
			fn.append(version);
		}
		if (includeClassifier && classifier != null && classifier.length() > 0) {
			fn.append("-");
			fn.append(classifier);
		}
		fn.append(".");
		fn.append(type);
		return fn.toString();
	}

	protected Path checkDir(Path resolve) {
		if (!Files.exists(resolve)) {
			try {
				Files.createDirectories(resolve);
			} catch (IOException e) {
				throw new IllegalStateException(String.format("Failed to create %s.", resolve));
			}
		}
		return resolve;
	}

	protected ArtifactRepository parseRepository(String repo, ArtifactRepositoryPolicy policy)
			throws MojoFailureException {
		// if it's a simple url
		String id = "temp";
		ArtifactRepositoryLayout layout = getLayout("default");
		String url = repo;

		// if it's an extended repo URL of the form id::layout::url
		if (repo.contains("::")) {
			Matcher matcher = ALT_REPO_SYNTAX_PATTERN.matcher(repo);
			if (!matcher.matches()) {
				throw new MojoFailureException(repo, "Invalid syntax for repository: " + repo,
						"Invalid syntax for repository. Use \"id::layout::url\" or \"URL\".");
			}

			id = matcher.group(1).trim();
			if (!StringUtils.isEmpty(matcher.group(2))) {
				layout = getLayout(matcher.group(2).trim());
			}
			url = matcher.group(3).trim();
		}
		return new MavenArtifactRepository(id, url, layout, policy, policy);
	}

	protected ArtifactCoordinate toExtensionCoordinate(Artifact art) {
		ArtifactHandler artifactHandler = artifactHandlerManager.getArtifactHandler("zip");
		DefaultArtifactCoordinate artifactCoordinate = new DefaultArtifactCoordinate();
		artifactCoordinate.setGroupId(art.getGroupId());
		artifactCoordinate.setArtifactId(art.getArtifactId());
		artifactCoordinate.setVersion(art.getVersion());
		artifactCoordinate.setClassifier(EXTENSION_ARCHIVE);
		artifactCoordinate.setExtension(artifactHandler.getExtension());
		return artifactCoordinate;
	}

	protected ArtifactCoordinate toArtifactCoordinate(DependableCoordinate dependableCoordinate) {
		ArtifactHandler artifactHandler = artifactHandlerManager.getArtifactHandler(dependableCoordinate.getType());
		DefaultArtifactCoordinate artifactCoordinate = new DefaultArtifactCoordinate();
		artifactCoordinate.setGroupId(dependableCoordinate.getGroupId());
		artifactCoordinate.setArtifactId(dependableCoordinate.getArtifactId());
		artifactCoordinate.setVersion(dependableCoordinate.getVersion());
		artifactCoordinate.setClassifier(dependableCoordinate.getClassifier());
		artifactCoordinate.setExtension(artifactHandler.getExtension());
		return artifactCoordinate;
	}

	protected ArtifactRepositoryLayout getLayout(String id) throws MojoFailureException {
		ArtifactRepositoryLayout layout = repositoryLayouts.get(id);

		if (layout == null) {
			throw new MojoFailureException(id, "Invalid repository layout", "Invalid repository layout: " + id);
		}

		return layout;
	}

	protected Path processVersionsInExtensionArchives(Artifact artifact, Path extensionZip) throws IOException {
		if(processExtensionVersions && "extension-archive".equals(artifact.getClassifier()) && "zip".equals(artifact.getType())) {
			Path tmpDir = Files.createTempDirectory("ext");
			try {
				unzip(extensionZip, tmpDir);
				AtomicInteger counter = new AtomicInteger();
				Files.find(tmpDir, Integer.MAX_VALUE, (filePath, fileAttr) -> fileAttr.isRegularFile()
						&& filePath.getFileName().toString().toLowerCase().endsWith(".jar")).forEach((file) -> {
							/* We have something that is possibly an extension jar. 
							 * So we peek inside, and see if there is an extension.def
							 * resource. 
							 */
							getLog().debug(String.format("Checking if %s is an extension.", file));
							try {
								Path jarTmpDir = Files.createTempDirectory("jar");
								try {
									unzip(file, jarTmpDir);
									
									/* Is there a manifest? */
									Path jarManifest = jarTmpDir.resolve("META-INF").resolve("MANIFEST.MF");
									if(Files.exists(jarManifest)) {
										/* There is a MANIFEST.MF, is it a hypersocket extension? */
										Manifest mf;
										try(InputStream in = Files.newInputStream(jarManifest)) {
											mf = new Manifest(in);
										}
										String jarExtensionVersion = mf.getMainAttributes().getValue("X-Extension-Version");
										if(jarExtensionVersion == null) {
											/* This is not a hypersocket extension, skip */
											getLog().debug(String.format("Not an extension, %s has no X-Extension-Version MANIFEST.MF entry.", file));
											return;
										}
										
										/* This is an extension, inject the build number */
										String newJarExtensionVersion = getVersion(true, jarExtensionVersion);
										mf.getMainAttributes().putValue("X-Extension-Version", newJarExtensionVersion);
										getLog().info(String.format("Adjusted version in %s from %s to %s.", file, jarExtensionVersion, newJarExtensionVersion));
										
										/* Rewrite the manifest */
										try(OutputStream out = Files.newOutputStream(jarManifest)) {
											mf.write(out);
										}
										
										/* Look for Maven metadata to update */
										Path maven = jarTmpDir.resolve("META-INF").resolve("maven");
										Files.find(maven, Integer.MAX_VALUE, (filePath, fileAttr) -> filePath.getFileName().toString().equals("pom.xml") || filePath.getFileName().toString().equals("pom.properties")).forEach((mavenFile) -> {
											if(mavenFile.getFileName().toString().equals("pom.xml")) {
									    		DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory.newInstance();
									    		try {
										            DocumentBuilder docBuilder = docBuilderFactory.newDocumentBuilder();
										            Document doc;
													try(InputStream docIn = Files.newInputStream(mavenFile)) {
											            doc = docBuilder.parse (docIn);
													}
										            doc.getDocumentElement().getElementsByTagName("version").item(0).setTextContent(newJarExtensionVersion);
										            
										            TransformerFactory transformerFactory = TransformerFactory.newInstance();
										            Transformer transformer = transformerFactory.newTransformer();
										            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
										            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
										            DOMSource source = new DOMSource(doc);
										            try(Writer writer = Files.newBufferedWriter(mavenFile)) {
										            	StreamResult result = new StreamResult(writer);
										            	transformer.transform(source, result);
										            }
									    		}
												catch(Exception ioe) {
													throw new IllegalStateException("Failed to rewrite pom.properties");
												}
									            
											}
											else if(mavenFile.getFileName().toString().equals("pom.properties")) {
												Properties properties = new Properties();
												try(InputStream propertiesIn = Files.newInputStream(mavenFile)) {
													properties.load(propertiesIn);
												}
												catch(IOException ioe) {
													throw new IllegalStateException("Failed to rewrite pom.properties");
												}
												properties.put("version", newJarExtensionVersion);
												try(OutputStream propertiesOut= Files.newOutputStream(mavenFile)) {
													properties.store(propertiesOut, "Processed by logonbox-plugin-generator");
												}
												catch(IOException ioe) {
													throw new IllegalStateException("Failed to rewrite pom.properties");
												}
											}
										});
										
										/* Re-pack the jar, also using the new version number
										 * in the filename */
										Path newFile = file.getParent().resolve(file.getFileName().toString().replace(jarExtensionVersion, newJarExtensionVersion));
										getLog().debug(String.format("New file is %s.", newFile));
										zip(newFile, jarTmpDir);
										
										/* Delete the old file */
										if(!newFile.equals(file)) {
											Files.delete(file);
										}
										
										
										counter.incrementAndGet();
									}
									else {
										/* There is not, skip this one */
										getLog().debug(String.format("Not an extension, %s has no MANIFEST.MF.", file));
										return;
									}
								}
								finally {
									FileUtils.deleteDirectory(jarTmpDir.toFile());
								}				
							}
							catch(IOException ioe) {
								throw new IllegalStateException("Failed to process extension archive jars.", ioe);
							}
						});
				
				/* Re-pack the extension zip */
				if(counter.get() > 0) {
					zip(extensionZip, tmpDir);
				}
				else
					getLog().debug(String.format("No jars processed in %s", extensionZip));
			}
			finally {
				FileUtils.deleteDirectory(tmpDir.toFile());
			}
		}
		return extensionZip;
	}

	/**
	 * @return {@link #skip}
	 */
	protected boolean isSkip() {
		return skip;
	}

	
	public void zip(Path zipFile, Path directoryToZip) throws IOException {
		getLog().debug(String.format("Zipping %s from %s", zipFile, directoryToZip));
        try(ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
			Files.find(directoryToZip, Integer.MAX_VALUE, (filePath, fileAttr) -> true).forEach((file) -> {
				Path relpath = directoryToZip.relativize(file);
				try {
					if(Files.isDirectory(file)) {
		                zos.putNextEntry(new ZipEntry(relpath.toString() + "/"));
		                zos.closeEntry();
					}
					else {
						try(InputStream in = Files.newInputStream(file)) {
							ZipEntry entry = new ZipEntry(relpath.toString());
					        zos.putNextEntry(entry);
					        byte[] bytes = new byte[1024];
					        int length;
					        while ((length = in.read(bytes)) >= 0) {
					            zos.write(bytes, 0, length);
					        }
						}
					}
				}
				catch(IOException ioe) {
					throw new IllegalStateException("Failed to re-zip adjusted extension archive.");
				}
			});
        }
    }
	
	public void unzip(Path zip, Path destDir) throws IOException {
		getLog().debug(String.format("Unzipping %s to %s", zip, destDir));
		byte[] buffer = new byte[1024];
		try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
			ZipEntry zipEntry = zis.getNextEntry();
			while (zipEntry != null) {
				Path newFile = newFile(destDir, zipEntry);
				if(getLog().isDebugEnabled())
					getLog().debug(String.format("  Entry: %s (%s)", zipEntry.getName(), zipEntry.isDirectory() ? "Dir" : "File"));
				if (zipEntry.isDirectory()) {
					if (!Files.isDirectory(newFile)) {
						Files.createDirectories(newFile);
					}
				} else {
					Path parent = newFile.getParent();
					if (!Files.isDirectory(parent)) {
						if(getLog().isDebugEnabled())
							getLog().debug(String.format("  Need to create parent: %s (ex: %s dir: %s, file: %s)", parent, Files.exists(parent), Files.isDirectory(parent), Files.isRegularFile(parent)));  
						Files.createDirectories(parent);
					}

					// write file content
					OutputStream fos = Files.newOutputStream(newFile);
					int len;
					while ((len = zis.read(buffer)) > 0) {
						fos.write(buffer, 0, len);
					}
					fos.close();
				}
				zipEntry = zis.getNextEntry();
			}
			zis.closeEntry();
		}
	}
	
	public static Path newFile(Path destinationDir, ZipEntry zipEntry) throws IOException {
		Path destFile = destinationDir.resolve(zipEntry.getName());

	    String destDirPath = destinationDir.toFile().getCanonicalPath();
	    String destFilePath = destFile.toFile().getCanonicalPath();

	    if (!destFilePath.startsWith(destDirPath + File.separator)) {
	        throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
	    }

	    return destFile;
	}
}
