/*
 * Copyright 2014 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.overlord.apiman.dt.test.util;

import java.io.InputStream;
import java.net.URI;
import java.text.MessageFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.DefaultHttpClient;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.NumericNode;
import org.codehaus.jackson.node.ObjectNode;
import org.codehaus.jackson.node.TextNode;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.overlord.apiman.dt.test.plan.TestGroupType;
import org.overlord.apiman.dt.test.plan.TestPlan;
import org.overlord.apiman.dt.test.plan.TestType;
import org.overlord.apiman.dt.test.resttest.RestTest;
import org.overlord.apiman.dt.test.server.DtApiTestServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for all junit integration tests for dt api.
 * 
 * @author eric.wittmann@redhat.com
 */
public abstract class AbstractTestPlanTest {

    private static Logger logger = LoggerFactory.getLogger(AbstractTestPlanTest.class);

    private static DtApiTestServer testServer = new DtApiTestServer();

    @BeforeClass
    public static void setup() throws Exception {
        testServer.start();
    }

    /**
     * Called to run a test plan.
     * 
     * @param resourcePath
     * @param cl
     */
    protected void runTestPlan(String resourcePath, ClassLoader cl) {
        TestPlan testPlan = TestUtil.loadTestPlan(resourcePath, cl);
        log("");
        log("-----------------------------------------------------------");
        log("Executing Test Plan: " + resourcePath);
        log("-----------------------------------------------------------");
        log("");
        for (TestGroupType group : testPlan.getTestGroup()) {
            log("----------------------------------");
            log("Starting Test Group [{0}]", group.getName());
            log("----------------------------------");

            for (TestType test : group.getTest()) {
                String rtPath = test.getValue();
                log("Executing REST Test [{0}] - {1}", test.getName(), rtPath);
                RestTest restTest = TestUtil.loadRestTest(rtPath, cl);
                runTest(restTest);
                log("REST Test Completed");
                log("+++++++++++++++++++");
            }

            log("Test Group [{0}] Completed Successfully", group.getName());
        }
        
        log("");
        log("-----------------------------------------------------------");
        log("Test Plan successfully executed: " + resourcePath);
        log("-----------------------------------------------------------");
        log("");
    }

    /**
     * Runs a single REST test.
     * 
     * @param restTest
     */
    private void runTest(RestTest restTest) throws Error {
        try {
            URI uri = new URI(testServer.getUrl(restTest.getRequestPath()));
            HttpRequestBase request = null;
            if (restTest.getRequestMethod().equalsIgnoreCase("GET")) {
                request = new HttpGet();
            } else if (restTest.getRequestMethod().equalsIgnoreCase("POST")) {
                request = new HttpPost();
                HttpEntity entity = new StringEntity(restTest.getRequestPayload());
                ((HttpPost) request).setEntity(entity);
            }
            request.setURI(uri);
            
            Map<String, String> requestHeaders = restTest.getRequestHeaders();
            for (Entry<String, String> entry : requestHeaders.entrySet()) {
                request.setHeader(entry.getKey(), entry.getValue());
            }
            
            DefaultHttpClient client = new DefaultHttpClient();
            BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
            credsProvider.setCredentials(new AuthScope(uri.getHost(), uri.getPort()),
                    new UsernamePasswordCredentials(restTest.getUsername(), restTest.getPassword()));
            client.setCredentialsProvider(credsProvider);
            
            HttpResponse response = client.execute(request);
            assertResponse(restTest, response);
        } catch (Exception e) {
            throw new Error(e);
        }
        
    }

    /**
     * Assert that the response matched the expected.
     * @param restTest
     * @param response
     */
    private void assertResponse(RestTest restTest, HttpResponse response) {
        int actualStatusCode = response.getStatusLine().getStatusCode();
        Assert.assertEquals("Unexpected REST response status code", restTest.getExpectedStatusCode(), actualStatusCode);
        for (Entry<String, String> entry : restTest.getExpectedResponseHeaders().entrySet()) {
            String headerName = entry.getKey();
            String expectedHeaderValue = entry.getValue();
            Header header = response.getFirstHeader(headerName);
            Assert.assertNotNull("Expected header to exist but was not found: " + headerName, header);
            String actualValue = header.getValue();
            Assert.assertEquals(expectedHeaderValue, actualValue);
        }
        String ct = response.getFirstHeader("Content-Type").getValue();
        if (ct.equals("application/json")) {
            assertJsonPayload(restTest, response);
        } else if (ct.equals("text/plain")) {
            assertTextPayload(restTest, response);
        } else {
            Assert.fail("Unsupported response payload type: " + ct);
        }
    }

    /**
     * Assume the payload is JSON and do some assertions based on the configuration
     * in the REST Test.
     * @param restTest
     * @param response
     */
    private void assertJsonPayload(RestTest restTest, HttpResponse response) {
        InputStream inputStream = null;
        try {
            inputStream = response.getEntity().getContent();
            ObjectMapper jacksonParser = new ObjectMapper();
            JsonNode actualJson = jacksonParser.readTree(inputStream);
            JsonNode expectedJson = jacksonParser.readTree(restTest.getExpectedResponsePayload());
            try {
                assertJson(expectedJson, actualJson);
            } catch (Error e) {
                System.out.println("--- START FAILED JSON PAYLOAD ---");
                System.out.println(actualJson.toString());
                System.out.println("--- END FAILED JSON PAYLOAD ---");
                throw e;
            }
        } catch (Exception e) {
            throw new Error(e);
        } finally {
            IOUtils.closeQuietly(inputStream);
        }
    }

    /**
     * Asserts that the JSON payload matches what we expected, as defined
     * in the configuration of the rest test.
     * @param expectedJson
     * @param actualJson
     */
    private void assertJson(JsonNode expectedJson, JsonNode actualJson) {
        Iterator<Entry<String, JsonNode>> fields = expectedJson.getFields();
        while (fields.hasNext()) {
            Entry<String, JsonNode> entry = fields.next();
            String expectedFieldName = entry.getKey();
            JsonNode expectedValue = entry.getValue();
            if (expectedValue instanceof TextNode) {
                TextNode tn = (TextNode) expectedValue;
                String expected = tn.getTextValue();
                JsonNode actualValue = actualJson.get(expectedFieldName);
                Assert.assertNotNull("Expected JSON text field '" + expectedFieldName + "' with value '"
                        + expected + "' but was not found.", actualValue);
                Assert.assertEquals("Expected JSON text field '" + expectedFieldName + "' with value '"
                        + expected + "' but found non-text [" + actualValue.getClass().getSimpleName()
                        + "] field with that name instead.", TextNode.class, actualValue.getClass());
                String actual = ((TextNode) actualValue).getTextValue();
                Assert.assertEquals("Value mis-match for text field '" + expectedFieldName + "'.", expected,
                        actual);
            } else if (expectedValue instanceof NumericNode) {
                NumericNode numeric = (NumericNode) expectedValue;
                Number expected = numeric.getNumberValue();
                JsonNode actualValue = actualJson.get(expectedFieldName);
                Assert.assertNotNull("Expected JSON numeric field '" + expectedFieldName + "' with value '"
                        + expected + "' but was not found.", actualValue);
                Assert.assertEquals("Expected JSON numeric field '" + expectedFieldName + "' with value '"
                        + expected + "' but found non-numeric [" + actualValue.getClass().getSimpleName()
                        + "] field with that name instead.", expectedValue.getClass(), actualValue.getClass());
                Number actual = ((NumericNode) actualValue).getNumberValue();
                Assert.assertEquals("Value mis-match for numeric field '" + expectedFieldName + "'.", expected,
                        actual);
            } else if (expectedValue instanceof ObjectNode) {
                JsonNode actualValue = actualJson.get(expectedFieldName);
                Assert.assertNotNull("Expected parent JSON field '" + expectedFieldName
                        + "' but was not found.", actualValue);
                Assert.assertEquals("Expected parent JSON field '" + expectedFieldName
                        + "' found field of type '" + actualValue.getClass().getSimpleName() + "'.",
                        ObjectNode.class, actualValue.getClass());
                assertJson(expectedValue, actualValue);
            }
        }
    }

    /**
     * Assume the payload is Text and do some assertions based on the configuration
     * in the REST Test.
     * @param restTest
     * @param response
     */
    private void assertTextPayload(RestTest restTest, HttpResponse response) {
        InputStream inputStream = null;
        try {
            inputStream = response.getEntity().getContent();
            List<String> lines = IOUtils.readLines(inputStream);
            StringBuilder builder = new StringBuilder();
            for (String line : lines) {
                builder.append(line).append("\n");
            }
            
            String actual = builder.toString();
            String expected = restTest.getExpectedResponsePayload();
            Assert.assertEquals("Response payload (text/plain) mis-match.", expected, actual);
        } catch (Exception e) {
            throw new Error(e);
        } finally {
            IOUtils.closeQuietly(inputStream);
        }
    }

    /**
     * Logs a message.
     * 
     * @param message
     * @param params
     */
    private void log(String message, Object... params) {
        String outmsg = MessageFormat.format(message, params);
        logger.info("    >> " + outmsg);
    }

    @AfterClass
    public static void shutdown() throws Exception {
        testServer.stop();
    }

}