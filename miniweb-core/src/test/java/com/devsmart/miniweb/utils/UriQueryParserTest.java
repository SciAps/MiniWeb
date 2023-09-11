package com.devsmart.miniweb.utils;

import org.junit.Test;
import static org.junit.Assert.*;

import java.net.ProtocolException;
import java.util.List;
import java.util.Map;


public class UriQueryParserTest {

    @Test
    public void test() {

        String uri = "/stuff/test?param1=itworked&param2=rad&param1=cool";
        boolean exceptionHappened = false;

        Map<String, List<String>> params = null;
        try {
            params = UriQueryParser.getUrlParameters(uri);
        } catch (ProtocolException e) {
            exceptionHappened = true;
        }

        assertNotNull(params);
        assertNotEquals(exceptionHappened, true);
        assertEquals(2, params.get("param1").size());
        assertEquals("itworked", params.get("param1").get(0));
        assertEquals("cool", params.get("param1").get(1));

        assertEquals(1, params.get("param2").size());
        assertEquals("rad", params.get("param2").get(0));

        uri = "/stuff/test?param1=itworked&param2=rad&param1=%cool";
        try {
            params = UriQueryParser.getUrlParameters(uri);
        } catch (ProtocolException e) {
            exceptionHappened = true;
        }
        assertTrue(exceptionHappened);
    }
}
