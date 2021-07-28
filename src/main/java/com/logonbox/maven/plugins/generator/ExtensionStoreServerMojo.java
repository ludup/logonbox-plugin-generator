package com.logonbox.maven.plugins.generator;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResult;
import org.apache.maven.shared.transfer.dependencies.resolve.DependencyResolverException;
import org.sonatype.inject.Description;

import com.logonbox.maven.plugins.generator.MiniHttpServer.DynamicContent;
import com.logonbox.maven.plugins.generator.MiniHttpServer.DynamicContentFactory;
import com.logonbox.maven.plugins.generator.MiniHttpServer.Log;
import com.logonbox.maven.plugins.generator.MiniHttpServer.Method;

/**
 * Start a mini HTTP server that acts as an extension store, allowing local
 * testing of the extension store.
 */
@Mojo(threadSafe = true, name = "store", requiresDirectInvocation = true, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
@Description("Starts an extension store server for testing extension system")
public class ExtensionStoreServerMojo extends AbstractExtensionsMojo {

	class Extension {
		Artifact artifact;
		String hash;

		Extension(Artifact artifact, String hash) {
			this.artifact = artifact;
			this.hash = hash;
		}
	}

	/**
	 * The maven project.
	 */
	@Parameter(required = true, readonly = true, property = "project")
	protected MavenProject project;

	@Parameter(defaultValue = "8081", property = "extension-store.port")
	private int port;

	@Parameter(defaultValue = "Generated Developers Extension Store", property = "extension-store.description")
	private String description;

	/*
	 * The extension target. Matches 'ExtensionTarget' in Hypersocket main source
	 * tree.
	 */
	@Parameter(defaultValue = "", property = "extension-store.target")
	private String extensionTarget = "";

	@Parameter(defaultValue = "Developer", property = "extension-store.tab")
	private String tab = "Developer";

	@Parameter(defaultValue = "/app", property = "extension-store.app-path")
	private String appPath = "/app";

	private MiniHttpServer server;
	private Map<String, Extension> map = new HashMap<>();

	protected void onExecute() throws MojoExecutionException, MojoFailureException {
		try {
			
			Set<Artifact> artifacts = project.getArtifacts();
			for (Artifact artifact : artifacts) {
				if (isProcessedGroup(artifact) && isJarExtension(artifact)) {
					coordinate.setGroupId(artifact.getGroupId());
					coordinate.setArtifactId(artifact.getArtifactId());
					coordinate.setVersion(artifact.getVersion());
					coordinate.setType("zip");
					coordinate.setClassifier(EXTENSION_ARCHIVE);

					try {
						doCoordinate();
					} catch (MojoFailureException | DependencyResolverException | ArtifactResolverException e) {
						getLog().debug("Failed to process an artifact, assuming it's not an extension.", e);
					}
				}
				else
					getLog().debug(artifact.getId() + " is not an extension");
			}

			server = new MiniHttpServer(port, -1, null);
			MiniHttpServer.setLOG(new Log() {

				@Override
				public void info(String message) {
					getLog().info(message);					
				}

				@Override
				public void error(String message) {
					getLog().error(message);
				}

				@Override
				public void error(String message, Throwable exception) {
					getLog().error(message, exception);					
				}

				@Override
				public void debug(String message) {
					getLog().debug(message);					
				}
			});
			server.addContent(new DynamicContentFactory() {
				@Override
				public DynamicContent get(Method method, String path, Map<String, List<String>> headers, InputStream in)
						throws IOException {
					String query = null;
					int idx = path.indexOf('?');
					if (idx != -1) {
						query = path.substring(idx + 1);
						path = path.substring(0, idx);
					}
					getLog().debug(String.format("Request for: %s", path));
					if (path.startsWith(appPath + "/api/store/private") || path.startsWith(appPath + "/api/store/phases")) {
						return handlePrivate();
					} else if (path.startsWith(appPath + "/api/store/repos2/")) {
						String[] parts = path.substring(appPath.length() + 18).split("/");
						return store(parts[0], parts[3].split(",")[0]);
					} else if (map.containsKey(path)) {
						File file = map.get(path).artifact.getFile();
						return new DynamicContent("application/zip", new FileInputStream(file));
					} else {
						throw new FileNotFoundException(
								"This extension store only serves the version of the project it is run from, "
										+ project.getVersion() + ".");
					}

				}
			});
			getLog().info("Starting extension store server, press Ctrl+C to stop.");
			server.run();
		} catch (IOException ioe) {
			throw new MojoExecutionException("Failed to start extension store server.", ioe);
		}
	}

	@Override
	protected void doHandleResult(ArtifactResult result)
			throws MojoExecutionException, DependencyResolverException, ArtifactResolverException, IOException {

		Artifact artifact = result.getArtifact();

		/* Is the artifact an extension? */
		String version = getArtifactVersion(artifact);
		String key = "/" + version + "/" + artifact.getArtifactId() + "/" + artifact.getArtifactId() + "-" + version
				+ ".zip";
		getLog().info("Mapping " + artifact + " to " + key);

		try (InputStream in = new FileInputStream(artifact.getFile())) {
			String hash = DigestUtils.md5Hex(in);
			map.put(key, new Extension(artifact, hash));
		}

	}

	@Override
	protected boolean isSnapshotVersionAsBuildNumber() {
		return true;
	}

	DynamicContent store(String version, String target) throws IOException {
		// private String filename;
		// private String repository;
		// private String featureGroup;

		getLog().debug(String.format("Getting version: %s", version));
		JsonArrayBuilder resources = Json.createArrayBuilder();
		for (Map.Entry<String, Extension> en : map.entrySet()) {
			JsonObjectBuilder resource = Json.createObjectBuilder();
			Extension extension = en.getValue();
			Artifact artifact = extension.artifact;
			String artifactVersion = getArtifactVersion(artifact);
			String unprocessedArtifactVersion = artifact.getVersion();
			getLog().debug(String.format("Comparing versions: %s and %s (%s)", version, artifactVersion, unprocessedArtifactVersion));
//			if (version.equals(artifactVersion) || version.equals(unprocessedArtifactVersion)) {
				File file = artifact.getFile();
				resource.add("size", file.length());
				resource.add("url", en.getKey());
				resource.add("filename", FilenameUtils.getName(en.getKey()));
				resource.add("repositoryDescription", description);
				resource.add("modifiedDate", file.lastModified());
				resource.add("version", artifactVersion);
				resource.add("hash", extension.hash);
				resource.add("extensionId", artifact.getArtifactId());
				resource.add("state", "NOT_INSTALLED");
				resource.add("target", extensionTarget.equals("") ? target : extensionTarget);
				resource.add("mandatory", false);
				resource.add("weight", 0);
				resource.add("tab", tab);
				Properties def = getExtensionDefinition(artifact);
				resource.add("description",
						def.containsKey("extension.description") ? def.getProperty("extension.description")
								: artifact.getGroupId() + ":" + artifact.getArtifactId());
				resource.add("extensionName", def.containsKey("extension.name") ? def.getProperty("extension.name")
						: artifact.getArtifactId());
				resource.add("dependsOn",
						def.containsKey("extension.depends")
								? Json.createArrayBuilder(
										Arrays.asList(def.getProperty("extension.depends").split(",")))
								: Json.createArrayBuilder());

				resources.add(resource);
//			}

		}

		/* Resource response */
		return resourcesResponse(resources);
	}

	DynamicContent resourcesResponse(JsonArrayBuilder resources) throws UnsupportedEncodingException {
		String json = Json.createObjectBuilder().add("success", true).add("confirmation", false).add("message", "")
				.add("properties", Json.createObjectBuilder()).add("resources", resources).build().toString();
		return new DynamicContent("text/json", new ByteArrayInputStream(json.getBytes("UTF-8")));
	}

	DynamicContent handlePrivate() throws UnsupportedEncodingException {
		JsonArrayBuilder resources = Json.createArrayBuilder();
		resources.add(Json.createObjectBuilder().add("version", getArtifactVersion(project.getArtifact()))
				.add("publicPhase", true).add("name", "developer"));

		return resourcesResponse(resources);
	}
	
	static Properties getExtensionDefinition(Artifact artifact) throws IOException {
		Properties p = new Properties();
		try (ZipInputStream zis = new ZipInputStream(new FileInputStream(artifact.getFile()))) {
			ZipEntry zipEntry = zis.getNextEntry();
			while (zipEntry != null) {
				if(zipEntry.getName().endsWith("/extension.def")) {
					p.load(zis);
					break;
				}
				else if(zipEntry.getName().endsWith("/")) {
					// skip
				}
				else {
					// sink
					IOUtils.skip(zis, Long.MAX_VALUE);
				}
				zipEntry = zis.getNextEntry();
			}
			zis.closeEntry();
		}
		return p;
	}
}