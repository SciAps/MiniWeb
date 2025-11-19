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
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ServerSocketFactory;
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

    public void configureSslContext(KeyManager[] keyManagers, TrustManager[] trustManagers)  {
        try {
            mSslContext = SSLContext.getInstance("TLS");
            mSslContext.init(keyManagers, trustManagers, null);
            mSslEnabled = true;
        } catch (Exception e) {
            LOGGER.error("Could not initialize SSLContext:", e);
            mSslContext = null;
            mSslEnabled = false;
        }
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
            ServerSocketFactory factory = ServerSocketFactory.getDefault();
            mServerSocket = factory.createServerSocket();
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
            } catch (InterruptedException e) {
                LOGGER.error("Interrupted waiting for listener thread shutdown", e);
                Thread.currentThread().interrupt();
            } finally {
                mListenThread = null;
            }
            LOGGER.info("Server shutdown");
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
                    remoteConnection.connection.shutdown();
                } catch (UnsupportedOperationException | IOException e) {
                    LOGGER.error("Error shutting down connection", e);
                }
                try {
                    remoteConnection.connection.close();
                } catch (UnsupportedOperationException | IOException e) {
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

            Socket socket = null;
            while (mRunning) {
                try {
                    if (mIsDebugBuild) {
                        LOGGER.info("waiting in accept on {}", mServerSocket.getLocalSocketAddress());
                    }
                    socket = mServerSocket.accept();
                    if (mIsDebugBuild) {
                        LOGGER.info("accepting connection from: {}", socket.getRemoteSocketAddress());
                    }
                    // If SSL is enabled, ensure we have a SSLSocket and complete the handshake
                    if (mSslEnabled && (mServerSocket instanceof SSLServerSocket)) {
                        SSLSocket sslSocket = (SSLSocket) socket;
                        sslSocket.setUseClientMode(false);
                        String[] protocols = sslSocket.getEnabledProtocols();
                        String[] desired = new String[]{"TLSv1.3", "TLSv1.2"};
                        sslSocket.setEnabledProtocols(
                                Arrays.stream(desired).filter(p -> Arrays.asList(protocols).contains(p))
                                      .toArray(String[]::new));
                        sslSocket.startHandshake();
                    }

                    DefaultHttpServerConnection connection = new DefaultHttpServerConnection();
                    connection.bind(socket, new BasicHttpParams());
                    RemoteConnection remoteConnection = new RemoteConnection(socket.getInetAddress(), connection);

                    mWorkerThreads.execute(new WorkerTask(httpService, remoteConnection));
                } catch (SocketTimeoutException | SSLHandshakeException e) {
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
            if (socket != null) {
                try {
                    socket.close();
                    LOGGER.info("Connection is closed properly");
                } catch (IOException e) {
                    LOGGER.error("Can't close connection. Reason: ", e);
                }
            }
        }
    }
}
