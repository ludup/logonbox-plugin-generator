package com.logonbox.maven.plugins.generator;

import java.io.File;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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

import com.sshtools.common.util.FileUtils;

import edu.emory.mathcs.backport.java.util.concurrent.atomic.AtomicInteger;

/**
 * Resolves and downloads all of a projects extensions and place the resulting
 * zips in a specific diretory. Based on {@link GetMojo}.
 */
public abstract class AbstractExtensionsMojo extends AbstractBaseExtensionsMojo {

	public interface IORunnable {
		void run() throws IOException;
	}

	protected static final String EXTENSION_ARCHIVE = "extension-archive";

	private static final List<String> DEFAULT_GROUPS = Arrays.asList("com.hypersocket", "com.logonbox",
			"com.nervepoint", "com.sshtools", "com.jadaptive");
	private static final Pattern ALT_REPO_SYNTAX_PATTERN = Pattern.compile("(.+)::(.*)::(.+)");

	@Parameter(defaultValue = "${session}", required = true, readonly = true)
	protected MavenSession session;

	static Map<Artifact, Path> lastVersionProcessed = new HashMap<>();

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
	@Parameter(property = "extensions.copyOncePerRuntime", defaultValue = "true")
	protected boolean copyOncePerRuntime = true;
	/**
	 * Which groups can contain extensions. This can massively speed up dependency
	 * by not needlessly contacting a Maven repository to determine if an artifact
	 * has a extension archive artifact as well (which it tries to do for ALL
	 * dependencies including 3rd party ones that will never has an extension
	 * archive). This provides a way to optimise this, as we only have a few group
	 * names that have extensions.
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

		if (isExclude(artifact)) {
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
		return artifact != null && artifact.getClassifier() != null && artifact.getClassifier().length() > 0
				&& excludeClassifiers != null && excludeClassifiers.contains(artifact.getClassifier());
	}

	protected boolean isProcessedGroup(Artifact artifact) {
		if (groups == null || groups.isEmpty()) {
			return DEFAULT_GROUPS.contains(artifact.getGroupId());
		} else
			return groups.contains(artifact.getGroupId());
	}

	protected abstract void doHandleResult(ArtifactResult result)
			throws MojoExecutionException, DependencyResolverException, ArtifactResolverException, IOException;

	protected void doCoordinate() throws MojoFailureException, MojoExecutionException, IllegalArgumentException,
			DependencyResolverException, ArtifactResolverException {

		ArtifactRepositoryPolicy always = new ArtifactRepositoryPolicy(true,
				updatePolicy == null ? ArtifactRepositoryPolicy.UPDATE_POLICY_INTERVAL + ":60" : updatePolicy, 
				checksumPolicy == null ? ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE : checksumPolicy );
//		ArtifactRepositoryPolicy always = new ArtifactRepositoryPolicy(true,
//				updatePolicy == null ? ArtifactRepositoryPolicy.UPDATE_POLICY_NEVER : updatePolicy,
//				checksumPolicy == null ? ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE : checksumPolicy);
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
					if (isProcessedGroup(result.getArtifact())) {
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
		return artifact.getArtifactId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion()
				+ (artifact.getClassifier() == null ? "" : ":" + artifact.getClassifier());
	}

	protected String getFileName(Artifact a, boolean includeVersion, boolean includeClassifier) {
		return getFileName(a.getArtifactId(), getArtifactVersion(a, processSnapshotVersions), a.getClassifier(),
				a.getType(), includeVersion, includeClassifier);
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

	protected boolean matches(Artifact a, String key) {
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

	protected ArtifactRepositoryLayout getLayout(String id) throws MojoFailureException {
		ArtifactRepositoryLayout layout = repositoryLayouts.get(id);

		if (layout == null) {
			throw new MojoFailureException(id, "Invalid repository layout", "Invalid repository layout: " + id);
		}

		return layout;
	}

	protected void runIfNeedVersionProcessedArchive(Artifact artifact, Path target, IORunnable r, FileTime sourceTime)
			throws IOException {
		if (copyOncePerRuntime) {
			var other = lastVersionProcessed.get(artifact);
			if (other == null) {
				lastVersionProcessed.put(artifact, target);
				r.run();
			} else {
				getLog().debug(String.format(
						"Skipping processing of %s, we have already processed it once this runtime, just linking from %s to %s.",
						artifact, other, target));
				Files.deleteIfExists(target);
				Files.createSymbolicLink(target, other);
			}
			Files.setLastModifiedTime(target, FileTime.from(sourceTime.toInstant()));
		} else {
			r.run();
			Files.setLastModifiedTime(target, FileTime.from(sourceTime.toInstant()));
		}
	}

	protected void processVersionsInExtensionArchives(Artifact artifact, ZipInputStream zis, ZipOutputStream zos)
			throws IOException {
		if (processExtensionVersions && "extension-archive".equals(artifact.getClassifier())
				&& "zip".equals(artifact.getType())) {
			AtomicInteger counter = new AtomicInteger();
			ZipEntry zipEntry = zis.getNextEntry();
			while (zipEntry != null) {
				getLog().debug(String.format("  Zip entry: %s (dir: %s)", zipEntry.getName(), zipEntry.isDirectory()));
				if (zipEntry.isDirectory()) {
					zos.putNextEntry(new ZipEntry(zipEntry.getName()));
					zos.closeEntry();
				} else if (zipEntry.getName().toLowerCase().endsWith(".jar") && isPotentialExtensions(FileUtils.getFilename(zipEntry.getName())) ) {
					zos.putNextEntry(new ZipEntry(zipEntry.getName()));
					try (var in = new ZipInputStream(new FilterInputStream(zis) {
						@Override
						public void close() throws IOException {
						}
					}) ; var out = new ZipOutputStream(new FilterOutputStream(zos) {
						@Override
						public void close() throws IOException {
						}
					}) ) {
						getLog().debug("    Process versions in inner jar artifact of " + artifact.getArtifactId() + " from " + zipEntry.getName());
						processVersionsInJarFile(artifact, counter, in, out);
						getLog().debug("    Processed versions in inner jar artifact of " + artifact.getArtifactId() + " from " + zipEntry.getName());
					}
					zos.closeEntry();
				} else {
					zos.putNextEntry(new ZipEntry(zipEntry.getName()));
					zis.transferTo(zos);
					zos.closeEntry();
					getLog().debug(String.format("    Copied %s.", zipEntry.getName()));
				}

				zipEntry = zis.getNextEntry();
			}
			zos.flush();
			zis.closeEntry();

			/* Re-pack the extension zip */
			if (counter.get() == 0)
				getLog().debug(String.format("No jars processed in %s", artifact));
		}
	}

	protected boolean isPotentialExtensions(String name) {
		if(name.startsWith("com.logonbox-") ||
		   name.startsWith("com.hypersocket-") ||
		   name.startsWith("com.sshtools-") ||
		   name.startsWith("com.nervepoint-"))
			return true;
		else
			return false;
	}

	public void processVersionsInJarFile(Artifact artifact, AtomicInteger counter, ZipInputStream zis,
			ZipOutputStream zos) {
		/*
		 * We have something that is possibly an extension jar. So we peek inside, and
		 * see if there is an extension.def resource.
		 */
		try {
			String jarExtensionVersion = null;
			String newJarExtensionVersion = null;
			ZipEntry zipEntry = zis.getNextEntry();
			while (zipEntry != null) {
				getLog().debug(String.format("  Jar entry: %s (dir: %s)", zipEntry.getName(), zipEntry.isDirectory()));
				if (zipEntry.isDirectory()) {
					zos.putNextEntry(new ZipEntry(zipEntry.getName()));
					zos.closeEntry();
				} else if (zipEntry.getName().equals("META-INF/MANIFEST.MF")) {
					/* There is a MANIFEST.MF, is it a hypersocket extension? */
					Manifest mf = new Manifest(zis);
					jarExtensionVersion = mf.getMainAttributes().getValue("X-Extension-Version");
					if (jarExtensionVersion != null) {

						/* This is an extension, inject the build number */
						newJarExtensionVersion = getVersion(true, jarExtensionVersion);
						mf.getMainAttributes().putValue("X-Extension-Version", newJarExtensionVersion);
						getLog().debug(String.format("    Adjusted version in %s from %s to %s.", zipEntry.getName(),
								jarExtensionVersion, newJarExtensionVersion));

					}
					/* Rewrite the manifest */
					zos.putNextEntry(new ZipEntry(zipEntry.getName()));
					mf.write(zos);
					zos.closeEntry();
				} else if (newJarExtensionVersion != null && zipEntry.getName().equals("plugin.properties")) {

					/* Look for extension properties to update */
					Properties pluginProperties = new Properties();
					pluginProperties.load(zis);
					pluginProperties.put("plugin.version", newJarExtensionVersion);
					getLog().debug(String.format("    Adjusted version in %s from %s to %s.", zipEntry.getName(),
							jarExtensionVersion, newJarExtensionVersion));

					zos.putNextEntry(new ZipEntry(zipEntry.getName()));
					pluginProperties.store(zos, "Plugin Properties for " + artifact.getArtifactId());
					zos.closeEntry();
				} else if (newJarExtensionVersion != null && zipEntry.getName().equals("extension.def")) {

					/* Look for extension def to update */
					Properties extProperties = new Properties();
					extProperties.load(zis);
					extProperties.put("extension.version", newJarExtensionVersion);
					getLog().debug(String.format("    Adjusted version in %s from %s to %s.", zipEntry.getName(),
							jarExtensionVersion, newJarExtensionVersion));

					zos.putNextEntry(new ZipEntry(zipEntry.getName()));
					extProperties.store(zos, "Extension Properties for " + artifact.getArtifactId());
					zos.closeEntry();
				} else if (newJarExtensionVersion != null
						&& zipEntry.getName().matches("META-INF/maven/.*/pom\\.xml")) {
					DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory.newInstance();
					try {
						DocumentBuilder docBuilder = docBuilderFactory.newDocumentBuilder();
						Document doc = docBuilder.parse(new FilterInputStream(zis) {
							@Override
							public void close() throws IOException {
								// DocumentBuilder.parse() is dumb, it closes the stream passed it
							}
						});
						doc.getDocumentElement().getElementsByTagName("version").item(0)
								.setTextContent(newJarExtensionVersion);

						TransformerFactory transformerFactory = TransformerFactory.newInstance();
						Transformer transformer = transformerFactory.newTransformer();
						transformer.setOutputProperty(OutputKeys.INDENT, "yes");
						transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
						DOMSource source = new DOMSource(doc);
						zos.putNextEntry(new ZipEntry(zipEntry.getName()));
						getLog().debug(String.format("    Adjusted version in %s from %s to %s.", zipEntry.getName(),
								jarExtensionVersion, newJarExtensionVersion));
						StreamResult result = new StreamResult(zos);
						transformer.transform(source, result);
						zos.closeEntry();
					} catch (Exception ioe) {
						throw new IllegalStateException("Failed to rewrite pom.properties");
					}
				} else if (newJarExtensionVersion != null
						&& zipEntry.getName().matches("META-INF/maven/.*/pom\\.properties")) {
					Properties properties = new Properties();
					properties.load(zis);

					getLog().debug(String.format("    Adjusted version in %s from %s to %s.", zipEntry.getName(),
							jarExtensionVersion, newJarExtensionVersion));
					properties.put("version", newJarExtensionVersion);
					zos.putNextEntry(new ZipEntry(zipEntry.getName()));
					properties.store(zos, "Processed by logonbox-plugin-generator");
					zos.closeEntry();
				} else {
					zos.putNextEntry(new ZipEntry(zipEntry.getName()));
					zis.transferTo(zos);
					zos.closeEntry();
					getLog().debug(String.format("    Copied %s.", zipEntry.getName()));
				}

				zipEntry = zis.getNextEntry();
			}
			zis.closeEntry();
			zos.flush();

			if (newJarExtensionVersion == null) {
				/* There is not, skip this one */
				getLog().debug(String.format("Not an extension, %s has no MANIFEST.MF.", artifact));
			}
			return;
		} catch (IOException ioe) {
			throw new IllegalStateException("Failed to process extension archive jars.", ioe);
		}
	}

	/**
	 * @return {@link #skip}
	 */
	protected boolean isSkip() {
		return skip;
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
