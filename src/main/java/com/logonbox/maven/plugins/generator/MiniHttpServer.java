package com.logonbox.maven.plugins.generator;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MiniHttpServer extends Thread implements Closeable {

	public static class DynamicContent {

		private long contentLength;
		private InputStream in;
		private String mimeType;
		private String[] headers;

		public DynamicContent(String mimeType, InputStream in, String... headers) {
			this(mimeType, -1, in, headers);
		}

		public DynamicContent(String mimeType, long contentLength, InputStream in, String... headers) {
			super();
			this.headers = headers;
			this.mimeType = mimeType;
			this.in = in;
			this.contentLength = contentLength;
		}

		public long getContentLength() {
			return contentLength;
		}

		public InputStream getIn() {
			return in;
		}

		public String getMimeType() {
			return mimeType;
		}

		public String[] getHeaders() {
			return headers;
		}

	}

	public interface DynamicContentFactory {
		DynamicContent get(Method method, String path, Map<String, List<String>> headers, InputStream content) throws IOException;
	}

	public enum Method {
		GET, HEAD, POST
	}

	public enum Status {

		BAD_REQUEST(400, "Bad Request"), FORBIDDEN(403, "Forbidden"), FOUND(302, "Found"),
		INTERNAL_SERVER_ERROR(500, "Not Found"), MOVED_PERMANENTLY(301, "Moved Permanently"),
		NOT_FOUND(404, "Not Found"), NOT_IMPLEMENTED(501, "Not Implement"), OK(200, "OK");

		private int code;
		private String text;

		Status(int code, String text) {
			this.code = code;
			this.text = text;
		}

		public int getCode() {
			return code;
		}

		public String getText() {
			return text;
		}

	}

	public static final String KEYSTORE_PASSWORD = "changeit";

	private static Logger LOG = LoggerFactory.getLogger(MiniHttpServer.class);

	private boolean caching = true;
	private List<DynamicContentFactory> contentFactories = new ArrayList<>();
	private boolean open = true;
	private ExecutorService pool = Executors.newFixedThreadPool(10);
	private ServerSocket serversocket;
	private SSLServerSocket sslServersocket;

	public static final String HYPERSOCKET_BOOT_HTTP_SERVER = "hypersocket.bootHttpServer";
	public static final String HYPERSOCKET_BOOT_HTTP_SERVER_DEFAULT = "true";

	public MiniHttpServer(int http, int https, File keystoreFile) throws IOException {
		super("MiniHttpServer");

		LOG.info(String.format("Open temporary HTTP server on port %d", http));

		if (http > 0) {
			serversocket = new ServerSocket(http, 10);
			serversocket.setReuseAddress(true);
		}

		if (https > 0) {
			LOG.info(String.format("Open temporary HTTPS server on port %d", https));

			SSLContext sc = null;
			try {
				KeyStore ks = null;
				KeyManagerFactory kmf = null;

				if (keystoreFile != null && keystoreFile.exists()) {
					LOG.info(String.format("Using keystore %s", keystoreFile));
					try (InputStream fin = new FileInputStream(keystoreFile)) {
						ks = loadKeyStoreFromJKS(fin, KEYSTORE_PASSWORD.toCharArray());
						kmf = KeyManagerFactory.getInstance("SunX509");
						kmf.init(ks, KEYSTORE_PASSWORD.toCharArray());
					} catch (Exception e) {
						LOG.error("Failed to load temporary keystore, reverting to default.", e);
						ks = null;
					}
				}

				if (ks == null) {
					throw new IllegalStateException("Configure a keystore");
				}

				sc = SSLContext.getInstance("TLS");
				sc.init(kmf.getKeyManagers(), null, null);
			} catch (Exception e) {
				throw new IOException("Failed to configure SSL.", e);
			}

			SSLServerSocketFactory ssf = sc.getServerSocketFactory();
			sslServersocket = (SSLServerSocket) ssf.createServerSocket(https, 10);
			sslServersocket.setReuseAddress(true);
		}

		if (http < 1 && https < 1)
			throw new IOException("Neither HTTP or HTTPs port were provided.");
	}

	public void addContent(DynamicContentFactory page) {
		contentFactories.add(page);
	}

	@Override
	public void close() throws IOException {
		if (!open)
			throw new IOException("Already closed.");
		LOG.info("Closing Mini HTTP server.");
		open = false;
		try {
			serversocket.close();
		} finally {
			try {
				if (sslServersocket != null)
					sslServersocket.close();
			} finally {
				pool.shutdown();
			}
		}
	}

	public boolean isCaching() {
		return caching;
	}

	public void run() {
		/* Run, keeping number of thread used to minimum required for configuration */

		if (serversocket == null) {
			/* HTTPS only */
			runOn(sslServersocket);
		} else if (sslServersocket == null) {
			/* HTTP only */
			runOn(serversocket);
		} else if (serversocket != null && sslServersocket != null) {
			/* Both */
			Thread t = new Thread("SSLMiniHttpServer") {
				public void run() {
					runOn(serversocket);
				}
			};
			t.start();
			runOn(sslServersocket);
			try {
				t.join();
			} catch (InterruptedException e) {
			}
		} else
			throw new IllegalStateException();
	}

	public void setCaching(boolean caching) {
		this.caching = caching;
	}

	private void connection(Socket socket) {
		try {
			InetAddress client = socket.getInetAddress();
			if (client == null) {
				LOG.error("Socket was lost between accepting it and starting to handle it. This can be caused "
						+ "by the system socket factory being swapped out for another while the boot HTTP server "
						+ "is running. Closing down the server now, it has become useless!");
				try {
					close();
				} catch (IOException e) {
				}
			} else {
				try {
					LOG.debug(String.format("%s connected to server", client.getHostName()));
					InputStream input = socket.getInputStream();
					try (OutputStream out = socket.getOutputStream()) {
						handle(input, out);
						socket.getOutputStream().flush();
					}
				} catch (Exception e) {
					LOG.info("Failed handling connection.", e);
				}
			}
		} finally {
			try {
				socket.close();
			} catch (IOException ioe) {
			}
		}
	}

	private String getMimeType(URL url) {
		try {
			URLConnection conx = url.openConnection();
			try {
				String contentType = conx.getContentType();
				return contentType;
			} finally {
				try {
					conx.getInputStream().close();
				} catch (IOException ioe) {
				}
			}
		} catch (IOException ioe) {
			return URLConnection.guessContentTypeFromName(FilenameUtils.getName(url.getPath()));
		}
	}

	private void handle(InputStream in, OutputStream output) throws IOException {
		Method method = null;
		String path = new String();
		

		BufferedReader lineInput = new BufferedReader(new InputStreamReader(in));
		
		String tmp = lineInput.readLine();
		String tmp2 = new String(tmp);
		tmp.toUpperCase();
		if (tmp.startsWith("GET")) {
			method = Method.GET;
		} else if (tmp.startsWith("HEAD")) {
			method = Method.HEAD;
		} else if (tmp.startsWith("POST")) {
			method = Method.POST;
		} else {
			output.write(header(Status.NOT_IMPLEMENTED, "text/plain", -1));
			return;
		}

		int start = 0;
		int end = 0;
		for (int a = 0; a < tmp2.length(); a++) {
			if (tmp2.charAt(a) == ' ' && start != 0) {
				end = a;
				break;
			}
			if (tmp2.charAt(a) == ' ' && start == 0) {
				start = a;
			}
		}
		path = tmp2.substring(start + 2, end);
		if (path.equals(""))
			path = "/";

		if (path.equals("/"))
			path = "index.html";

		if (!path.startsWith("/"))
			path = "/" + path;

		String line = null;
		Map<String, List<String>> headers = new HashMap<>();
		while ((line = lineInput.readLine()) != null) {
			if (line.equals(""))
				break;
			int idx = line.indexOf(':');
			if (idx == -1)
				throw new IOException("Bad header '" + line + "'.");
			String name = line.substring(0, idx).trim();
			String value = line.substring(idx + 1).trim();
			List<String> values = headers.get(name);
			if (values == null) {
				values = new ArrayList<>();
				headers.put(name, values);
			}
			values.add(value);
		}

		Status status = null;
		for (DynamicContentFactory c : contentFactories) {
			DynamicContent content = null;
			try {
				content = c.get(method, path, headers, in);
			}
			catch(FileNotFoundException fnfe)  {
				status = Status.NOT_FOUND;
			}
			catch(UnsupportedOperationException uoe)  {
				status = Status.NOT_IMPLEMENTED;
			}
			catch(Exception ioe) {
				status = Status.INTERNAL_SERVER_ERROR;
				ioe.printStackTrace();
			}
			if (content != null) {
				if(status == null) 
					status = Status.OK; 
				output.write(
						header(status, content.getMimeType(), content.getContentLength(), content.getHeaders()));
				InputStream contentIn = content.getIn();
				try {
					IOUtils.copy(contentIn, output);
				} finally {
					in.close();
				}
				return;
			}
			else if(status != null) {
				output.write(
						header(status, "text/plain", 0));
				return;
			}
		}

		URL res = getClass().getResource("/boothttp" + path);

		if (res == null) {
			output.write(header(Status.NOT_FOUND, "text/plain", -1));
			return;
		}

		if (method == Method.GET) {
			URLConnection conx = res.openConnection();
			output.write(header(status, getMimeType(res), conx.getContentLengthLong()));
			try (InputStream contentIn = conx.getInputStream()) {
				IOUtils.copy(contentIn, output);
			}
		}

	}

	private byte[] header(Status status, String contentType, long contentLength, String... headers) {
		String s = "HTTP/1.0 " + status.getCode() + " " + status.getText();
		s = s + "\r\n";
		s = s + "Connection: close\r\n";
		s = s + "Server: Hypersocket v0\r\n";
		s = s + "Content-Type: " + contentType + "\r\n";
		if (contentLength != -1)
			s = s + "Content-Length: " + contentLength + "\r\n";
		for (int i = 0; i < headers.length; i += 2) {
			s = s + headers[i] + ": " + headers[i + 1] + "\r\n";
		}
		if (!caching) {
			s = s + "Cache-Control: no-cache\r\n";
		}
		s = s + "\r\n";
		LOG.debug("Response header " + s);
		return s.getBytes();
	}

	private void runOn(ServerSocket so) {
		while (open) {
			LOG.debug("Waiting for connection");
			try {
				Socket connectionsocket = so.accept();
				pool.execute(() -> connection(connectionsocket));
			} catch (Exception e) {
				LOG.info("Failed waiting for connection.", e);
			}
		}
	}

	public static KeyPair generatePrivateKey(String algorithm, int bits) throws CertificateException {
		try {
			KeyPairGenerator kpGen = KeyPairGenerator.getInstance(algorithm, "BC");
			kpGen.initialize(bits, new SecureRandom());
			return kpGen.generateKeyPair();
		} catch (Throwable t) {
			throw new CertificateException("Failed to generate private key", t);
		}
	}

	public static KeyStore loadKeyStoreFromJKS(InputStream jksFile, char[] passphrase)
			throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException,
			NoSuchProviderException, UnrecoverableKeyException {

		try {
			KeyStore keystore = KeyStore.getInstance("JKS");
			keystore.load(jksFile, passphrase);
			return keystore;
		} finally {
			IOUtils.closeQuietly(jksFile);
		}
	}
}