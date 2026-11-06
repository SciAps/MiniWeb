package com.devsmart.miniweb.handlers.controller;


import com.devsmart.miniweb.utils.RequestMethod;
import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;

import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

public class ControllerInvoker implements HttpRequestHandler {

    public final Logger logger = LoggerFactory.getLogger(ControllerInvoker.class);

    public PathVarCapture pathEndpoint;
    public Object instance;
    public Method method;
    public ParamHandler[] paramHandlers;
    public boolean serializeRetval;
    private Gson mGson;
    public RequestMethod requestMethod;
    public String pathPrefix;

    public ControllerInvoker(Gson gson) {
        mGson = gson;
    }

    private Gson getGson() {
        return mGson;
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
        final Object[] params = new Object[paramHandlers.length];
        final Class<?>[] paramTypes = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            try {
                params[i] = paramHandlers[i].createParam(request, response, context, this);
            } catch (final NumberFormatException e) {
                respondBadRequest(response, "malformed parameter " + paramName(i) + ": " + e.getMessage());
                return;
            }
            // A param handler that already rejected the request (e.g. an unparseable query string).
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_BAD_REQUEST) {
                respondBadRequest(response, "malformed query string");
                return;
            }
            // Method.invoke cannot pass null for a primitive; it would surface as a 500.
            if (params[i] == null && paramTypes[i].isPrimitive()) {
                respondBadRequest(response, "missing required parameter " + paramName(i));
                return;
            }
        }

        try {
            Object retval = method.invoke(instance, params);
            final boolean hasOwnErrorBody = response.getStatusLine().getStatusCode() >= HttpStatus.SC_BAD_REQUEST
                    && response.getEntity() != null;
            if (serializeRetval && !hasOwnErrorBody) {
                Gson gson = getGson();
                String respStr = gson.toJson(retval);
                StringEntity respEntity = new StringEntity(respStr, HTTP.UTF_8);
                respEntity.setContentType("application/json");
                response.setEntity(respEntity);
            }
        } catch (Exception e) {
            logger.error("error invoking controller method: {}:{}", instance.getClass().getName(), method.getName(), e);
            throw new HttpException();
        }
    }

    private void respondBadRequest(final HttpResponse response, final String message) {
        logger.warn("{}:{} rejected: {}", instance.getClass().getName(), method.getName(), message);
        response.setStatusCode(HttpStatus.SC_BAD_REQUEST);
        response.setEntity(new StringEntity(message, ContentType.TEXT_PLAIN));
    }

    private String paramName(final int index) {
        for (final Annotation annotation : method.getParameterAnnotations()[index]) {
            if (annotation instanceof RequestParam) {
                return "'" + ((RequestParam) annotation).value() + "'";
            }
            if (annotation instanceof PathVariable) {
                return "'" + ((PathVariable) annotation).value() + "'";
            }
        }
        return "#" + index;
    }

    public JsonWriter createJsonWriter(HttpResponse response) {
        PipedOutputStream out = new PipedOutputStream();
        final JsonWriter jsonWriter = new JsonWriter(new OutputStreamWriter(out));

        try {
            BasicHttpEntity entity = new BasicHttpEntity();
            entity.setContentType("application/json");
            entity.setContent(new PipedInputStream(out));
            response.setEntity(entity);
        } catch (IOException e){
            logger.error("error sending json response: {}", e);
        }

        return jsonWriter;
    }



}
