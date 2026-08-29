package org.qortium.api;

import io.swagger.v3.jaxrs2.integration.resources.OpenApiResource;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.rewrite.handler.RewriteHandler;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.qortium.api.resource.AnnotationPostProcessor;
import org.qortium.api.resource.ApiDefinition;
import org.qortium.network.Network;
import org.qortium.settings.Settings;
import org.qortium.utils.SslUtils;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;

public class GatewayService {

	private static GatewayService instance;

	private final ResourceConfig config;
	private Server server;

	private GatewayService() {
		this.config = createResourceConfig();
	}

	/** Creates the same resource configuration used by the live gateway. */
	static ResourceConfig createResourceConfig() {
		ResourceConfig config = new ResourceConfig();
		config.packages("org.qortium.api.resource", "org.qortium.api.gateway.resource");
		registerPublicApiAccess(config);
		config.register(org.glassfish.jersey.media.multipart.MultiPartFeature.class);
		config.register(OpenApiResource.class);
		config.register(ApiDefinition.class);
		config.register(AnnotationPostProcessor.class);
		return config;
	}

	public static GatewayService getInstance() {
		if (instance == null)
			instance = new GatewayService();

		return instance;
	}

	public Iterable<Class<?>> getResources() {
		return this.config.getClasses();
	}

	public void start() {
		try {
			// Create API server

			// SSL support if requested
			String keystorePathname = Settings.getInstance().getSslKeystorePathname();
			String keystorePassword = Settings.getInstance().getSslKeystorePassword();

			if (keystorePathname != null && keystorePassword != null) {
				keystorePassword = Settings.ensureGeneratedSslKeystorePassword();
				Path keystorePath = Path.of(keystorePathname);

				// SSL version
				SslUtils.ensureKeystorePermissions(keystorePath);
				if (!Files.isReadable(keystorePath))
					throw new RuntimeException("Failed to start SSL API due to broken keystore");

				// BouncyCastle-specific SSLContext build
				SSLContext sslContext = SSLContext.getInstance("TLS", "BCJSSE");
				KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("PKIX", "BCJSSE");

				KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType(), "BC");

				try (InputStream keystoreStream = Files.newInputStream(keystorePath)) {
					keyStore.load(keystoreStream, keystorePassword.toCharArray());
				}

				keyManagerFactory.init(keyStore, keystorePassword.toCharArray());
				sslContext.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());

				SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
				sslContextFactory.setSslContext(sslContext);
				SslUtils.configureServerTls(sslContextFactory);

				this.server = new Server();

				HttpConfiguration httpConfig = new HttpConfiguration();
				httpConfig.setSecureScheme("https");
				httpConfig.setSecurePort(Settings.getInstance().getGatewayPort());

				SecureRequestCustomizer src = new SecureRequestCustomizer();
				httpConfig.addCustomizer(src);

				HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory(httpConfig);
				SslConnectionFactory sslConnectionFactory = new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString());

				ServerConnector portUnifiedConnector = new ServerConnector(this.server,
						new DetectorConnectionFactory(sslConnectionFactory),
						httpConnectionFactory);
				portUnifiedConnector.setHost(Network.getInstance().getBindAddress());
				portUnifiedConnector.setPort(Settings.getInstance().getGatewayPort());

				this.server.addConnector(portUnifiedConnector);
			} else {
				// Non-SSL
				InetAddress bindAddr = InetAddress.getByName(Network.getInstance().getBindAddress());
				InetSocketAddress endpoint = new InetSocketAddress(bindAddr, Settings.getInstance().getGatewayPort());
				this.server = new Server(endpoint);
			}

			// Error handler
			ErrorHandler errorHandler = new ApiErrorHandler();
			this.server.setErrorHandler(errorHandler);

			// Request logging
			if (Settings.getInstance().isGatewayLoggingEnabled()) {
				RequestLogWriter logWriter = new RequestLogWriter("gateway-requests.log");
				logWriter.setAppend(true);
				logWriter.setTimeZone("UTC");
				RequestLog requestLog = new CustomRequestLog(logWriter, ApiService.API_REQUEST_LOG_FORMAT);
				this.server.setRequestLog(requestLog);
			}

			// URL rewriting
			RewriteHandler rewriteHandler = new RewriteHandler();
			PublicApiProtectionHandler protectionHandler = new PublicApiProtectionHandler();

			this.server.setHandler(protectionHandler);
			protectionHandler.setHandler(rewriteHandler);

			// Response compression: rendered QDN app bundles are often hundreds of
			// KB of JS/CSS, and gateway clients are frequently slow/unreliable
			// (mobile, appliance browsers) where an uncompressed asset may never
			// finish downloading.
			GzipHandler gzipHandler = new GzipHandler();
			gzipHandler.setMinGzipSize(1400);
			rewriteHandler.setHandler(gzipHandler);

			// Context
			ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
			context.setContextPath("/");
			gzipHandler.setHandler(context);

			// Cross-origin resource sharing
			CorsFilter.addTo(context);

			// API servlet
			ServletContainer container = new ServletContainer(this.config);
			ServletHolder apiServlet = new ServletHolder(container);
			apiServlet.setInitOrder(1);
			context.addServlet(apiServlet, "/*");

			// Start server
			this.server.start();
		} catch (Exception e) {
			// Failed to start
			throw new RuntimeException("Failed to start API", e);
		}
	}

	static void registerPublicApiAccess(ResourceConfig config) {
		// Jersey must resolve the route before access control can distinguish the
		// gateway's public QDN catch-all from API resources in the same URL space.
		config.register(GatewayPublicApiAccessFilter.class);
	}

	public void stop() {
		try {
			// Stop server
			this.server.stop();
		} catch (Exception e) {
			// Failed to stop
		}

		this.server = null;
	}

}
