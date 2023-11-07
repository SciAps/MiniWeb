package com.devsmart.miniweb;



import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.protocol.HttpRequestHandlerResolver;
import com.devsmart.miniweb.handlers.FileSystemRequestHandler;

import java.io.File;

public class ServerBuilder {

    private int mPort = 8080;
    private boolean mIsAdvancedLoggingEnabled;
    private HttpRequestHandlerResolver mRequestHandler;
    private UriRequestHandlerResolver mUriMapper = new UriRequestHandlerResolver();
    private Gson mGson = new GsonBuilder().create();

    public ServerBuilder port(int port) {
        mPort = port;
        return this;
    }

    public ServerBuilder setAdvancedLoggingEnabled(boolean isAdvancedLoggingEnabled) {
        mIsAdvancedLoggingEnabled = isAdvancedLoggingEnabled;
        return this;
    }

    private String trimPattern(String pattern){
        String retval = pattern;
        int i = pattern.lastIndexOf('*');
        if(i > 0){
            retval = pattern.substring(0, i);
        }
        if(retval.endsWith("/")){
            retval = retval.substring(0, retval.length()-1);
        }
        return retval;

    }

    public ServerBuilder setGson(Gson gson) {
        mGson = gson;
        return this;
    }

    public ServerBuilder mapController(String pattern, Object... controllers) {
        ControllerBuilder builder = new ControllerBuilder(mGson);
        builder.withPathPrefix(trimPattern(pattern));
        for(Object controller : controllers){
            builder.addController(controller);
        }
        mUriMapper.register(pattern, builder.create());
        return this;
    }

    public ServerBuilder mapDirectory(String pattern, File fsRoot) {
        mapDirectory(pattern, fsRoot, "");
        return this;
    }

    public ServerBuilder mapDirectory(String pattern, File fsRoot, String prefix) {
        mUriMapper.register(pattern, new FileSystemRequestHandler(fsRoot, prefix));
        return this;
    }

    public ServerBuilder mapHandler(String pattern, HttpRequestHandler handler) {
        mUriMapper.register(pattern, handler);
        return this;
    }

    public Server create() {
        Server server = new Server();
        server.port = mPort;
        server.isAdvancedLoggingEnabled = mIsAdvancedLoggingEnabled;
        if(mRequestHandler == null){
            mRequestHandler = mUriMapper;
        }
        server.requestHandlerResolver = mRequestHandler;

        return server;
    }
}
