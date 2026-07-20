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
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
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
    private final Set<RemoteConnection> mActiveConnections = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile boolean mRunning = false;
    private SSLContext mSslContext;
    private boolean mSslEnabled = false;

    public void configureSslContext(KeyManager[] keyManagers, TrustManager[] trustManagers)  {
        try {
            mSslContext = SSLContext.getInstance("TLS");
            mSslContext.init(keyManagers, trustManagers, null);
            mSslEnabled = true;
        } catch (Exception e) {
            mSslContext = null;
            mSslEnabled = false;
            throw new IllegalStateException("Could not initialize SSLContext", e);
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
        } else {
            ServerSocketFactory factory = ServerSocketFactory.getDefault();
            mServerSocket = factory.createServerSocket();
        }

        mServerSocket.setReuseAddress(true);
        mServerSocket.bind(new InetSocketAddress(port));

        if (mSslEnabled && mServerSocket instanceof SSLServerSocket) {
            SSLServerSocket sslServerSocket = (SSLServerSocket) mServerSocket;
            sslServerSocket.setNeedClientAuth(true);
            String[] supported = sslServerSocket.getSupportedProtocols();
            String[] desired = new String[]{"TLSv1.3", "TLSv1.2"};
            String[] filtered = Arrays.stream(desired)
                                      .filter(p -> Arrays.asList(supported).contains(p))
                                      .toArray(String[]::new);
            if (filtered.length > 0) {
                sslServerSocket.setEnabledProtocols(filtered);
            }
            LOGGER.info("SSL server socket configured: clientAuth=required, protocols={}",
                        Arrays.toString(sslServerSocket.getEnabledProtocols()));
        }

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
            // Force-close accepted connections: keep-alive clients hold sockets open with a
            // worker thread blocked in handleRequest(), and would otherwise get one more
            // response served with this server's stale routes after shutdown.
            for (RemoteConnection remoteConnection : mActiveConnections) {
                try {
                    remoteConnection.connection.shutdown();
                } catch (IOException e) {
                    LOGGER.warn("Error shutting down connection {}: {}", remoteConnection.connection, e.getMessage());
                }
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
                mActiveConnections.remove(remoteConnection);
                LOGGER.info("Closing connection {}", remoteConnection.connection);
                try {
                    if (remoteConnection.connection.isOpen()) {
                        remoteConnection.connection.close();
                    }
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
                    SSLSafeHttpServerConnection connection = new SSLSafeHttpServerConnection();
                    connection.bind(socket, new BasicHttpParams());
                    RemoteConnection remoteConnection = new RemoteConnection(socket.getInetAddress(), connection);

                    mActiveConnections.add(remoteConnection);
                    mWorkerThreads.execute(new WorkerTask(httpService, remoteConnection));
                } catch (SocketTimeoutException e) {
                    // ignore and continue
                } catch (SSLException e) {
                    LOGGER.warn("TLS handshake failed from {}: {}",
                                socket != null ? socket.getRemoteSocketAddress() : "unknown", e.getMessage());
                    closeSocket(socket);
                } catch (SocketException e) {
                    LOGGER.info("SocketListener shutting down");
                    mRunning = false;
                } catch (IOException e) {
                    LOGGER.error("I/O error", e);
                    mRunning = false;
                } catch (ClassCastException e) {
                    LOGGER.error("Expected SSLSocket when SSL is enabled; check server socket setup", e);
                    mRunning = false;
                }
            }
            closeSocket(socket);
        }
    }

    private static void closeSocket(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
                LOGGER.info("Connection is closed properly");
            } catch (IOException e) {
                LOGGER.error("Can't close connection. Reason: ", e);
            }
        }
    }


    public static class SSLSafeHttpServerConnection extends DefaultHttpServerConnection {
        @Override
        public void close() throws IOException {
            try {
                // This attempts to call shutdownOutput(), which fails on Android SSL
                super.close();
            } catch (UnsupportedOperationException e) {
                // Catch the specific Android error
                // verify the socket exists and force-close it to prevent leaks
                if (getSocket() != null) {
                    getSocket().close();
                }
            }
        }
    }
}
