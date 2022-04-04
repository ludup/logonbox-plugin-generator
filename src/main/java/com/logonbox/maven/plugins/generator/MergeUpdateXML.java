package com.logonbox.maven.plugins.generator;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URL;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.sonatype.inject.Description;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.amazonaws.services.s3.AmazonS3;

/**
 * Merge Install4J Update XML files
 */
@Mojo(defaultPhase = LifecyclePhase.DEPLOY, name = "merge-update-xml", requiresProject = true, requiresDirectInvocation = false, threadSafe = true)
@Description("Merge Update XML")
public class MergeUpdateXML extends AbstractS3UploadMojo {

	@Parameter
	protected String[] urls;

	@Parameter
	protected String baseUrl;

	/**
	 * The maven project.
	 */
	@Parameter(required = true, readonly = true, property = "project")
	protected MavenProject project;

	@Override
	protected AmazonS3 upload(AmazonS3 amazonS3) throws IOException {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		try {
			dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			DocumentBuilder db = dbf.newDocumentBuilder();
			Document outDoc = db.newDocument();
			Element outDescriptor = outDoc.createElement("updateDescriptor");
			if (baseUrl == null || baseUrl.length() == 0) {
				baseUrl = getPublicURI();
				while (baseUrl.endsWith("/"))
					baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
				int lidx = baseUrl.lastIndexOf('/');
				if (lidx != -1) {
					baseUrl = baseUrl.substring(0, lidx);
				}
			}
			if (!baseUrl.endsWith("/"))
				baseUrl += "/";
			for (String url : urls) {
				URL u = new URL(url);
				try (InputStream in = u.openStream()) {
					Document doc = db.parse(in);
					doc.getDocumentElement().normalize();
					NodeList list = doc.getElementsByTagName("entry");
					for (int i = 0; i < list.getLength(); i++) {
						Node node = list.item(i);
						if (node.getNodeType() == Node.ELEMENT_NODE) {
							Element element = (Element) node;
							removeByMediaId(outDoc, element.getAttribute("targetMediaFileId"));
							outDescriptor.appendChild(element.cloneNode(true));
						}
					}
				} catch (SAXException e) {
					throw new IOException("Failed to parse.", e);
				}
			}
			amazonS3.putObject(bucketName, keyPrefix, writeXmlDocumentToXmlFile(outDoc));
		} catch (ParserConfigurationException pe) {
			throw new IOException("Failed to setup parser.", pe);
		}
		return amazonS3;
	}

	private String writeXmlDocumentToXmlFile(Document xmlDocument) throws IOException {
		TransformerFactory tf = TransformerFactory.newInstance();
		Transformer transformer;
		try {
			transformer = tf.newTransformer();
			StringWriter writer = new StringWriter();
			transformer.transform(new DOMSource(xmlDocument), new StreamResult(writer));
			return writer.getBuffer().toString();
		} catch (TransformerException e) {
			throw new IOException("Failed to format XML.", e);
		} catch (Exception e) {
			throw new IOException("Failed to format XML.", e);
		}
	}

	void removeByMediaId(Document outDoc, String id) {
		NodeList list = outDoc.getElementsByTagName("entry");
		for (int i = 0; i < list.getLength(); i++) {
			Node node = list.item(i);
			if (node.getNodeType() == Node.ELEMENT_NODE) {
				Element element = (Element) node;
				if (element.getAttribute("targetMediaFileId").equals(id)) {
					element.removeChild(element.getParentNode());
				}
			}
		}
	}
}