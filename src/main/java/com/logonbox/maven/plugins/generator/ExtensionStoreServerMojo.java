package com.logonbox.maven.plugins.generator;

import static com.logonbox.maven.plugins.generator.Plugins.getBestProperty;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FilenameUtils;
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

import com.sshtools.uhttpd.UHTTPD;
import com.sshtools.uhttpd.UHTTPD.RootContext;
import com.sshtools.uhttpd.UHTTPD.Transaction;

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

	@Parameter(defaultValue = "", property = "extension-store.phase")
	private String phaseName = "";

	private RootContext server;
	private Map<String, Extension> map = new HashMap<>();
	private String actualPhaseName;

	protected void onExecute() throws MojoExecutionException, MojoFailureException {
		try {

			Set<Artifact> artifacts = project.getArtifacts();
			String firstVersion = null;
			for (Artifact artifact : artifacts) {
				if (isProcessedGroup(artifact) && isJarExtension(artifact)) {
					coordinate.setGroupId(artifact.getGroupId());
					coordinate.setArtifactId(artifact.getArtifactId());
					coordinate.setVersion(artifact.getVersion());
					coordinate.setType("zip");
					coordinate.setClassifier(EXTENSION_ARCHIVE);

					try {
						doCoordinate();
						firstVersion = artifact.getVersion();
					} catch (MojoFailureException | DependencyResolverException | ArtifactResolverException e) {
						getLog().debug("Failed to process an artifact, assuming it's not an extension.", e);
					}
				} else
					getLog().debug(artifact.getId() + " is not an extension");
			}

			/* Calculate a phase name */
			if (phaseName.equals("") || !phaseName.contains("_")) {
				if (firstVersion == null) {
					getLog().warn("No extensions being served, cannot determine a phase to use.");
				} else {
					String[] parts = firstVersion.split("\\.");
					if (phaseName.equals("")) {
						actualPhaseName = "nightly" + parts[0] + "_" + parts[1] + "x";
					} else {
						actualPhaseName += "_" + parts[0] + "_" + parts[1] + "x";
					}
				}
			} else {
				actualPhaseName = phaseName;
			}

			server = UHTTPD.server().
					withHttp(port).
					withoutHttps().
					get(".*/api/store/private", this::handlePrivate).
					get(".*/api/store/phases", this::handlePrivate).
					get(".*/api/store/repos2/.*", this::store).
					get(".*", tx -> {
						if (map.containsKey(tx.uri())) {
							tx.response("application/zip", map.get(tx.uri()).artifact.getFile());
						} else {
							throw new FileNotFoundException(
									"This extension store only serves the version of the project it is run from, "
											+ project.getVersion() + ".");
						}
					}).
					build();
			
			getLog().info("Starting extension store server, press Ctrl+C to stop.");
			getLog().info("Service phase: " + actualPhaseName);
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

	void store(Transaction tx) throws IOException {

		var parts = tx.uri().substring(appPath.length() + 18).split("/");
		var version = parts[0];
		var target = parts[3].split(",")[0];
		

		getLog().debug(String.format("Getting version: %s", version));
		JsonArrayBuilder resources = Json.createArrayBuilder();
		for (Map.Entry<String, Extension> en : map.entrySet()) {
			JsonObjectBuilder resource = Json.createObjectBuilder();
			Extension extension = en.getValue();
			Artifact artifact = extension.artifact;
			String artifactVersion = getArtifactVersion(artifact);
			String unprocessedArtifactVersion = artifact.getVersion();
			getLog().debug(String.format("Comparing versions: %s and %s (%s)", version, artifactVersion,
					unprocessedArtifactVersion));
//			if (version.equals(artifactVersion) || version.equals(unprocessedArtifactVersion)) {

			File file = artifact.getFile();
			var pluginProperties = new Properties();
			try {
				pluginProperties.putAll(Plugins.getPluginProperties(file.toPath()));

			} catch (Exception e) {
			}
			var extensionProperties = new Properties();
			try {
				Plugins.getExtensionProperties(file.toPath()).forEach((k, v) -> {
					extensionProperties.putIfAbsent(k, v);
				});
			} catch (Exception e2) {
			}
			var mavenProperties = new Properties();
			try {
				Plugins.getDefaultMavenManifestProperties(file.toPath()).forEach((k, v) -> {
					mavenProperties.putIfAbsent(k, v);
				});
			} catch (Exception e) {
			}

			var defaultProperties = new Properties();
			defaultProperties.put("id", artifact.getArtifactId());

			if(getLog().isDebugEnabled()) {
				getLog().info("Properties from " + file + " are ");
				pluginProperties.forEach((k, v) -> {
					getLog().info("   " + k + " = " + v);
				});
				extensionProperties.forEach((k, v) -> {
					getLog().info("   " + k + " = " + v);
				});
				mavenProperties.forEach((k, v) -> {
					getLog().info("   " + k + " = " + v);
				});
				defaultProperties.forEach((k, v) -> {
					getLog().info("   " + k + " = " + v);
				});
			}
			
			var plist = Arrays.asList(pluginProperties, extensionProperties, mavenProperties, defaultProperties);
			var name = getBestProperty(artifact.getGroupId() + ":" + artifact.getArtifactId(), plist, "x.plugin.name", "extension.name", "name");
			name = name.replaceFirst("^Hypersocket - ", "");
			name = name.replaceFirst("^LogonBox - ", "");
			name = name.replaceFirst("^SSHTools - ", "");
			name = name.replaceFirst("^Nervepoint - ", "");

			resource.add("size", file.length());
			resource.add("url", en.getKey());
			resource.add("filename", FilenameUtils.getName(en.getKey()));
			resource.add("repositoryDescription", description);
			resource.add("modifiedDate", file.lastModified());
			resource.add("version", getBestProperty(artifactVersion, plist, "plugin.version", "extension.version", "version"));
			resource.add("hash", extension.hash);
			resource.add("extensionId",
					getBestProperty(artifact.getArtifactId(), plist, "plugin.id", "extension.id", "artifactId"));
			resource.add("state", "NOT_INSTALLED");
			resource.add("target", extensionTarget.equals("") ? target : extensionTarget);
			resource.add("mandatory", getBestProperty("false", plist, "x.plugin.mandatory", "extension.mandatory"));
			resource.add("weight", 0);
			resource.add("tab", tab);
			resource.add("type", pluginProperties.isEmpty() ? "EXTENSION" : "PLUGIN");
			resource.add("extensionName", name);
			resource.add("description",
					getBestProperty(name, plist, "plugin.description", "extension.description", "description"));
			var dep = getBestProperty("", plist, "plugin.dependencies", "extension.depends");
			resource.add("dependsOn", dep.equals("") ? Json.createArrayBuilder()
					: Json.createArrayBuilder(Arrays.asList(dep.split(","))));

			resources.add(resource);
//			}

		}

		/* Resource response */
		tx.response("text/json", resourcesResponse(resources));
	}

	Object resourcesResponse(JsonArrayBuilder resources) throws UnsupportedEncodingException {
		return Json.createObjectBuilder().add("success", true).add("confirmation", false).add("message", "")
				.add("properties", Json.createObjectBuilder()).add("resources", resources).build();
	}

	void handlePrivate(Transaction tx) throws IOException {
		tx.response("text/json", resourcesResponse(Json.createArrayBuilder().add(Json.createObjectBuilder().add("version", getArtifactVersion(project.getArtifact()))
				.add("publicPhase", true).add("name", actualPhaseName))));
	}
}