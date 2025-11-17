package com.devsmart.miniweb;

import org.apache.http.ConnectionClosedException;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.DefaultHttpServerConnection;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.protocol.HttpRequestHandlerResolver;
import org.apache.http.protocol.HttpService;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

public class Server {

    private static final Logger LOGGER = LoggerFactory.getLogger(Server.class);

    int port;
    boolean isAdvancedLoggingEnabled;
    boolean mIsDebugBuild;
    HttpRequestHandlerResolver requestHandlerResolver;

    private static final ExecutorService mWorkerThreads = Executors.newCachedThreadPool();
    public static final String ORIGIN = "origin";

    private ServerSocket mServerSocket;
    private SocketListener mListenThread;
    private boolean mRunning = false;
    private SSLContext mSslContext;
    private boolean mSslEnabled = false;

    public void configureSslContext(KeyManager[] keyManagers, TrustManager[] trustManagers) throws Exception {
        mSslContext = SSLContext.getInstance("TLS");
        mSslContext.init(keyManagers, trustManagers, null);
        mSslEnabled = true;
    }

    public void start() throws IOException {
        if (mRunning) {
            LOGGER.warn("server already running");
            return;
        }

        if (mSslEnabled && mSslContext != null) {
            SSLServerSocketFactory factory = mSslContext.getServerSocketFactory();
            mServerSocket = factory.createServerSocket();
            ((SSLServerSocket) mServerSocket).setNeedClientAuth(true);
        } else {
            mServerSocket = new ServerSocket(port);
        }

        mServerSocket.setReuseAddress(true);
        mServerSocket.bind(new InetSocketAddress(port));
        if (mIsDebugBuild) {
            LOGGER.info("Server started listening on {}", mServerSocket.getLocalSocketAddress());
        }

        mRunning = true;

        mListenThread = new SocketListener();
        mListenThread.setName("MiniWeb Listen " + mServerSocket.getLocalSocketAddress());
        mListenThread.start();
    }

    public void shutdown() {
        if (mRunning) {
            mRunning = false;
            try {
                mServerSocket.close();
            } catch (IOException e) {
                LOGGER.error("Could not close socket:", e);
            }
            try {
                mListenThread.join();
                mListenThread = null;
            } catch (InterruptedException e) {
                LOGGER.error("", e);
            }
            LOGGER.info("Server shutdown");
        }
    }

    private synchronized void fallbackToHttp() {
        try {
            if (mServerSocket != null && !mServerSocket.isClosed()) {
                mServerSocket.close();
            }
        } catch (IOException e) {
            LOGGER.error("Error closing SSL server socket during fallback", e);
        }
        mSslEnabled = false;
        try {
            ServerSocket plain = new ServerSocket(); // unbound
            plain.setReuseAddress(true);
            plain.bind(new InetSocketAddress(port));
            mServerSocket = plain;
            LOGGER.info("Reverted to plain HTTP on port {}", port);
        } catch (IOException e) {
            LOGGER.error("Failed to create plain HTTP ServerSocket after SSL fallback", e);
            mRunning = false;
        }
    }

    private class WorkerTask implements Runnable {

        private final HttpService httpservice;
        private final RemoteConnection remoteConnection;

        public WorkerTask(HttpService service, RemoteConnection connection) {
            httpservice = service;
            remoteConnection = connection;
        }

        @Override
        public void run() {
            try {
                while(mRunning && remoteConnection.connection.isOpen()) {
                    BasicHttpContext context = new BasicHttpContext();
                    context.setAttribute(ORIGIN, remoteConnection.remoteAddress);
                    httpservice.handleRequest(remoteConnection.connection, context);
                    if (isAdvancedLoggingEnabled
                            && context.getAttribute(HttpCoreContext.HTTP_REQUEST) instanceof HttpRequest) {
                        HttpRequest request = (HttpRequest) context.getAttribute(HttpCoreContext.HTTP_REQUEST);
                        LOGGER.info("Handled request: {}", request.getRequestLine().getUri());
                    }
                }
            } catch (ConnectionClosedException e) {
                LOGGER.info("Client closed connection {}", remoteConnection.connection);
            } catch (IOException e) {
                LOGGER.warn("IO error - {}: {}", remoteConnection.connection, e.getMessage());
            } catch (HttpException e) {
                LOGGER.warn("Unrecoverable HTTP protocol violation - {}: {}", remoteConnection.connection, e.getMessage());
            } catch (Exception e){
                LOGGER.warn("unknown error - {}", remoteConnection.connection, e);
            } finally {
                LOGGER.info("Closing connection {}", remoteConnection.connection);
                try {
                    // Try a graceful shutdown first
                    try {
                        remoteConnection.connection.shutdown();
                    } catch (UnsupportedOperationException uoe) {
                        // SSLSocket may not support half-close; ignore
                    }
                } catch (IOException ignore) {}
                try {
                    remoteConnection.connection.close();
                } catch (UnsupportedOperationException uoe) {
                    // Ignore half-close attempts on SSLSocket
                } catch (IOException e) {
                    LOGGER.error("Error closing connection", e);
                }
            }

        }
    }

    private class SocketListener extends Thread {

        @Override
        public void run() {
            // Set up the HTTP protocol processor
            BasicHttpProcessor httpProcessor = new BasicHttpProcessor();
            httpProcessor.addResponseInterceptor(new ResponseDate());
            httpProcessor.addResponseInterceptor(new ResponseServer());
            httpProcessor.addResponseInterceptor(new ResponseContent());
            httpProcessor.addResponseInterceptor(new ResponseConnControl());

            DefaultHttpResponseFactory responseFactory = new DefaultHttpResponseFactory();
            HttpParams params = new BasicHttpParams();
            HttpService httpService = new HttpService(httpProcessor, new DefaultConnectionReuseStrategy(), responseFactory);
            httpService.setHandlerResolver(requestHandlerResolver);
            httpService.setParams(params);

            Socket rawSocket = null;
            while (mRunning) {
                try {
                    if (mIsDebugBuild) {
                        LOGGER.info("waiting in accept on {}", mServerSocket.getLocalSocketAddress());
                    }
                    rawSocket = mServerSocket.accept();
                    if (mIsDebugBuild) {
                        LOGGER.info("accepting connection from: {}", rawSocket.getRemoteSocketAddress());
                    }
                    Socket boundSocket = rawSocket;

                    // If SSL is enabled, ensure we have a SSLSocket and complete the handshake
                    if (mSslEnabled && (mServerSocket instanceof SSLServerSocket)) {
                        SSLSocket sslSocket = (SSLSocket) rawSocket;
                        sslSocket.setUseClientMode(false);
                        sslSocket.setEnabledProtocols(new String[]{"TLSv1.2"});
                        sslSocket.startHandshake();
                    }

                    DefaultHttpServerConnection connection = new DefaultHttpServerConnection();
                    connection.bind(boundSocket, new BasicHttpParams());
                    RemoteConnection remoteConnection = new RemoteConnection(boundSocket.getInetAddress(), connection);

                    mWorkerThreads.execute(new WorkerTask(httpService, remoteConnection));
                } catch (SSLHandshakeException e) {
                    LOGGER.error("SSL Handshake failed: {}. Reverting to HTTP.", e.getMessage());
                    try {
                        rawSocket.close();
                        LOGGER.info("Connection is closed properly");
                    } catch (IOException ioException) {
                        LOGGER.error("Can't close connection. Reason: ", ioException);
                    }
                    fallbackToHttp();
                } catch (SocketTimeoutException e) {
                    continue;
                } catch (SocketException e) {
                    LOGGER.info("SocketListener shutting down");
                    mRunning = false;
                } catch (IOException e) {
                    LOGGER.error("I/O error in accept loop", e);
                    mRunning = false;
                } catch (ClassCastException e) {
                    LOGGER.error("Expected SSLSocket when SSL is enabled; check server socket setup", e);
                    mRunning = false;
                }
            }
            if (rawSocket != null) {
                try {
                    rawSocket.close();
                    LOGGER.info("Connection is closed properly");
                } catch (IOException e) {
                    LOGGER.error("Can't close connection. Reason: ", e);
                }
            }
        }
    }
}
