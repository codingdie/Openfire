/**
 * $RCSfile$
 * $Revision: $
 * $Date: $
 *
 * Copyright (C) 2006 Jive Software. All rights reserved.
 *
 * This software is published under the terms of the GNU Public License (GPL),
 * a copy of which is included in this distribution.
 */

package org.jivesoftware.wildfire.http;

import org.mortbay.jetty.Server;
import org.mortbay.jetty.Connector;
import org.mortbay.jetty.webapp.WebAppContext;
import org.mortbay.jetty.security.SslSocketConnector;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.jivesoftware.util.*;
import org.jivesoftware.wildfire.net.SSLConfig;
import org.jivesoftware.wildfire.XMPPServer;

import javax.servlet.http.HttpServlet;
import javax.net.ssl.SSLServerSocketFactory;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.io.File;

/**
 *
 */
public final class HttpBindManager {

    public static final String HTTP_BIND_ENABLED = "httpbind.enabled";

    public static final boolean HTTP_BIND_ENABLED_DEFAULT = true;

    public static final String HTTP_BIND_PORT = "httpbind.port.plain";

    public static final int HTTP_BIND_PORT_DEFAULT = 8080;

    public static final String HTTP_BIND_SECURE_PORT = "httpbind.port.secure";

    public static final int HTTP_BIND_SECURE_PORT_DEFAULT = 8483;

    private static HttpBindManager instance = new HttpBindManager();

    private Server httpBindServer;

    private int bindPort;

    private int bindSecurePort;

    private CertificateListener certificateListener;

    private HttpSessionManager httpSessionManager;

    public static HttpBindManager getInstance() {
        return instance;
    }

    private HttpBindManager() {
        // Configure Jetty logging to a more reasonable default.
        System.setProperty("org.mortbay.log.class", "org.jivesoftware.util.log.util.JettyLog");
        // JSP 2.0 uses commons-logging, so also override that implementation.
        System.setProperty("org.apache.commons.logging.LogFactory", "org.jivesoftware.util.log.util.CommonsLogFactory");

        PropertyEventDispatcher.addListener(new HttpServerPropertyListener());
        this.httpSessionManager = new HttpSessionManager();
    }

    public void start() {
        certificateListener = new CertificateListener();
        CertificateManager.addListener(certificateListener);

        if (!isHttpBindServiceEnabled()) {
            return;
        }
        bindPort = getHttpBindUnsecurePort();
        bindSecurePort = getHttpBindSecurePort();
        configureHttpBindServer(bindPort, bindSecurePort);

        try {
            httpBindServer.start();
        }
        catch (Exception e) {
            Log.error("Error starting HTTP bind service", e);
        }
    }

    public void stop() {
        CertificateManager.removeListener(certificateListener);

        if (httpBindServer != null) {
            try {
                httpBindServer.stop();
            }
            catch (Exception e) {
                Log.error("Error stoping HTTP bind service", e);
            }
        }
    }

    public HttpSessionManager getSessionManager() {
        return httpSessionManager;
    }

    private boolean isHttpBindServiceEnabled() {
        return JiveGlobals.getBooleanProperty(HTTP_BIND_ENABLED, HTTP_BIND_ENABLED_DEFAULT);
    }

    private Connector createConnector(int port) {
        if (port > 0) {
            SelectChannelConnector connector = new SelectChannelConnector();
            // Listen on a specific network interface if it has been set.
            connector.setHost(getBindInterface());
            connector.setPort(port);
            return connector;
        }
        return null;
    }

    private Connector createSSLConnector(int securePort) {
        try {
            if (securePort > 0 && CertificateManager.isRSACertificate(SSLConfig.getKeyStore(),
                    XMPPServer.getInstance().getServerInfo().getName())) {
                SslSocketConnector sslConnector = new JiveSslConnector();
                sslConnector.setHost(getBindInterface());
                sslConnector.setPort(securePort);

                sslConnector.setTrustPassword(SSLConfig.getTrustPassword());
                sslConnector.setTruststoreType(SSLConfig.getStoreType());
                sslConnector.setTruststore(SSLConfig.getTruststoreLocation());
                sslConnector.setNeedClientAuth(false);
                sslConnector.setWantClientAuth(false);

                sslConnector.setKeyPassword(SSLConfig.getKeyPassword());
                sslConnector.setKeystoreType(SSLConfig.getStoreType());
                sslConnector.setKeystore(SSLConfig.getKeystoreLocation());
                return sslConnector;
            }
        }
        catch (Exception e) {
            Log.error("Error creating SSL connector for Http bind", e);
        }
        return null;
    }

    private String getBindInterface() {
        String interfaceName = JiveGlobals.getXMLProperty("network.interface");
        String bindInterface = null;
        if (interfaceName != null) {
            if (interfaceName.trim().length() > 0) {
                bindInterface = interfaceName;
            }
        }
        return bindInterface;
    }

    /**
     * Returns true if the HTTP binding server is currently enabled.
     *
     * @return true if the HTTP binding server is currently enabled.
     */
    public boolean isHttpBindEnabled() {
        return httpBindServer != null && httpBindServer.isRunning();
    }

    /**
     * Returns all the servlets that are part of the http-bind service.
     *
     * @return all the servlets that are part of the http-bind service.
     */
    public Map<HttpServlet, String> getServlets() {
        Map<HttpServlet, String> servlets = new HashMap<HttpServlet, String>();
        servlets.put(new ResourceServlet(), "/http-bind/js/");

        return servlets;
    }


    public String getHttpBindUnsecureAddress() {
        return "http://" + XMPPServer.getInstance().getServerInfo().getName() + ":" +
                bindPort + "/http-bind/";
    }

    public String getHttpBindSecureAddress() {
        return "https://" + XMPPServer.getInstance().getServerInfo().getName() + ":" +
                bindSecurePort + "/http-bind/";
    }

    public String getJavaScriptUrl() {
        return "http://" + XMPPServer.getInstance().getServerInfo().getName() + ":" +
                bindPort + "/scripts/";
    }

    public void setHttpBindEnabled(boolean isEnabled) {
        JiveGlobals.setProperty(HTTP_BIND_ENABLED, String.valueOf(isEnabled));
    }

    /**
     * Set the ports on which the HTTP binding service will be running.
     *
     * @param unsecurePort the unsecured connection port which clients can connect to.
     * @param securePort the secured connection port which clients can connect to.
     * @throws Exception when there is an error configuring the HTTP binding ports.
     */
    public void setHttpBindPorts(int unsecurePort, int securePort) throws Exception {
        changeHttpBindPorts(unsecurePort, securePort);
        bindPort = unsecurePort;
        bindSecurePort = securePort;
        if (unsecurePort != HTTP_BIND_PORT_DEFAULT) {
            JiveGlobals.setProperty(HTTP_BIND_PORT, String.valueOf(unsecurePort));
        }
        else {
            JiveGlobals.deleteProperty(HTTP_BIND_PORT);
        }
        if (securePort != HTTP_BIND_SECURE_PORT_DEFAULT) {
            JiveGlobals.setProperty(HTTP_BIND_SECURE_PORT, String.valueOf(securePort));
        }
        else {
            JiveGlobals.deleteProperty(HTTP_BIND_SECURE_PORT);
        }
    }

    private synchronized void changeHttpBindPorts(int unsecurePort, int securePort)
            throws Exception
    {
        if (unsecurePort < 0 && securePort < 0) {
            throw new IllegalArgumentException("At least one port must be greater than zero.");
        }
        if (unsecurePort == securePort) {
            throw new IllegalArgumentException("Ports must be distinct.");
        }

        if (httpBindServer != null) {
            try {
                httpBindServer.stop();
            }
            catch (Exception e) {
                Log.error("Error stopping http bind server", e);
            }
        }

        configureHttpBindServer(unsecurePort, securePort);
        httpBindServer.start();
    }

    /**
     * Starts an HTTP Bind server on the specified port and secure port.
     *
     * @param port the port to start the normal (unsecured) HTTP Bind service on.
     * @param securePort the port to start the TLS (secure) HTTP Bind service on.
     */
    private synchronized void configureHttpBindServer(int port, int securePort) {
        httpBindServer = new Server();
        Connector httpConnector = createConnector(port);
        Connector httpsConnector = createSSLConnector(securePort);
        if (httpConnector == null && httpsConnector == null) {
            httpBindServer = null;
            return;
        }
        if (httpConnector != null) {
            httpBindServer.addConnector(httpConnector);
        }
        if (httpsConnector != null) {
            httpBindServer.addConnector(httpsConnector);
        }

        httpBindServer.addHandler(createWebAppContext());
    }

    private WebAppContext createWebAppContext() {
        return new WebAppContext(JiveGlobals.getHomeDirectory() + File.separator +
                                    "resources" + File.separator + "spank", "/");
    }

    private void doEnableHttpBind(boolean shouldEnable) {
        if (shouldEnable && httpBindServer == null) {
            try {
                changeHttpBindPorts(JiveGlobals.getIntProperty(HTTP_BIND_PORT,
                        HTTP_BIND_PORT_DEFAULT), JiveGlobals.getIntProperty(HTTP_BIND_SECURE_PORT,
                        HTTP_BIND_SECURE_PORT_DEFAULT));
            }
            catch (Exception e) {
                Log.error("Error configuring HTTP binding ports", e);
            }
        }
        else if (!shouldEnable && httpBindServer != null) {
            try {
                httpBindServer.stop();
            }
            catch (Exception e) {
                Log.error("Error stopping HTTP bind service", e);
            }
            httpBindServer = null;
        }
    }

    /**
     * Returns the HTTP binding port which does not use SSL.
     *
     * @return the HTTP binding port which does not use SSL.
     */
    public int getHttpBindUnsecurePort() {
        return JiveGlobals.getIntProperty(HTTP_BIND_PORT, HTTP_BIND_PORT_DEFAULT);
    }

    /**
     * Returns the HTTP binding port which uses SSL.
     *
     * @return the HTTP binding port which uses SSL.
     */
    public int getHttpBindSecurePort() {
        return JiveGlobals.getIntProperty(HTTP_BIND_SECURE_PORT, HTTP_BIND_SECURE_PORT_DEFAULT);
    }

    private void setUnsecureHttpBindPort(int value) {
        if (value == bindPort) {
            return;
        }
        try {
            changeHttpBindPorts(value, JiveGlobals.getIntProperty(HTTP_BIND_SECURE_PORT,
                    HTTP_BIND_SECURE_PORT_DEFAULT));
            bindPort = value;
        }
        catch (Exception ex) {
            Log.error("Error setting HTTP bind ports", ex);
        }
    }

    private void setSecureHttpBindPort(int value) {
        if (value == bindSecurePort) {
            return;
        }
        try {
            changeHttpBindPorts(JiveGlobals.getIntProperty(HTTP_BIND_PORT,
                    HTTP_BIND_PORT_DEFAULT), value);
            bindSecurePort = value;
        }
        catch (Exception ex) {
            Log.error("Error setting HTTP bind ports", ex);
        }
    }

    private synchronized void restartServer() {
        if(httpBindServer != null) {
            try {
                httpBindServer.stop();
            }
            catch (Exception e) {
                Log.error("Error stopping http bind server", e);
            }

            configureHttpBindServer(getHttpBindUnsecurePort(), getHttpBindSecurePort());
        }
    }

    /** Listens for changes to Jive properties that affect the HTTP server manager. */
    private class HttpServerPropertyListener implements PropertyEventListener {

        public void propertySet(String property, Map params) {
            if (property.equalsIgnoreCase(HTTP_BIND_ENABLED)) {
                doEnableHttpBind(Boolean.valueOf(params.get("value").toString()));
            }
            else if (property.equalsIgnoreCase(HTTP_BIND_PORT)) {
                int value;
                try {
                    value = Integer.valueOf(params.get("value").toString());
                }
                catch (NumberFormatException ne) {
                    JiveGlobals.deleteProperty(HTTP_BIND_PORT);
                    return;
                }
                setUnsecureHttpBindPort(value);
            }
            else if (property.equalsIgnoreCase(HTTP_BIND_SECURE_PORT)) {
                int value;
                try {
                    value = Integer.valueOf(params.get("value").toString());
                }
                catch (NumberFormatException ne) {
                    JiveGlobals.deleteProperty(HTTP_BIND_SECURE_PORT);
                    return;
                }
                setSecureHttpBindPort(value);
            }
        }

        public void propertyDeleted(String property, Map params) {
            if (property.equalsIgnoreCase(HTTP_BIND_ENABLED)) {
                doEnableHttpBind(HTTP_BIND_ENABLED_DEFAULT);
            }
            else if (property.equalsIgnoreCase(HTTP_BIND_PORT)) {
                setUnsecureHttpBindPort(HTTP_BIND_PORT_DEFAULT);
            }
            else if (property.equalsIgnoreCase(HTTP_BIND_SECURE_PORT)) {
                setSecureHttpBindPort(HTTP_BIND_SECURE_PORT_DEFAULT);
            }
        }

        public void xmlPropertySet(String property, Map params) {
        }

        public void xmlPropertyDeleted(String property, Map params) {
        }
    }

    private class JiveSslConnector extends SslSocketConnector {

        @Override
        protected SSLServerSocketFactory createFactory() throws Exception {
            return SSLConfig.getServerSocketFactory();
        }
    }

    private class CertificateListener implements CertificateEventListener {

        public void certificateCreated(KeyStore keyStore, String alias, X509Certificate cert) {
            // If new certificate is RSA then (re)start the HTTPS service
            if ("RSA".equals(cert.getPublicKey().getAlgorithm())) {
                restartServer();
            }
        }

        public void certificateDeleted(KeyStore keyStore, String alias) {
                restartServer();
        }

        public void certificateSigned(KeyStore keyStore, String alias,
                                      List<X509Certificate> certificates) {
            // If new certificate is RSA then (re)start the HTTPS service
            if ("RSA".equals(certificates.get(0).getPublicKey().getAlgorithm())) {
                restartServer();
            }
        }
    }
}
