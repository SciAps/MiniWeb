package com.devsmart.miniweb;

import com.devsmart.miniweb.handlers.ReflectionControllerRequestHandler;
import com.devsmart.miniweb.handlers.controller.Body;
import com.devsmart.miniweb.handlers.controller.Controller;
import com.devsmart.miniweb.handlers.controller.RequestMapping;
import com.devsmart.miniweb.handlers.controller.RequestParam;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonWriter;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.util.EntityUtils;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Requests a client gets wrong, each of which must end in a completed 400 rather than a 500, a hang or a drop. */
public class ControllerInvokerErrorTest {

    @Controller("bad/")
    public static class ErrorController {

        @RequestMapping(value = "count")
        public void count(HttpResponse response, @RequestParam("apples") int apples) {
            response.setEntity(new StringEntity("apples: " + apples, ContentType.TEXT_PLAIN));
        }

        @RequestMapping(value = "text")
        public void text(HttpResponse response, @RequestParam("name") String name) {
            response.setEntity(new StringEntity("name: " + name, ContentType.TEXT_PLAIN));
        }

        @RequestMapping(value = "stream")
        public void stream(JsonWriter writer, @RequestParam("name") String name) throws IOException {
            writer.beginObject();
            writer.name("name");
            writer.value(name);
            writer.endObject();
            writer.close();
        }

        @RequestMapping(value = "rejects")
        @Body
        public String rejects(HttpResponse response) {
            response.setStatusCode(HttpStatus.SC_BAD_REQUEST);
            response.setEntity(new StringEntity("own reason", ContentType.TEXT_PLAIN));
            return "never serialized";
        }

        @RequestMapping(value = "echo")
        @Body
        public String echo(@RequestParam("name") String name) {
            return name;
        }

        @RequestMapping(value = "rejectsWithWriter")
        @Body
        public String rejectsWithWriter(JsonWriter writer, HttpResponse response) {
            response.setStatusCode(HttpStatus.SC_BAD_REQUEST);
            return "error value";
        }
    }

    private static HttpResponse send(String uri) throws Exception {
        final ReflectionControllerRequestHandler handler = new ControllerBuilder(new GsonBuilder().create(), true)
                .addController(new ErrorController())
                .withPathPrefix("/")
                .create();
        final HttpResponse response =
                new BasicHttpResponse(new BasicStatusLine(HttpVersion.HTTP_1_1, HttpStatus.SC_OK, ""));
        handler.handle(new BasicHttpRequest("GET", uri), response, HttpCoreContext.create());
        return response;
    }

    private static void assertBadRequest(HttpResponse response, String reason) throws IOException {
        assertEquals(HttpStatus.SC_BAD_REQUEST, response.getStatusLine().getStatusCode());
        final String body = EntityUtils.toString(response.getEntity());
        assertTrue("'" + body + "' should contain '" + reason + "'", body.contains(reason));
    }

    /** Parsed outside the invoker's try, a NumberFormatException once escaped and dropped the connection. */
    @Test
    public void aNonNumericPrimitiveIsABadRequest() throws Exception {
        assertBadRequest(send("/bad/count?apples=abc"), "malformed parameter 'apples'");
    }

    /** Method.invoke cannot pass null for a primitive; it once surfaced as a 500. */
    @Test
    public void aMissingPrimitiveIsABadRequest() throws Exception {
        assertBadRequest(send("/bad/count"), "missing required parameter 'apples'");
    }

    /** The query parser sets 400 itself; the invoker once went on to invoke and a 500 replaced it. */
    @Test
    public void aMalformedQueryStringIsABadRequest() throws Exception {
        assertBadRequest(send("/bad/text?name=%ZZ"), "malformed query string");
    }

    /**
     * A JsonWriter param installs a pipe only the handler closes. Rejected before the handler runs, that pipe once
     * stayed as the entity and reading the response blocked forever - hence the timeout.
     */
    @Test(timeout = 5000)
    public void aRejectedRequestDoesNotLeaveAJsonWriterPipeOpen() throws Exception {
        assertBadRequest(send("/bad/stream?name=%ZZ"), "malformed query string");
    }

    /** An @Body handler's own error body once lost to the serialized return value. */
    @Test
    public void anErrorBodyTheHandlerSetIsKept() throws Exception {
        assertBadRequest(send("/bad/rejects"), "own reason");
    }

    /**
     * The JsonWriter's pipe is not a body the handler chose. Taken for one, serialization was skipped and reading the
     * response blocked on the open pipe - hence the timeout.
     */
    @Test(timeout = 5000)
    public void anUntouchedJsonWriterPipeIsReplacedBySerialization() throws Exception {
        final HttpResponse response = send("/bad/rejectsWithWriter");

        assertEquals(HttpStatus.SC_BAD_REQUEST, response.getStatusLine().getStatusCode());
        assertEquals("\"error value\"", EntityUtils.toString(response.getEntity()));
    }

    @Test
    public void aSuccessfulBodyIsStillSerialized() throws Exception {
        final HttpResponse response = send("/bad/echo?name=hi");

        assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
        assertEquals("\"hi\"", EntityUtils.toString(response.getEntity()));
    }
}
