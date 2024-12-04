package org.apache.catalina.servlets;

import static org.apache.catalina.startup.SimpleHttpClient.CRLF;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import jakarta.servlet.http.HttpServletResponse;

import org.junit.Assert;
import org.junit.Test;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.SimpleHttpClient;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.startup.TomcatBaseTest;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.http.FastHttpDateFormat;

public class TestDefaultServletConditionalRequests extends TomcatBaseTest {

    @Test
    public void testPreconditions_rfc9110_13_2_2_1_head0() throws Exception {
        startServer(true);
        testPreconditions(Task.HEAD_INDEX_HTML, IfPolicy.ETAG_ALL, null, null, null, null, 200);
        testPreconditions(Task.HEAD_INDEX_HTML, IfPolicy.ETAG_EXACTLY, null, null, null, null, 200);
        testPreconditions(Task.HEAD_INDEX_HTML, IfPolicy.ETAG_IN, null, null, null, null, 200);
        testPreconditions(Task.HEAD_INDEX_HTML, IfPolicy.ETAG_NOT_IN, null, null, null, null, 412);
    }

    @Test
    public void testPreconditions_rfc9110_13_2_2_1_head1() throws Exception {
        startServer(false);
        testPreconditions(Task.HEAD_INDEX_HTML, IfPolicy.ETAG_ALL, null, null, null, null, 200);
        testPreconditions(Task.HEAD_INDEX_HTML, IfPolicy.ETAG_EXACTLY, null, null, null, null, 412);
        testPreconditions(Task.HEAD_INDEX_HTML, IfPolicy.ETAG_IN, null, null, null, null, 412);
        testPreconditions(Task.HEAD_INDEX_HTML, IfPolicy.ETAG_NOT_IN, null, null, null, null, 412);
    }

    @Test
    public void testPreconditions_rfc9110_13_2_2_1_get0() throws Exception {
        startServer(true);
        testPreconditions(Task.GET_INDEX_HTML, IfPolicy.ETAG_ALL, null, null, null, null, 200);
    }

    @Test
    public void testPreconditions_rfc9110_13_2_2_1_get1() throws Exception {
        startServer(false);
        testPreconditions(Task.GET_INDEX_HTML, IfPolicy.ETAG_ALL, null, null, null, null, 200);
        testPreconditions(Task.GET_INDEX_HTML, IfPolicy.ETAG_EXACTLY, null, null, null, null, 412);
    }

    @Test
    public void testPreconditions_rfc9110_13_2_2_1_post0() throws Exception {
        startServer(true);
        testPreconditions(Task.POST_INDEX_HTML, IfPolicy.ETAG_ALL, null, null, null, null, 200);
    }

    @Test
    public void testPreconditions_rfc9110_13_2_2_1_post1() throws Exception {
        startServer(false);
        testPreconditions(Task.POST_INDEX_HTML, IfPolicy.ETAG_ALL, null, null, null, null, 200);
        testPreconditions(Task.POST_INDEX_HTML, IfPolicy.ETAG_EXACTLY, null, null, null, null, 412);
    }

    @Test
    public void testPreconditions_rfc9110_13_2_2_1_put0() throws Exception {
        startServer(true);
        testPreconditions(Task.PUT_EXIST_TXT, IfPolicy.ETAG_ALL, null, null, null, null,
                HttpServletResponse.SC_NO_CONTENT);
        testPreconditions(Task.PUT_EXIST_TXT, IfPolicy.ETAG_IN, null, null, null, null,
                HttpServletResponse.SC_NO_CONTENT);
        testPreconditions(Task.PUT_EXIST_TXT, IfPolicy.ETAG_NOT_IN, null, null, null, null, 412);
        testPreconditions(Task.PUT_NEW_TXT, null, null, null, null, null, HttpServletResponse.SC_CREATED);
    }

    @Test
    public void testPreconditions_rfc9110_13_2_2_1_put1() throws Exception {
        startServer(false);
        testPreconditions(Task.PUT_EXIST_TXT, IfPolicy.ETAG_ALL, null, null, null, null,
                HttpServletResponse.SC_NO_CONTENT);
        testPreconditions(Task.PUT_EXIST_TXT, IfPolicy.ETAG_EXACTLY, null, null, null, null, 412);
    }


    enum HTTP_METHOD {
        GET,
        PUT,
        DELETE,
        POST,
        HEAD
    }

    enum Task {
        HEAD_INDEX_HTML(HTTP_METHOD.HEAD, "/index.html"),
        HEAD_404_HTML(HTTP_METHOD.HEAD, "/sc_404.html"),

        GET_INDEX_HTML(HTTP_METHOD.GET, "/index.html"),
        GET_404_HTML(HTTP_METHOD.GET, "/sc_404.html"),

        POST_INDEX_HTML(HTTP_METHOD.POST, "/index.html"),
        POST_404_HTML(HTTP_METHOD.POST, "/sc_404.html"),

        PUT_EXIST_TXT(HTTP_METHOD.PUT, "/put_exist.txt"),
        PUT_NEW_TXT(HTTP_METHOD.PUT, "/put_new.txt"),

        DELETE_NAME_TXT(HTTP_METHOD.DELETE, "/delete_exist.txt"),
        DELETE_NOT_EXIST_TXT(HTTP_METHOD.DELETE, "/delete_404.txt");

        HTTP_METHOD m;
        String uri;

        Task(HTTP_METHOD m, String uri) {
            this.m = m;
            this.uri = uri;
        }

        @Override
        public String toString() {
            return m.name() + " " + uri;
        }
    }

    enum IfPolicy {
        ETAG_EXACTLY,
        ETAG_IN,
        ETAG_ALL,
        ETAG_NOT_IN,
        DATE_EQ,
        DATE_GE,
        DATE_GT,
        DATE_LE,
        DATE_LT;
    }

    enum IfType {
        ifMatch("If-Match"), // ETag strong comparison
        ifUnmodifiedSince("If-Unmodified-Since"),
        ifNoneMatch("If-None-Match"), // ETag weak comparison
        ifModifiedSince("If-Modified-Since"),
        ifRange("If-Range"); // ETag strong comparison

        private String header;

        IfType(String header) {
            this.header = header;
        }

        public String value() {
            return this.header;
        }
    }

    protected List<String> genETagCondtion(String strongETag, String weakETag, IfPolicy policy) {
        List<String> headerValues = new ArrayList<String>();
        switch (policy) {
            case ETAG_ALL:
                headerValues.add("*");
                break;
            case ETAG_EXACTLY:
                if (strongETag != null) {
                    headerValues.add(strongETag);
                } else {
                    // Should not happen
                    throw new IllegalArgumentException("strong etag not found!");
                }
                break;
            case ETAG_IN:
                headerValues.add("\"1a2b3c4d\"");
                headerValues.add(weakETag + "," + strongETag + ",W/\"*\"");
                break;
            case ETAG_NOT_IN:
                headerValues.add("W/" + strongETag + "");
                if (strongETag != null && strongETag.length() > 6) {
                    headerValues.add(strongETag.substring(0, 3) + strongETag.substring(5));
                }
                break;
            default:
                break;
        }
        return headerValues;
    }

    protected List<String> genETagCondtion(long lastModifiedTimestamp, IfPolicy policy) {
        List<String> headerValues = new ArrayList<String>();
        if (lastModifiedTimestamp <= 0) {
            return headerValues;
        }
        switch (policy) {
            case DATE_EQ:
                headerValues.add(FastHttpDateFormat.formatDate(lastModifiedTimestamp));
                break;
            case DATE_GE:
                headerValues.add(FastHttpDateFormat.formatDate(lastModifiedTimestamp));
                headerValues.add(FastHttpDateFormat.formatDate(lastModifiedTimestamp + 30000L));
                break;
            case DATE_GT:
                headerValues.add(FastHttpDateFormat.formatDate(lastModifiedTimestamp + 30000L));
                headerValues.add(FastHttpDateFormat.formatDate(lastModifiedTimestamp + 60000L));
                break;
            case DATE_LE:
                headerValues.add(FastHttpDateFormat.formatDate(lastModifiedTimestamp));
                headerValues.add(FastHttpDateFormat.formatDate(lastModifiedTimestamp - 30000L));
                break;
            case DATE_LT:
                headerValues.add(FastHttpDateFormat.formatDate(lastModifiedTimestamp - 30000L));
                headerValues.add(FastHttpDateFormat.formatDate(lastModifiedTimestamp - 60000L));
                break;
            default:
                break;
        }
        return headerValues;
    }

    protected void wrapperHeaders(Map<String,List<String>> headers, String resourceETag, long lastModified,
            IfPolicy policy, IfType type) {
        Objects.requireNonNull(type);
        if (policy == null) {
            return;
        }
        List<String> headerValues = new ArrayList<String>();
        String weakETag = resourceETag;
        String strongETag = resourceETag;
        if (resourceETag != null) {
            if (resourceETag.startsWith("W/")) {
                strongETag = resourceETag.substring(2);
            } else {
                weakETag = "W/" + resourceETag;
            }
        }

        List<String> eTagConditions = genETagCondtion(strongETag, weakETag, policy);
        if (!eTagConditions.isEmpty()) {
            headerValues.addAll(eTagConditions);
        }

        if (!headerValues.isEmpty()) {
            headers.put(type.value(), headerValues);
        }
    }

    private File tempDocBase = null;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        tempDocBase = Files.createTempDirectory(getTemporaryDirectory().toPath(), "conditional").toFile();
        Files.write(Path.of(tempDocBase.getAbsolutePath(), "index.html"), "<html><body>Index</body></html>".getBytes(),
                StandardOpenOption.CREATE);
        Path.of(tempDocBase.getAbsolutePath(), "index.html").toFile()
                .setLastModified(System.currentTimeMillis() - 3600L * 1000L * 24 * 100); // 30 days ago

        Files.write(Path.of(tempDocBase.getAbsolutePath(), "put_exist.txt"), "put_exist_v0".getBytes(),
                StandardOpenOption.CREATE);
        Path.of(tempDocBase.getAbsolutePath(), "put_exist.txt").toFile()
                .setLastModified(System.currentTimeMillis() - 3600L * 1000L * 24 * 30); // 30 days ago

        Files.write(Path.of(tempDocBase.getAbsolutePath(), "delete_exist.txt"), "delete_exist_v0".getBytes(),
                StandardOpenOption.CREATE);
        Path.of(tempDocBase.getAbsolutePath(), "delete_exist.txt").toFile()
                .setLastModified(System.currentTimeMillis() - 3600L * 1000L * 24 * 7); // 7 days ago

    }

    protected void startServer(boolean resourceHasStrongETag) throws Exception {
        Tomcat tomcat = getTomcatInstance();
        Context ctxt = tomcat.addContext("", tempDocBase.getAbsolutePath());

        Wrapper w = Tomcat.addServlet(ctxt, "default", DefaultServlet.class.getName());
        w.addInitParameter("readonly", "false");
        w.addInitParameter("allowPartialPut", Boolean.toString(true));
        w.addInitParameter("useStrongETags", Boolean.toString(resourceHasStrongETag));
        ctxt.addServletMappingDecoded("/", "default");

        tomcat.start();
    }

    protected void testPreconditions(Task task, IfPolicy ifMatchHeader, IfPolicy ifUnmodifiedSinceHeader,
            IfPolicy ifNoneMatchHeader, IfPolicy ifModifiedSinceHeader, IfPolicy ifRangeHeader, int scExpected)
            throws Exception {
        Assert.assertNotNull(task);


        Map<String,List<String>> requestHeaders = new HashMap<>();

        Map<String,List<String>> responseHeaders = new HashMap<>();

        String etag = null;
        long lastModified = -1;
        String uri = "http://localhost:" + getPort() + task.uri;
        // Try head to receives etag and lastModified Date
        int sc = headUrl(uri, new ByteChunk(), responseHeaders);
        if (sc == 200) {
            etag = getSingleHeader("ETag", responseHeaders);
            String dt = getSingleHeader("last-modified", responseHeaders);
            if (dt != null && dt.length() > 0) {
                lastModified = FastHttpDateFormat.parseDate(dt);
            }
        }

        wrapperHeaders(requestHeaders, etag, lastModified, ifMatchHeader, IfType.ifMatch);
        wrapperHeaders(requestHeaders, etag, lastModified, ifModifiedSinceHeader, IfType.ifModifiedSince);
        wrapperHeaders(requestHeaders, etag, lastModified, ifNoneMatchHeader, IfType.ifNoneMatch);
        wrapperHeaders(requestHeaders, etag, lastModified, ifUnmodifiedSinceHeader, IfType.ifUnmodifiedSince);
        wrapperHeaders(requestHeaders, etag, lastModified, ifRangeHeader, IfType.ifRange);

        responseHeaders.clear();
        sc = 0;
        ByteChunk out = new ByteChunk();
        SimpleHttpClient client = null;
        if (task.m == HTTP_METHOD.PUT) {
            client = new SimpleHttpClient() {

                @Override
                public boolean isResponseBodyOK() {
                    return true;
                }
            };
            client.setPort(getPort());
            StringBuffer putCmd = new StringBuffer();
            putCmd.append(
                    "PUT " + task.uri + " HTTP/1.1" + CRLF + "Host: localhost" + CRLF + "Connection: Close" + CRLF);

            for (Entry<String,List<String>> e : requestHeaders.entrySet()) {
                for (String v : e.getValue()) {
                    putCmd.append(e.getKey() + ": " + v + CRLF);
                }
            }
            putCmd.append("Content-Length: 6" + CRLF);
            putCmd.append(CRLF);

            putCmd.append("PUT_v2");
            client.setRequest(new String[] { putCmd.toString() });
            client.connect();
            client.processRequest();
            sc = client.getStatusCode();
        } else {
            sc = methodUrl(uri, out, DEFAULT_CLIENT_TIMEOUT_MS, requestHeaders, responseHeaders, task.m.name());
        }
        assertEquals("Failure - %s, req headers: %s, resp headers: %s".formatted(task, requestHeaders.toString(),
                responseHeaders.toString()), scExpected, sc);
    }
    
}
