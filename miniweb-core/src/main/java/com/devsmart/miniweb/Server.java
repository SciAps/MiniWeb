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

public class Server {

    private static final Logger LOGGER = LoggerFactory.getLogger(Server.class);

    int port;
    boolean isAdvancedLoggingEnabled;
    HttpRequestHandlerResolver requestHandlerResolver;

    private static final ExecutorService mWorkerThreads = Executors.newCachedThreadPool();
    public static final String ORIGIN = "origin";

    private ServerSocket mServerSocket;
    private SocketListener mListenThread;
    private boolean mRunning = false;

    public void start() throws IOException {
        if (mRunning) {
            LOGGER.warn("server already running");
            return;
        }

        mServerSocket = new ServerSocket();
        mServerSocket.setReuseAddress(true);
        mServerSocket.bind(new InetSocketAddress(port));
        LOGGER.info("Server started listening on {}", mServerSocket.getLocalSocketAddress());

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
                closeConnection();
            }
        }

        public void closeConnection() {
            try {
                remoteConnection.connection.close();
            } catch (IOException e){
                LOGGER.error("", e);
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
                    LOGGER.info("waiting in accept on {}", mServerSocket.getLocalSocketAddress());
                    socket = mServerSocket.accept();
                    LOGGER.info("accepting connection from: {}", socket.getRemoteSocketAddress());

                    DefaultHttpServerConnection connection = new DefaultHttpServerConnection();
                    connection.bind(socket, new BasicHttpParams());
                    RemoteConnection remoteConnection = new RemoteConnection(socket.getInetAddress(), connection);

                    mWorkerThreads.execute(new WorkerTask(httpService, remoteConnection));
                } catch (SocketTimeoutException e) {
                    continue;
                } catch (SocketException e) {
                    LOGGER.info("SocketListener shutting down");
                    mRunning = false;
                } catch (IOException e) {
                    LOGGER.error("", e);
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
