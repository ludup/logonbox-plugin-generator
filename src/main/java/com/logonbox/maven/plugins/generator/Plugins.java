package com.logonbox.maven.plugins.generator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;

public class Plugins {

	public static final String X_PLUGIN_NAME = "x.plugin.name";
	public static final String X_PLUGIN_WEIGHT = "x.plugin.weight";
	public static final String X_PLUGIN_MANDATORY = "x.plugin.mandatory";
	public static final String X_PLUGIN_SYSTEM = "x.plugin.system";
	public static final String X_PLUGIN_LICENSE = "x.plugin.license";
	public static final String X_PLUGIN_LICENSE_URL = "x.plugin.licenseUrl";
	public static final String X_PLUGIN_URL = "x.plugin.url";

	private Plugins() {
	}

	public static Properties getPluginProperties(Path path) {
		if (Files.isDirectory(path)) {
			try (var in = Files.newInputStream(path.resolve("plugin.properties"))) {
				return properties(in);
			} catch (IOException ioe) {
				throw new IllegalArgumentException(String.format("Could not open %s as a zip file.", path));
			}
		} else {
			try (var in = Files.newInputStream(path)) {
				return getPluginProperties(in);
			} catch (IOException ioe) {
				throw new IllegalArgumentException(String.format("Could not open %s as a zip file.", path));
			}
		}
	}

	public static Properties getPluginProperties(InputStream in) {
		ZipEntry en;
		try (var z = new ZipInputStream(in)) {
			while ((en = z.getNextEntry()) != null) {
				if (en.getName().equals("plugin.properties")) {
					return properties(z);
				}
			}
		} catch (IOException ioe) {
		}
		throw new IllegalArgumentException("Not a plugin stream.");
	}

	public static Properties getExtensionProperties(Path path) {
		try (var in = Files.newInputStream(path)) {
			return getExtensionProperties(in);
		} catch (IOException ioe) {
			throw new IllegalArgumentException(String.format("Could not open %s as a zip file.", path));
		}
	}

	public static String getExtensionVersion(Path path) {
		try (var in = Files.newInputStream(path)) {
			return getExtensionVersion(in);
		} catch (IOException ioe) {
			throw new IllegalArgumentException(String.format("Could not open %s as a zip file.", path));
		}
	}

	public static Properties getDefaultMavenManifestProperties(Path path) {
		try (var in = Files.newInputStream(path)) {
			return getDefaultMavenManifestProperties(in);
		} catch (IOException ioe) {
			throw new IllegalArgumentException(String.format("Could not open %s as a zip file.", path));
		}
	}
	
	public static String getBestProperty(String defaultValue, Collection<Properties> properties, Collection<String> keys) {
		var it = keys.iterator();
		for(var p : properties) {
			var k = it.next();
			if(p.containsKey(k))
				return p.getProperty(k);
		}
		return defaultValue;
	}
	
	public static Properties getDefaultMavenManifestProperties(InputStream in) {
		var doc = getMavenManifestInsideArchive(in);
		var p = new Properties();
		putIfNotNull(p, "artifactId", "artifactId", doc);
		putIfNotNull(p, "name", "name", doc);
		putIfNotNull(p, "description", "description", doc);
		putIfNotNull(p, "version", "version", doc);
		return p;
	}

	public static Document getMavenManifestInsideArchive(InputStream in) {
		return getMavenManifestInsideArchive(in, null);
	}
	
	private static Document getMavenManifestInsideArchive(InputStream in, String extName) {
		ZipEntry en;
		boolean hasDef = false;
		Document hasVersion = null;
		try (var z = new ZipInputStream(in)) {
			while ((en = z.getNextEntry()) != null) {
				if(extName == null) {
					extName = en.getName();
					while(extName.startsWith("/"))
						extName = extName.substring(1);
					extName = extName.split("/")[0];
				}
				if (en.getName().equals("extension.def") || en.getName().endsWith("/extension.def")) {
					hasDef = true;
				} else if (en.getName().matches(".*META-INF/maven/.*/pom\\.xml")) {
					try {
			    		var docBuilderFactory = DocumentBuilderFactory.newInstance();
			            var docBuilder = docBuilderFactory.newDocumentBuilder();
			            hasVersion = docBuilder.parse (z);
					}
					catch(Exception e) {
						e.printStackTrace();
					}
				} else if (extName != null && en.getName().matches(".*/" + extName + ".*\\.jar")) {
					try {
						return getMavenManifestInsideArchive(z, extName);
					} catch (IllegalArgumentException iae) {
					}
				}
			}
		} catch (IOException ioe) {
		}
		if (hasDef && hasVersion != null) {
			return hasVersion;
		}
		throw new IllegalArgumentException("Not an extension stream.");
	}

	public static String getExtensionVersion(InputStream in) {
		ZipEntry en;
		boolean hasDef = false;
		String hasVersion = null;
		try (var z = new ZipInputStream(in)) {
			while ((en = z.getNextEntry()) != null) {
				if (en.getName().equals("extension.def")) {
					hasDef = true;
				} else if (en.getName().equals("META-INF/MANIFEST.MF")) {
					var mf = new Manifest(z);
					hasVersion = mf.getMainAttributes().getValue("Implementation-Version");
				} else if (en.getName().endsWith(".jar")) {
					try {
						return getExtensionVersion(z);
					} catch (IllegalArgumentException iae) {
					}
				}
			}
		} catch (IOException ioe) {
		}
		if (hasDef && hasVersion != null)
			return hasVersion;
		throw new IllegalArgumentException("Not an extension stream.");
	}

	public static Properties getExtensionProperties(InputStream in) {
		ZipEntry en;
		try (var z = new ZipInputStream(in)) {
			while ((en = z.getNextEntry()) != null) {
				if (en.getName().equals("extension.def") || en.getName().endsWith("/extension.def")) {
					return properties(z);
				} else if (en.getName().endsWith(".jar")) {
					try {
						return getExtensionProperties(z);
					} catch (IllegalArgumentException iae) {
					}
				}
			}
		} catch (IOException ioe) {
		}
		throw new IllegalArgumentException("Not an extension stream.");
	}
	
	public static String getBestProperty(String defaultValue, Collection<Properties> properties, String... keys) {
		var it = Arrays.asList(keys).iterator();
		for(var p : properties) {
			var k = it.hasNext() ? it.next() : "";
			if(!k.equals("") && p.containsKey(k))
				return p.getProperty(k);
		}
		return defaultValue;
	}

	private static Properties properties(InputStream in) throws IOException {
		var p = new Properties();
		p.load(in);
		return p;
	}
	
	private static void putIfNotNull(Properties p, String k, String n, Document doc) {
		var v = findElString(doc, n);
		if(v != null)
			p.put(k, v);
	}

	private static String findElString(Document doc, String name) {
		try {
			return doc.getDocumentElement().getElementsByTagName(name).item(0).getTextContent();
		}
		catch(Exception e) {
			return null;
		}
	}
}
