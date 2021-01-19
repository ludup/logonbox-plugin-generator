package com.logonbox.maven.plugins.generator;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
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

/**
 * Resolves and downloads all of a projects extensions and place the resulting
 * zips in a specific diretory. Based on {@link GetMojo}.
 */
public abstract class AbstractExtensionsMojo extends AbstractMojo {
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
	@Parameter(property = "remoteRepositories")
	protected String remoteRepositories;

	/**
	 *
	 */
	@Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true, required = true)
	protected List<ArtifactRepository> pomRemoteRepositories;

	/**
	 * Location of the file.
	 */
	@Parameter(defaultValue = "${project.build.directory}/artifacts", property = "output", required = true)
	protected File output;

	/**
	 * Skip plugin execution completely.
	 *
	 * @since 2.7
	 */
	@Parameter(property = "mdep.skip", defaultValue = "false")
	protected boolean skip;

	@Parameter(defaultValue = "true")
	protected boolean includeVersion;

	/**
	 * Download transitively, retrieving the specified artifact and all of its
	 * dependencies.
	 */
	@Parameter(property = "transitive", defaultValue = "true")
	protected boolean transitive = true;

	protected abstract void handleResult(ArtifactResult result)
			throws MojoExecutionException, DependencyResolverException, ArtifactResolverException;

	protected void doCoordinate() throws MojoFailureException, MojoExecutionException, IllegalArgumentException,
			DependencyResolverException, ArtifactResolverException {
		ArtifactRepositoryPolicy always = new ArtifactRepositoryPolicy(true,
				ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS, ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN);

		List<ArtifactRepository> repoList = new ArrayList<>();

		if (pomRemoteRepositories != null) {
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
				if ("extension-archive".equals(coordinate.getClassifier())) {
					getLog().debug("Resolving " + toCoords(result.getArtifact()) + " with transitive dependencies");
					try {
						handleResult(artifactResolver.resolveArtifact(buildingRequest,
								toExtensionCoordinate(result.getArtifact())));
					} catch (ArtifactResolverException arfe) {
						getLog().debug("Failed to resolve " + result.getArtifact().getArtifactId()
								+ " as an extension, assuming it isn't one");
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
		return artifact.getArtifactId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
	}

	protected void copy(Path p1, Path p2, Instant mod) throws IOException {
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
	}

	protected String getFileName(Artifact a, boolean includeVersion, boolean includeClassifier) {
		return getFileName(a.getArtifactId(), a.getVersion(), a.getClassifier(), a.getType(), includeVersion, includeClassifier);
	}

	protected String getFileName(String artifactId, String version, String classifier, String type, boolean includeVersion, boolean includeClassifier) {
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
		artifactCoordinate.setClassifier("extension-archive");
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

	/**
	 * @return {@link #skip}
	 */
	protected boolean isSkip() {
		return skip;
	}

}
