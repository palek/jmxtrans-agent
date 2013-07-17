/*
 * Copyright (c) 2010-2013 the original author or authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */
package org.jmxtrans.embedded.output;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.StringReader;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.io.File;
import java.net.*;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Collections;
import java.util.Comparator;

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.MappingJsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;

import org.jmxtrans.embedded.EmbeddedJmxTransException;
import org.jmxtrans.embedded.QueryResult;
import org.jmxtrans.embedded.util.io.IoUtils2;
import org.jmxtrans.embedded.util.StringUtils2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import javax.annotation.Nonnull;
import javax.annotation.Nullable;


import java.lang.management.ManagementFactory;

/**
 * <a href="https://copperegg.com//">CopperEgg Metrics</a> implementation of the {@linkplain org.jmxtrans.embedded.output.OutputWriter}.
 * <p/>
 * This implementation uses v2 of the CopperEgg API <a href="http://dev.copperegg.com/">
 * <p/>
 * Settings:
 * <ul>
 * <li>"{@code url}": CopperEgg API server URL.
 * Optional, default value: {@value #DEFAULT_COPPEREGG_API_URL}.</li>
 * <li>"{@code user}": CopperEgg user. Mandatory</li>
 * <li>"{@code token}": CopperEgg APIKEY. Mandatory</li>
 * <li>"{@code coppereggApiTimeoutInMillis}": read timeout of the calls to CopperEgg HTTP API.
 * Optional, default value: {@value #DEFAULT_COPPEREGG_API_TIMEOUT_IN_MILLIS}.</li>
 * <li>"{@code enabled}": flag to enable/disable the writer. Optional, default value: <code>true</code>.</li>
 * <li>"{@code source}": CopperEgg . Optional, default value: {@value #DEFAULT_SOURCE} (the hostname of the server).</li>
 * </ul>
 * LibratoWriter.java author:
 * @author <a href="mailto:cleclerc@cloudbees.com">Cyrille Le Clerc</a>
 *
 * CopperEggWriter.java was derived from LibratoWriter.java 
 */
public class CopperEggWriter extends AbstractOutputWriter implements OutputWriter {
    public static final String METRIC_TYPE_GAUGE = "gauge";
    public static final String METRIC_TYPE_COUNTER = "counter";
    public static final String DEFAULT_COPPEREGG_API_URL = "https://api.copperegg.com/v2/revealmetrics";
    public static final String SETTING_COPPEREGG_API_TIMEOUT_IN_MILLIS = "coppereggApiTimeoutInMillis";
    public static final int DEFAULT_COPPEREGG_API_TIMEOUT_IN_MILLIS = 5000;
    public static final String SETTING_SOURCE = "source";
    public static final String DEFAULT_SOURCE = "#hostname#";
    private final static String DEFAULT_COPPEREGG_CONFIGURATION_PATH = "classpath:copperegg_config.json";
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final AtomicInteger exceptionCounter = new AtomicInteger();
    private JsonFactory jsonFactory = new JsonFactory();


   /**
     * CopperEgg API URL
     */
    private URL url;
    private String url_str;
    private int coppereggApiTimeoutInMillis = DEFAULT_COPPEREGG_API_TIMEOUT_IN_MILLIS;
    private long myPID = 0;
    private String myhost;
    private String myPID_host;
    private String config_location;

    private static Map<String, String> dashMap = new HashMap<String, String>();
    private static Map<String, String> metricgroupMap = new HashMap<String, String>();

    private String jvm_metric_groupID = null;
    private String tomcat_metric_groupID = null;
    private String app_metric_groupID = null;

    /**
     * CopperEgg API authentication username
     */
    private String user;
    /**
     * CopperEgg APIKEY
     */
    private String token;
    private String basicAuthentication;
    /**
     * Optional proxy for the http API calls
     */
    @Nullable
    private Proxy proxy;
    /**
     * CopperEgg measurement property 'source',
     */
    @Nullable
    private String source;

    /**
     * Load settings<p/>
     */
    @Override
    public void start() {

        config_location = DEFAULT_COPPEREGG_CONFIGURATION_PATH;
        String path = config_location.substring("classpath:".length());
 
        long thisPID = getPID();
        if( myPID == thisPID) {
            logger.info("Started from two threads with the same PID, {}",thisPID);
            return;
        }
        myPID = thisPID;
        try {
            String str = String.valueOf(myPID);
            url_str = getStringSetting(SETTING_URL, DEFAULT_COPPEREGG_API_URL);
            url = new URL(url_str);
            user = getStringSetting(SETTING_USERNAME);
            token = getStringSetting(SETTING_TOKEN);
            user = token;
            basicAuthentication = Base64Variants.getDefaultVariant().encode((user + ":" + "U").getBytes(Charset.forName("US-ASCII")));

            if (getStringSetting(SETTING_PROXY_HOST, null) != null && !getStringSetting(SETTING_PROXY_HOST).isEmpty()) {
                proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(getStringSetting(SETTING_PROXY_HOST), getIntSetting(SETTING_PROXY_PORT)));
            }

            coppereggApiTimeoutInMillis = getIntSetting(SETTING_COPPEREGG_API_TIMEOUT_IN_MILLIS, DEFAULT_COPPEREGG_API_TIMEOUT_IN_MILLIS);

            source = getStringSetting(SETTING_SOURCE, DEFAULT_SOURCE);
            source = getStrategy().resolveExpression(source);
            myhost = source;
            myPID_host = myhost + '.' + str;
            
            try{
                InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
                if(in == null) {
                    logger.warn("No file found for classpath:" + config_location);
                } else {
                  read_config(in);
                }
            } catch (Exception e){
                exceptionCounter.incrementAndGet();
                logger.warn("Exception in start " + e);
            }

            ensure_metric_groups();
            ensure_dashboards();

            logger.info("Started CopperEggWriter Successfully on jvm '{}', connected to '{}', proxy {}", myPID_host, url, proxy);
        } catch (MalformedURLException e) {
            exceptionCounter.incrementAndGet();
            throw new EmbeddedJmxTransException(e);
        }
    }

    /**
     * Export collected metrics to CopperEgg
     */
    @Override
    public void write(Iterable<QueryResult> results) {

        List<QueryResult> jvm_counters = new ArrayList<QueryResult>();
        List<QueryResult> tomcat_counters = new ArrayList<QueryResult>();
        List<QueryResult> app_counters = new ArrayList<QueryResult>();

        long epochInMillis = 0;
        String myname =  null;
        Object myval = null;
        long thisPID = 0;
        String tmp = null;
        String pidHost = null;

        String delims = "[.]";
        for (QueryResult result : results) {
            epochInMillis = result.getEpochInMillis();
            myname =  result.getName();
            myval = result.getValue();
            thisPID = getPID();
            tmp = String.valueOf(thisPID);
            pidHost = source + "." + tmp;

            String[] parts = myname.split(delims);
            if( parts.length > 0 ) {
                String p1 = parts[0];
                if( (p1.equals("jvm")) || (p1.equals("jmxtrans")) ) {
                    QueryResult new_result = new QueryResult(myname, pidHost, myval, epochInMillis);
                    jvm_counters.add(new_result);
                } else if( p1.equals("tomcat") ) {
                    if( (parts[1].equals("thread-pool")) || (parts[1].equals("global-request-processor")) ) {
                        String connector = parts[2];
                        myname = parts[0] + "." + parts[1] + "." + parts[3];
                        String fullID = pidHost + "." + connector;
                        QueryResult new_result = new QueryResult(myname, fullID, myval, epochInMillis);
                        tomcat_counters.add(new_result);
                    } else if( parts[1].equals("manager") ) {
                        String myhost = parts[2];
                        String mycontext = parts[3];
                        myname = parts[0] + "." + parts[1] + "." + parts[4];
                        String fullID = pidHost + "." + myhost + "." + mycontext;
                        QueryResult new_result = new QueryResult(myname, fullID, myval, epochInMillis);
                        tomcat_counters.add(new_result);
                    } else if( parts[1].equals("servlet") ) {
                        String myWebmodule = parts[2];
                        String myServletname = parts[3];
                        if( (parts[3].equals("default")) || (parts[3].equals("jsp")) ) {
                            myWebmodule = myWebmodule + "ROOT";
                        }
                        myname = parts[0] + "." + parts[1] + "." + parts[4];
                        String fullID = pidHost + "." + myWebmodule + "." + myServletname;
                        QueryResult new_result = new QueryResult(myname, fullID, myval, epochInMillis);
                        tomcat_counters.add(new_result);
                    } else if( parts[1].equals("data-source") ) {
                        String myhost = parts[2];
                        String mycontext = parts[3];
                        String mydbname = parts[4];
                        myname = parts[0] + "." + parts[1] + "." + parts[5];
                        String fullID = pidHost + "." + myhost + "." + mycontext + "." + mydbname;
                        QueryResult new_result = new QueryResult(myname, fullID, myval, epochInMillis);
                        tomcat_counters.add(new_result);
                    }
                } else if( p1.equals("website") ) {
                    if( parts[1].equals("visitors")  ) {
                        myname = "tomcat.website." + parts[1] + "." + parts[2];
                        QueryResult new_result = new QueryResult(myname, pidHost, myval, epochInMillis);
                        tomcat_counters.add(new_result);
                    }
                } else if( (p1.equals("sales")) || (p1.equals("cocktail")) ) {
                    QueryResult new_result = new QueryResult(myname, pidHost, myval, epochInMillis);
                    app_counters.add(new_result);
                }
            } else {
                logger.warn("parts return NULL!!!");
            }
        }
        if(jvm_counters.size() > 0) {
            Collections.sort(jvm_counters, new Comparator<QueryResult>() {
                public int compare(QueryResult o1, QueryResult o2) {
                  //Sorts by 'epochInMillis' property
                  return o1.getEpochInMillis()<o2.getEpochInMillis()?-1:o1.getEpochInMillis()>o2.getEpochInMillis()?1:0;
                }
            });
            send_metrics(jvm_metric_groupID, jvm_counters);
        }
        if(tomcat_counters.size() > 0) {
            Collections.sort(tomcat_counters, new Comparator<QueryResult>() {
                public int compare(QueryResult o1, QueryResult o2) {
                  //Sorts by 'epochInMillis' property
                  return o1.getEpochInMillis()<o2.getEpochInMillis()?-1:o1.getEpochInMillis()>o2.getEpochInMillis()?1:0;
                }
  
            });
            send_metrics(tomcat_metric_groupID, tomcat_counters);
        }
        if(app_counters.size() > 0) {
            Collections.sort(jvm_counters, new Comparator<QueryResult>() {
                public int compare(QueryResult o1, QueryResult o2) {
                  //Sorts by 'epochInMillis' property
                  return o1.getEpochInMillis()<o2.getEpochInMillis()?-1:o1.getEpochInMillis()>o2.getEpochInMillis()?1:0;
                }
            });
            send_metrics(app_metric_groupID, app_counters);
        }
    }
    public void send_metrics(String mg_name, List<QueryResult> counters) {
         long timeblock = counters.get(0).getEpoch(TimeUnit.SECONDS);
        int remaining = counters.size();
        List<QueryResult> sorted_ctrs = new ArrayList<QueryResult>();
 
       for (QueryResult counter : counters) {
            remaining = remaining - 1;
            if( timeblock != (counter.getEpoch(TimeUnit.SECONDS)) ) {
                one_set(mg_name, sorted_ctrs); 
                timeblock = counter.getEpoch(TimeUnit.SECONDS);
                sorted_ctrs.clear();
                sorted_ctrs.add(counter);
            } else {
                sorted_ctrs.add(counter);
            }
            if( remaining == 0 ) {
                one_set(mg_name, sorted_ctrs); 
            }
        }
    }

    public void one_set(String mg_name, List<QueryResult> counters)  {
        HttpURLConnection urlCxn = null;
        URL newurl = null;
        try {
            newurl = new URL(url_str + "/samples/" + mg_name + ".json");
            if (proxy == null) {
                urlCxn = (HttpURLConnection) newurl.openConnection();
            } else {
                urlCxn = (HttpURLConnection) newurl.openConnection(proxy);
            }
            if (urlCxn != null) {
                urlCxn.setRequestMethod("POST");
                urlCxn.setDoInput(true);
                urlCxn.setDoOutput(true);
                urlCxn.setReadTimeout(coppereggApiTimeoutInMillis);
                urlCxn.setRequestProperty("content-type", "application/json; charset=utf-8");
                urlCxn.setRequestProperty("Authorization", "Basic " + basicAuthentication);
            }
        } catch (Exception e) {
            exceptionCounter.incrementAndGet();
            logger.warn("Exceptiom: one_set: failed to connect to CopperEgg Service '{}' with proxy {}", newurl, proxy, e);
            return;
        }
        if( urlCxn != null ) {
            try {     
                cue_serialize(counters, urlCxn.getOutputStream());
                int responseCode = urlCxn.getResponseCode();
                if (responseCode != 200) {
                    logger.warn("one_set: Failure {}: {} to send result to CopperEgg service {}", responseCode, urlCxn.getResponseMessage(), newurl);
                }       
                try {
                        InputStream in = urlCxn.getInputStream();
                        IoUtils2.copy(in, IoUtils2.nullOutputStream());
                        IoUtils2.closeQuietly(in);
                        InputStream err = urlCxn.getErrorStream();
                        if (err != null) {
                            IoUtils2.copy(err, IoUtils2.nullOutputStream());
                            IoUtils2.closeQuietly(err);
                        }
                } catch (IOException e) {
                        exceptionCounter.incrementAndGet();
                        logger.warn("Execption one_set: Write-Exception flushing http connection", e);
                }

            } catch (Exception e) {
                exceptionCounter.incrementAndGet();
                logger.warn("Execption: one_set: Failure to send result to CopperEgg Service '{}' with proxy {}", newurl, proxy, e);
            }
        }
    }
    public void cue_serialize(@Nonnull Iterable<QueryResult> counters, @Nonnull OutputStream out) throws IOException {
        int first = 0;
        long time = 0;
        String myID = null;
        JsonGenerator g = jsonFactory.createGenerator(out, JsonEncoding.UTF8);

        for (QueryResult counter : counters) {
           if( 0 == first ) {
              time = counter.getEpoch(TimeUnit.SECONDS);
              myID = counter.getType();
              first = 1;
              g.writeStartObject();
              g.writeStringField("identifier", myID);
              g.writeNumberField("timestamp", time);
              g.writeObjectFieldStart("values");
           }
           if (counter.getValue() instanceof Integer) {
                g.writeNumberField(counter.getName(), (Integer) counter.getValue());
            } else if (counter.getValue() instanceof Long) {
                g.writeNumberField(counter.getName(), (Long) counter.getValue());
            } else if (counter.getValue() instanceof Float) {
                g.writeNumberField(counter.getName(), (Float) counter.getValue());
            } else if (counter.getValue() instanceof Double) {
                g.writeNumberField(counter.getName(), (Double) counter.getValue());
            }
        }
        g.writeEndObject();
        g.writeEndObject();
        g.flush();
        g.close();
    }


    private static long getPID() {
       String processName =
          java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
       return Long.parseLong(processName.split("@")[0]);
    }

    public int cue_getExceptionCounter() {
        return exceptionCounter.get();
    }

    /**
     * If metric group doesn't exist, create it
     * If it does exist, update it.
     */

    public void ensure_metric_groups() {
        HttpURLConnection urlConnection = null;
        OutputStreamWriter wr = null;
        URL myurl = null;

        try {
           myurl = new URL(url_str + "/metric_groups.json?show_hidden=1");
           urlConnection = (HttpURLConnection) myurl.openConnection();
           urlConnection.setRequestMethod("GET");
           urlConnection.setDoInput(true);
           urlConnection.setDoOutput(true);
           urlConnection.setReadTimeout(coppereggApiTimeoutInMillis);
           urlConnection.setRequestProperty("content-type", "application/json; charset=utf-8");
           urlConnection.setRequestProperty("Authorization", "Basic " + basicAuthentication);

           int responseCode = urlConnection.getResponseCode();
           if (responseCode != 200) {
               logger.warn("Bad responsecode " + String.valueOf(responseCode)+ " from metric_groups Index: " +  myurl);
           }
        } catch (Exception e) {
              exceptionCounter.incrementAndGet();
              logger.warn("Failure to execute metric_groups index request "+ myurl + "  "+ e);
        } finally {
            if (urlConnection != null) {
                try {
                    InputStream in = urlConnection.getInputStream();
                    String theString = convertStreamToString(in);
                    for (Map.Entry<String, String> entry : metricgroupMap.entrySet()) {
                        String checkName =  entry.getKey();
                        try {
                            String Rslt = groupFind(checkName, theString, 0);
                            if(Rslt != null){
                                // Update it
                                Rslt = Send_Commmand("/metric_groups/" + Rslt + ".json?show_hidden=1", "PUT", entry.getValue(),0);
                            } else {
                                // create it
                                Rslt = Send_Commmand("/metric_groups.json", "POST", entry.getValue(),0);
                            }
                            if(Rslt != null) {
                                if (Rslt.toLowerCase().contains("tomcat")) {
                                    tomcat_metric_groupID = Rslt;
                                } else if (Rslt.toLowerCase().contains("jvm")){
                                    jvm_metric_groupID = Rslt;
                                } else {
                                    app_metric_groupID = Rslt;
                                }
                            }
                        } catch (Exception e) {
                            exceptionCounter.incrementAndGet();
                            logger.warn("Exception in metric_group update or create: "+ myurl + "  "+ e);
                        }
                    }
                } catch (IOException e) {
                    exceptionCounter.incrementAndGet();
                    logger.warn("Exception flushing http connection"+ e);
                }
            }
        }
    }


    /**
     * If dashboard doesn't exist, create it
     * If it does exist, update it.
     */

    private void ensure_dashboards() {
        HttpURLConnection urlConnection = null;
        OutputStreamWriter wr = null;
        URL myurl = null;

        try {
           myurl = new URL(url_str + "/dashboards.json");
           urlConnection = (HttpURLConnection) myurl.openConnection();
           urlConnection.setRequestMethod("GET");
           urlConnection.setDoInput(true);
           urlConnection.setDoOutput(true);
           urlConnection.setReadTimeout(coppereggApiTimeoutInMillis);
           urlConnection.setRequestProperty("content-type", "application/json; charset=utf-8");
           urlConnection.setRequestProperty("Authorization", "Basic " + basicAuthentication);

           int responseCode = urlConnection.getResponseCode();
           if (responseCode != 200) {
               logger.warn("Bad responsecode " + String.valueOf(responseCode)+ " from Dahsboards Index: " +  myurl);
           }
        } catch (Exception e) {
            exceptionCounter.incrementAndGet();
            logger.warn("Exception on dashboards index request "+ myurl + "  "+ e);
        } finally {
            if (urlConnection != null) {
                try {
                    InputStream in = urlConnection.getInputStream();
                    String theString = convertStreamToString(in);
                    for (Map.Entry<String, String> entry : dashMap.entrySet()) {
                        String checkName =  entry.getKey();
                        try {
                            String Rslt = groupFind(checkName, theString, 1);
                            if(Rslt != null){
                                // Update it
                                Rslt = Send_Commmand("/dashboards/" + Rslt + ".json", "PUT", entry.getValue(),1);
                            } else {
                                // create it
                                Rslt = Send_Commmand("/dashboards.json", "POST", entry.getValue(),1);
                            }
                        } catch (Exception e) {
                            exceptionCounter.incrementAndGet();
                            logger.warn("Exception in dashboard update or create: "+ myurl + "  "+ e);   
                        }
                    }
                } catch (IOException e) {
                    exceptionCounter.incrementAndGet();
                    logger.warn("Exception flushing http connection"+ e);
                }
            }
        }
    }

    private String jparse(String jsonstr, Integer ExpectInt) {

        ObjectMapper mapper = new ObjectMapper();
        String Result = null;
        try {
            JsonNode root = mapper.readTree(jsonstr);
            if(ExpectInt != 0) {
                int myid = root.get("id").asInt();
                Result = String.valueOf(myid);
            } else {
                Result = root.get("id").asText().toString();
            }
        } catch (JsonGenerationException e) {
            exceptionCounter.incrementAndGet();
            logger.warn("JsonGenerationException "+ e);
        } catch (JsonMappingException e) {
            exceptionCounter.incrementAndGet();
            logger.warn("JsonMappingException "+ e);
        } catch (IOException e) {
            exceptionCounter.incrementAndGet();
            logger.warn("IOException "+ e);
        }
        return(Result);
    }


    public String convertStreamToString(InputStream is)
            throws IOException {
        //
        // To convert the InputStream to String we use the
        // Reader.read(char[] buffer) method. We iterate until the
        // Reader return -1 which means there's no more data to
        // read. We use the StringWriter class to produce the string.
        //
        if (is != null) {
            Writer writer = new StringWriter();

            char[] buffer = new char[1024];
            try {
                Reader reader = new BufferedReader(
                        new InputStreamReader(is, "UTF-8"));
                int n;
                while ((n = reader.read(buffer)) != -1) {
                    writer.write(buffer, 0, n);
                }
            } finally {
                is.close();
            }
            return writer.toString();
        } else {
            return "";
        }
    }

    /**
     * read_config()
     * The copperegg_config.json file contains a specification for the metric groups and dashboards to be created / or updated.
     * Mandatory
     */
    public void read_config(InputStream in) throws Exception {

        JsonFactory f = new MappingJsonFactory();
        JsonParser jp = f.createJsonParser(in);

        JsonToken current;

        current = jp.nextToken();
        if (current != JsonToken.START_OBJECT) {
            logger.warn("read_config: Error:  START_OBJECT not found : quiting.");
            return;
        }
        current = jp.nextToken();
        String fieldName = jp.getCurrentName();
        current = jp.nextToken();
        if (fieldName.equals("config")) {
            if (current != JsonToken.START_OBJECT) {
                logger.warn("read_config: Error:  START_OBJECT not found after config : quiting.");
                return;
            }
            current = jp.nextToken();
            String fieldName2 = jp.getCurrentName();
            if (fieldName2.equals("metric_groups")) {
                current = jp.nextToken();
                if (current != JsonToken.START_ARRAY) {
                  logger.warn("read_config: Error:  START_ARRAY not found after metric_groups : quiting.");
                  return;
                }

                current = jp.nextToken();
                while (current != JsonToken.END_ARRAY) {
                    if (current != JsonToken.START_OBJECT) {
                        logger.warn("read_config: Error:  START_OBJECT not found after metric_groups START_ARRAY : quiting.");
                        return;
                    }
                    current = jp.nextToken();
                    JsonNode node1 = jp.readValueAsTree();
                    String node1string = write_tostring(node1);
                    metricgroupMap.put(node1.get("name").asText(),node1string);
                    current = jp.nextToken();
                }

                current = jp.nextToken();
                String fieldName3 = jp.getCurrentName();

                if (fieldName3.equals("dashboards")) {
                    current = jp.nextToken();
                    if (current != JsonToken.START_ARRAY) {
                        logger.warn("read_config: Error:  START_ARRAY not found after dashboards : quiting.");
                        return;
                    }
                    current = jp.nextToken();
                    while (current != JsonToken.END_ARRAY) {
                        if (current != JsonToken.START_OBJECT) {
                            logger.warn("read_config: Error:  START_OBJECT not found after dashboards START_ARRAY : quiting.");
                            return;
                        }
                        current = jp.nextToken();
                        JsonNode node = jp.readValueAsTree();
                        String nodestring = write_tostring(node);
                        dashMap.put(node.get("name").asText(),nodestring);
                        current = jp.nextToken();

                    }
                    if(jp.nextToken() != JsonToken.END_OBJECT) {
                        logger.warn("read_config: Error:  END_OBJECT expected, not found (1): quiting.");
                        return;
                    }
                    if(jp.nextToken() != JsonToken.END_OBJECT) {
                        logger.warn("read_config: Error:  END_OBJECT expected, not found (2): quiting.");
                        return;
                    }
                } else {
                    logger.warn("read_config: Error:  Expected dashboards : quiting.");
                    return;
                }
            } else {
                logger.warn("read_config: Error:  Expected metric_groups : quiting.");
                return;
            }
        }
    }

    public String groupFind(String findName, String findIndex, Integer ExpectInt) throws Exception {
        JsonFactory f = new MappingJsonFactory();
        JsonParser jp = f.createJsonParser(findIndex);

        int count = 0;
        int foundit = 0;
        String Result = null;

        JsonToken current = jp.nextToken();
        if (current != JsonToken.START_ARRAY) {
          logger.warn("groupFind: Error: START_ARRAY expected, not found : quiting.");
          return(Result);
        }
        current = jp.nextToken();
        while (current != JsonToken.END_ARRAY) {
            if (current != JsonToken.START_OBJECT) {
                logger.warn("groupFind: Error: START_OBJECT expected, not found : quiting.");
                return(Result);
            }
            current = jp.nextToken();
            JsonNode node = jp.readValueAsTree();
            String tmpStr = node.get("name").asText().toString();
             if(findName.equals(node.get("name").asText().toString())) {
                if(ExpectInt != 0) {
                    foundit = node.get("id").asInt();
                    Result = String.valueOf(foundit);
                } else {
                    Result = node.get("id").asText().toString();
                }
                break;
            }
            current = jp.nextToken();
            count = count + 1;
        }
        return(Result);
    }

    public String write_tostring(JsonNode json){
        ObjectMapper mapper = new ObjectMapper();
        StringWriter out = new StringWriter();

        try {
            JsonFactory fac = new JsonFactory();
            JsonGenerator gen = fac.createJsonGenerator(out);

            // Now write:
            mapper.writeTree(gen, json);
            gen.flush();
            gen.close();
            return out.toString();
        }
        catch(Exception e) {
            exceptionCounter.incrementAndGet();
            logger.warn("Exception in write_tostring: " + e); 
        }
        return(null);
    }

    public String Send_Commmand(String command, String msgtype, String payload, Integer ExpectInt){
        HttpURLConnection urlConnection = null;
        URL myurl = null;
        OutputStreamWriter wr = null;
        int responseCode = 0;
        String id = null;
        int error = 0;

        try {
            myurl = new URL(url_str + command);
            urlConnection = (HttpURLConnection) myurl.openConnection();
            urlConnection.setRequestMethod(msgtype);
            urlConnection.setDoInput(true);
            urlConnection.setDoOutput(true);
            urlConnection.setReadTimeout(coppereggApiTimeoutInMillis);
            urlConnection.addRequestProperty("User-Agent", "Mozilla/4.76");
            urlConnection.setRequestProperty("content-type", "application/json; charset=utf-8");
            urlConnection.setRequestProperty("Authorization", "Basic " + basicAuthentication);

            wr = new OutputStreamWriter(urlConnection.getOutputStream(),"UTF-8");
            wr.write(payload);
            wr.flush();

            responseCode = urlConnection.getResponseCode();
            if (responseCode != 200) {
                logger.warn("Send Command: Response code " + responseCode + " url is " + myurl + " command " + msgtype);
                error = 1;
            }
        } catch (Exception e) {
            exceptionCounter.incrementAndGet();
            logger.warn("Exception in Send Command: url is " + myurl + " command " + msgtype + "; " + e);
            error = 1;
        } finally {
            if (urlConnection != null) {
                try {
                  if( error > 0 ) {
                        InputStream err = urlConnection.getErrorStream();
                        String errString = convertStreamToString(err);
                        logger.warn("Reported error : " + errString); 
                        IoUtils2.closeQuietly(err);
                    } else {
                        InputStream in = urlConnection.getInputStream();
                        String theString = convertStreamToString(in);
                        id = jparse(theString, ExpectInt);
                        IoUtils2.closeQuietly(in);
                    }
                } catch (IOException e) {
                    exceptionCounter.incrementAndGet();
                    logger.warn("Exception in Send Command : flushing http connection " +  e);
                }
            }
            if(wr != null) {
                try {
                    wr.close();
                } catch (IOException e) {
                    exceptionCounter.incrementAndGet();
                    logger.warn("Exception in Send Command: closing OutputWriter " +  e);
                }
            }
        }
        return(id);
    }
}

