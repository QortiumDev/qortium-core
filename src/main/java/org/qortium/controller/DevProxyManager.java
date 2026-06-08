package org.qortium.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.api.DevProxyService;
import org.qortium.repository.DataException;
import org.qortium.settings.Settings;

import java.util.Locale;

public class DevProxyManager {

    protected static final Logger LOGGER = LogManager.getLogger(DevProxyManager.class);

    private static DevProxyManager instance;

    private boolean running = false;

    private String sourceHostAndPort = "127.0.0.1:5173"; // Default for React/Vite

    private DevProxyManager() {

    }

    public static DevProxyManager getInstance() {
        if (instance == null)
            instance = new DevProxyManager();

        return instance;
    }

    public void start() throws DataException {
        synchronized(this) {
            if (!Settings.getInstance().isDevProxyEnabled())
                throw new DataException("Developer proxy is disabled. Set devProxyEnabled to true before starting it.");

            if (this.running) {
                // Already running
                return;
            }

            LOGGER.info(String.format("Starting developer proxy service on port %d", Settings.getInstance().getDevProxyPort()));
            DevProxyService devProxyService = DevProxyService.getInstance();
            devProxyService.start();
            this.running = true;
        }
    }

    public void stop() {
        synchronized(this) {
            if (!this.running) {
                // Not running
                return;
            }

            LOGGER.info(String.format("Shutting down developer proxy service"));
            DevProxyService devProxyService = DevProxyService.getInstance();
            devProxyService.stop();
            this.running = false;
        }
    }

    public void setSourceHostAndPort(String sourceHostAndPort) throws DataException {
        this.sourceHostAndPort = DevProxyManager.validateSourceHostAndPort(sourceHostAndPort);
    }

    public String getSourceHostAndPort() {
        return this.sourceHostAndPort;
    }

    public Integer getPort() {
        return Settings.getInstance().getDevProxyPort();
    }

    public boolean isRunning() {
        return this.running;
    }

    private static String validateSourceHostAndPort(String sourceHostAndPort) throws DataException {
        if (sourceHostAndPort == null || sourceHostAndPort.isBlank()) {
            throw new DataException("Developer proxy source must be a loopback host and port");
        }

        String source = sourceHostAndPort.trim();
        if (source.contains("://") || source.contains("/") || source.contains("\\") ||
                source.contains("?") || source.contains("#") || source.contains("@")) {
            throw new DataException("Developer proxy source must be a host and port only");
        }

        String normalizedHost;
        String portString;
        if (source.startsWith("[")) {
            int closingBracketIndex = source.indexOf(']');
            if (closingBracketIndex < 0 || closingBracketIndex == source.length() - 1 ||
                    source.charAt(closingBracketIndex + 1) != ':' || source.indexOf('[', 1) >= 0 ||
                    source.indexOf(']', closingBracketIndex + 1) >= 0) {
                throw new DataException("Developer proxy source must include a valid host and port");
            }

            String host = source.substring(1, closingBracketIndex).toLowerCase(Locale.ROOT);
            if (!"::1".equals(host)) {
                throw new DataException("Developer proxy source must use a loopback host");
            }

            normalizedHost = "[::1]";
            portString = source.substring(closingBracketIndex + 2);
        }
        else {
            int colonIndex = source.indexOf(':');
            if (colonIndex <= 0 || colonIndex != source.lastIndexOf(':') || colonIndex == source.length() - 1) {
                throw new DataException("Developer proxy source must include a valid host and port");
            }

            String host = source.substring(0, colonIndex).toLowerCase(Locale.ROOT);
            if (!"localhost".equals(host) && !"127.0.0.1".equals(host)) {
                throw new DataException("Developer proxy source must use a loopback host");
            }

            normalizedHost = host;
            portString = source.substring(colonIndex + 1);
        }

        int port;
        try {
            port = Integer.parseInt(portString);
        } catch (NumberFormatException e) {
            throw new DataException("Developer proxy source port is invalid");
        }

        if (port < 1 || port > 65535) {
            throw new DataException("Developer proxy source port is invalid");
        }
        if (port == Settings.getInstance().getApiPort()) {
            throw new DataException("Developer proxy source cannot target the API port");
        }
        if (port == Settings.getInstance().getDevProxyPort()) {
            throw new DataException("Developer proxy source cannot target the developer proxy port");
        }

        return String.format("%s:%d", normalizedHost, port);
    }

}
