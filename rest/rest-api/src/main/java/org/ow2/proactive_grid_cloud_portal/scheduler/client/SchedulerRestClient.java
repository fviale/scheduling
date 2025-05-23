/*
 * ProActive Parallel Suite(TM):
 * The Open Source library for parallel and distributed
 * Workflows & Scheduling, Orchestration, Cloud Automation
 * and Big Data Analysis on Enterprise Grids & Clouds.
 *
 * Copyright (c) 2007 - 2017 ActiveEon
 * Contact: contact@activeeon.com
 *
 * This library is free software: you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation: version 3 of
 * the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * If needed, contact us to obtain a release under GPL Version 2 or 3
 * or a different license than the AGPL.
 */
package org.ow2.proactive_grid_cloud_portal.scheduler.client;

import static org.apache.commons.io.FileUtils.copyInputStreamToFile;

import java.io.*;
import java.lang.reflect.*;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import javax.annotation.Priority;
import javax.net.ssl.*;
import javax.ws.rs.Consumes;
import javax.ws.rs.Priorities;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.*;
import javax.ws.rs.ext.ContextResolver;
import javax.ws.rs.ext.Provider;
import javax.ws.rs.ext.ReaderInterceptorContext;

import org.apache.http.client.HttpClient;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.ssl.SSLContexts;
import org.jboss.resteasy.client.jaxrs.ClientHttpEngine;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.jboss.resteasy.client.jaxrs.engines.ApacheHttpClient4Engine;
import org.jboss.resteasy.core.interception.ServerReaderInterceptorContext;
import org.jboss.resteasy.plugins.interceptors.encoding.AcceptEncodingGZIPFilter;
import org.jboss.resteasy.plugins.interceptors.encoding.GZIPDecodingInterceptor;
import org.jboss.resteasy.plugins.interceptors.encoding.GZIPEncodingInterceptor;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;
import org.jboss.resteasy.resteasy_jaxrs.i18n.LogMessages;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.ow2.proactive.scheduler.common.job.JobIdDataAndError;
import org.ow2.proactive_grid_cloud_portal.common.SchedulerRestInterface;
import org.ow2.proactive_grid_cloud_portal.common.exceptionmapper.ExceptionToJson;
import org.ow2.proactive_grid_cloud_portal.dataspace.dto.ListFile;
import org.ow2.proactive_grid_cloud_portal.scheduler.client.utils.Zipper;
import org.ow2.proactive_grid_cloud_portal.scheduler.dto.JobIdData;
import org.ow2.proactive_grid_cloud_portal.scheduler.dto.WorkflowUrlData;
import org.ow2.proactive_grid_cloud_portal.scheduler.exception.NotConnectedRestException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.common.net.UrlEscapers;


public class SchedulerRestClient {

    public static final String VARIABLES_KEY = "variables";

    public static final String FILE_KEY = "file";

    private SchedulerRestInterface scheduler;

    private String restEndpointURL;

    private ResteasyProviderFactory providerFactory;

    private static ClientHttpEngine httpEngine;

    private static SSLContext sslContext;

    private static GZIPDecodingInterceptor gzipDecodingInterceptor = new GZIPDecodingInterceptor(Integer.MAX_VALUE);

    public SchedulerRestClient(String restEndpointURL) {
        this(restEndpointURL, null);
    }

    public SchedulerRestClient(String restEndpointURL, ClientHttpEngine httpEngine) {

        if (httpEngine == null) {
            setBlindTrustSSLContext();
            SSLConnectionSocketFactory sslConnectionSocketFactory = new SSLConnectionSocketFactory(sslContext,
                                                                                                   NoopHostnameVerifier.INSTANCE);
            HttpClient httpClient = HttpClientBuilder.create().setSSLSocketFactory(sslConnectionSocketFactory).build();
            httpEngine = new ApacheHttpClient4Engine(httpClient);
        }

        this.httpEngine = httpEngine;
        this.restEndpointURL = restEndpointURL;

        providerFactory = ResteasyProviderFactory.getInstance();
        if (!providerFactory.isRegistered(JacksonContextResolver.class)) {
            providerFactory.registerProvider(JacksonContextResolver.class);
        }
        registerGzipEncoding(providerFactory);

        scheduler = createRestProxy(providerFactory, restEndpointURL, httpEngine);
    }

    public String getRestEndpointURL() {
        return restEndpointURL;
    }

    private void setBlindTrustSSLContext() {
        try {
            TrustStrategy acceptingTrustStrategy = (cert, authType) -> true;
            sslContext = SSLContexts.custom().loadTrustMaterial(null, acceptingTrustStrategy).build();

        } catch (KeyStoreException | KeyManagementException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private static ResteasyClient buildResteasyClient(ResteasyProviderFactory provider) {
        return new ResteasyClientBuilder().providerFactory(provider).httpEngine(httpEngine).build();
    }

    public static void registerGzipEncoding(ResteasyProviderFactory providerFactory) {
        if (!providerFactory.isRegistered(AcceptEncodingGZIPFilter.class)) {
            providerFactory.registerProvider(AcceptEncodingGZIPFilter.class);
        }
        // Because of a bad design in rest easy GZIPDecodingInterceptor, the following code ensures :
        // - To remove the existing default GZIPDecodingInterceptor
        // (unfortunately the upgrade to RestEasy 3.15.6 changed the returned set to unmodifiable, making it necessary to use reflection
        // - instantiate a GZIPDecodingInterceptor with the appropriate max value
        if (!providerFactory.isRegistered(gzipDecodingInterceptor)) {
            Set<Class<?>> classes = providerFactory.getClasses();
            try {
                Field cField = classes.getClass().getSuperclass().getDeclaredField("c");
                cField.setAccessible(true);
                Collection collection = (Collection) cField.get(classes);
                collection.remove(GZIPDecodingInterceptor.class);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new IllegalStateException("Cannot access field in providerFactory.getClasses()", e);
            }
            providerFactory.registerProviderInstance(gzipDecodingInterceptor);
        }
        if (!providerFactory.isRegistered(GZIPEncodingInterceptor.class)) {
            providerFactory.registerProvider(GZIPEncodingInterceptor.class);
        }
    }

    public JobIdData submitXmlAs(String sessionId, InputStream jobXml, String user) throws Exception {
        return submitXml(sessionId, jobXml, null);
    }

    public JobIdData submitXml(String sessionId, InputStream jobXml) throws Exception {
        return submitXml(sessionId, jobXml, null);
    }

    public JobIdData submitXml(String sessionId, InputStream jobXml, Map<String, String> variables) throws Exception {
        return submit(sessionId, jobXml, MediaType.APPLICATION_XML_TYPE, variables, null);
    }

    public JobIdData submitXml(String sessionId, InputStream jobXml, Map<String, String> variables,
            Map<String, String> genericInfos) throws Exception {
        return submit(sessionId, jobXml, MediaType.APPLICATION_XML_TYPE, variables, genericInfos);
    }

    public JobIdData submitJobArchive(String sessionId, InputStream jobArchive) throws Exception {
        return submitJobArchive(sessionId, jobArchive, null, null);
    }

    public JobIdData submitJobArchive(String sessionId, InputStream jobArchive, Map<String, String> variables,
            Map<String, String> genericInfos) throws Exception {
        return submit(sessionId, jobArchive, MediaType.APPLICATION_OCTET_STREAM_TYPE, variables, genericInfos);
    }

    public boolean pushFile(String sessionId, String space, String path, String fileName, InputStream fileContent)
            throws Exception {
        String uriTmpl = (new StringBuilder(restEndpointURL)).append(addSlashIfMissing(restEndpointURL))
                                                             .append("scheduler/dataspace/")
                                                             .append(space)
                                                             .append(URLEncoder.encode(path, "UTF-8"))
                                                             .toString();

        ResteasyClient client = buildResteasyClient(providerFactory);

        ResteasyWebTarget target = client.target(uriTmpl);

        MultipartFormDataOutput formData = new MultipartFormDataOutput();
        formData.addFormData("fileName", fileName, MediaType.TEXT_PLAIN_TYPE);
        formData.addFormData("fileContent", fileContent, MediaType.APPLICATION_OCTET_STREAM_TYPE);

        GenericEntity<MultipartFormDataOutput> entity = new GenericEntity<MultipartFormDataOutput>(formData) {
        };

        Response response = target.request()
                                  .header("sessionid", sessionId)
                                  .post(Entity.entity(entity, MediaType.MULTIPART_FORM_DATA_TYPE));

        if (response.getStatus() != HttpURLConnection.HTTP_OK) {
            if (response.getStatus() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw new NotConnectedRestException("User not authenticated or session timeout.");
            } else {
                throwException(String.format("File upload failed. Status code: %d", response.getStatus()), response);
            }
        }
        return response.readEntity(Boolean.class);
    }

    public void updateLogo(String sessionId, InputStream fileContent) throws NotConnectedRestException {

        String uriTmpl = (new StringBuilder(restEndpointURL)).append(addSlashIfMissing(restEndpointURL))
                                                             .append("scheduler/logo/")
                                                             .toString();

        ResteasyClient client = buildResteasyClient(providerFactory);

        ResteasyWebTarget target = client.target(uriTmpl);

        MultipartFormDataOutput formData = new MultipartFormDataOutput();
        formData.addFormData("fileContent", fileContent, MediaType.APPLICATION_OCTET_STREAM_TYPE);

        GenericEntity<MultipartFormDataOutput> entity = new GenericEntity<MultipartFormDataOutput>(formData) {
        };

        Response response = target.request()
                                  .header("sessionid", sessionId)
                                  .post(Entity.entity(entity, MediaType.MULTIPART_FORM_DATA_TYPE));

        if (response.getStatus() != HttpURLConnection.HTTP_OK) {
            if (response.getStatus() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw new NotConnectedRestException("User not authenticated or session timeout.");
            } else {
                throwException(String.format("Update logo failed. Status code: %d", response.getStatus()), response);
            }
        }
    }

    public void pullFile(String sessionId, String space, String path, String outputPath) throws Exception {
        String uriTmpl = (new StringBuilder(restEndpointURL)).append(addSlashIfMissing(restEndpointURL))
                                                             .append("scheduler/dataspace/")
                                                             .append(space)
                                                             .append(URLEncoder.encode(path, "UTF-8"))
                                                             .toString();

        ResteasyClient client = buildResteasyClient(providerFactory);

        ResteasyWebTarget target = client.target(uriTmpl);
        Response response = target.request().header("sessionid", sessionId).get();
        if (response.getStatus() != HttpURLConnection.HTTP_OK) {
            if (response.getStatus() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw new NotConnectedRestException("User not authenticated or session timeout.");
            } else {
                throwException(String.format("Cannot retrieve the file. Status code: %s", response.getStatus()),
                               response);
            }
        }
        try {
            File file = new File(outputPath);
            if (response.hasEntity()) {
                copyInputStreamToFile(response.readEntity(InputStream.class), file);
            } else {
                // creates an empty file
                file.createNewFile();
            }
        } catch (Exception e) {
            throw e;
        } finally {
            if (response != null) {
                response.close();
            }
            if (!client.isClosed()) {
                client.close();
            }
        }
    }

    public boolean upload(String sessionId, File file, List<String> includes, List<String> excludes,
            String dataspacePath, final String path) throws Exception {
        StringBuffer uriTmpl = (new StringBuffer()).append(restEndpointURL)
                                                   .append(addSlashIfMissing(restEndpointURL))
                                                   .append("data/")
                                                   .append(dataspacePath)
                                                   .append('/')
                                                   .append(escapeUrlPathSegment(path));

        ResteasyClient client = buildResteasyClient(providerFactory);

        ResteasyWebTarget target = client.target(uriTmpl.toString());
        Response response = null;
        try {
            response = target.request()
                             .header("sessionid", sessionId)
                             .put(Entity.entity(new CompressedStreamingOutput(file, includes, excludes),
                                                new Variant(MediaType.APPLICATION_OCTET_STREAM_TYPE,
                                                            (Locale) null,
                                                            encoding(file))));
            if (response.getStatus() != HttpURLConnection.HTTP_CREATED) {
                if (response.getStatus() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    throw new NotConnectedRestException("User not authenticated or session timeout.");
                } else {
                    throwException(String.format("File upload failed. Status code: %d", response.getStatus()),
                                   response);
                }
            }
            return true;
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    public boolean upload(String sessionId, StreamingOutput output, String encoding, String dataspace, String path)
            throws Exception {
        StringBuffer uriTmpl = (new StringBuffer()).append(restEndpointURL)
                                                   .append(addSlashIfMissing(restEndpointURL))
                                                   .append("data/")
                                                   .append(dataspace);

        ResteasyClient client = buildResteasyClient(providerFactory);

        ResteasyWebTarget target = client.target(uriTmpl.toString()).path(path);
        Response response = null;
        try {
            response = target.request()
                             .header("sessionid", sessionId)
                             .put(Entity.entity(output,
                                                new Variant(MediaType.APPLICATION_OCTET_STREAM_TYPE,
                                                            (Locale) null,
                                                            encoding)));
            if (response.getStatus() != HttpURLConnection.HTTP_CREATED) {
                if (response.getStatus() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    throw new NotConnectedRestException("User not authenticated or session timeout.");
                } else {
                    throwException(String.format("File upload failed. Status code: %d" + response.getStatus()),
                                   response);
                }
            }
            return true;
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    public boolean download(String sessionId, String dataspacePath, String path, List<String> includes,
            List<String> excludes, String outputPath) throws Exception {
        return download(sessionId, dataspacePath, path, includes, excludes, new File(outputPath));
    }

    public boolean download(String sessionId, String dataspacePath, String path, List<String> includes,
            List<String> excludes, File outputFile) throws Exception {
        StringBuffer uriTmpl = (new StringBuffer()).append(restEndpointURL)
                                                   .append(addSlashIfMissing(restEndpointURL))
                                                   .append("data/")
                                                   .append(dataspacePath)
                                                   .append('/');

        ResteasyClient client = buildResteasyClient(providerFactory);

        ResteasyWebTarget target = client.target(uriTmpl.toString()).path(path);

        if (includes != null && !includes.isEmpty()) {
            target = target.queryParam("includes", includes.toArray(new Object[includes.size()]));
        }
        if (excludes != null && !excludes.isEmpty()) {
            target = target.queryParam("excludes", excludes.toArray(new Object[excludes.size()]));
        }

        Response response = null;
        try {
            response = target.request().header("sessionid", sessionId).acceptEncoding("*", "gzip", "zip").get();
            if (response.getStatus() != HttpURLConnection.HTTP_OK) {
                if (response.getStatus() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    throw new NotConnectedRestException("User not authenticated or session timeout.");
                } else {
                    throwException(String.format("Cannot retrieve the file. Status code: %d", response.getStatus()),
                                   response);
                }
            }
            if (response.hasEntity()) {
                InputStream is = response.readEntity(InputStream.class);
                if (isGZipEncoded(response)) {
                    if (outputFile.exists() && outputFile.isDirectory()) {
                        outputFile = new File(outputFile, response.getHeaderString("x-pds-pathname"));
                    }
                    Zipper.GZIP.unzip(is, outputFile);
                } else if (isZipEncoded(response)) {
                    Zipper.ZIP.unzip(is, outputFile);
                } else {
                    File container = outputFile.getParentFile();
                    if (!container.exists()) {
                        container.mkdirs();
                    }
                    Files.asByteSink(outputFile).writeFrom(is);
                }
            } else {
                outputFile.createNewFile();
            }
        } finally {
            if (response != null) {
                response.close();
            }
        }
        return true;
    }

    public boolean delete(String sessionId, String dataspacePath, String path, List<String> includes,
            List<String> excludes) throws Exception {
        StringBuffer uriTmpl = (new StringBuffer()).append(restEndpointURL)
                                                   .append(addSlashIfMissing(restEndpointURL))
                                                   .append("data/")
                                                   .append(dataspacePath)
                                                   .append('/');

        ResteasyClient client = buildResteasyClient(providerFactory);

        ResteasyWebTarget target = client.target(uriTmpl.toString()).path(path);
        if (includes != null && !includes.isEmpty()) {
            target = target.queryParam("includes", includes.toArray(new Object[includes.size()]));
        }
        if (excludes != null && !excludes.isEmpty()) {
            target = target.queryParam("excludes", excludes.toArray(new Object[excludes.size()]));
        }
        Response response = null;
        try {
            response = target.request().header("sessionid", sessionId).delete();
            if (response.getStatus() != HttpURLConnection.HTTP_NO_CONTENT) {
                if (response.getStatus() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    throw new NotConnectedRestException("User not authenticated or session timeout.");
                } else {
                    throwException(String.format("Cannot delete file(s). Status code: %s", response.getStatus()),
                                   response);
                }
            }
            return true;
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    public ListFile list(String sessionId, String dataspacePath, String pathname) throws Exception {
        StringBuffer uriTmpl = (new StringBuffer()).append(restEndpointURL)
                                                   .append(addSlashIfMissing(restEndpointURL))
                                                   .append("data/")
                                                   .append(dataspacePath)
                                                   .append('/');

        ResteasyClient client = buildResteasyClient(providerFactory);

        ResteasyWebTarget target = client.target(uriTmpl.toString()).path(pathname).queryParam("comp", "list");
        Response response = null;
        try {
            response = target.request().header("sessionid", sessionId).get();
            if (response.getStatus() != HttpURLConnection.HTTP_OK) {
                if (response.getStatus() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    throw new NotConnectedRestException("User not authenticated or session timeout.");
                } else {
                    throwException(String.format("Cannot list the specified location: %s", pathname), response);
                }
            }
            return response.readEntity(ListFile.class);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    public Map<String, Object> metadata(String sessionId, String dataspacePath, String pathname) throws Exception {
        StringBuffer uriTmpl = (new StringBuffer()).append(restEndpointURL)
                                                   .append(addSlashIfMissing(restEndpointURL))
                                                   .append("data/")
                                                   .append(dataspacePath)
                                                   .append(escapeUrlPathSegment(pathname));

        ResteasyClient client = buildResteasyClient(providerFactory);

        ResteasyWebTarget target = client.target(uriTmpl.toString());
        Response response = null;
        try {
            response = target.request().header("sessionid", sessionId).head();
            if (response.getStatus() != HttpURLConnection.HTTP_OK) {
                if (response.getStatus() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    throw new NotConnectedRestException("User not authenticated or session timeout.");
                } else {
                    throwException(String.format("Cannot get metadata from %s in %s.", pathname, dataspacePath),
                                   response);
                }
            }
            MultivaluedMap<String, Object> headers = response.getHeaders();
            Map<String, Object> metaMap = Maps.newHashMap();
            if (headers.containsKey(HttpHeaders.LAST_MODIFIED)) {
                metaMap.put(HttpHeaders.LAST_MODIFIED, headers.getFirst(HttpHeaders.LAST_MODIFIED));
            }
            return metaMap;
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    public JobIdData submitUrl(String sessionId, String workflowUrl, Map<String, String> variables,
            Map<String, String> genericInfos) throws Exception {
        String uriTmpl = restEndpointURL + addSlashIfMissing(restEndpointURL) + "scheduler/jobs/body";
        ResteasyClient client = buildResteasyClient(providerFactory);
        ResteasyWebTarget target = client.target(uriTmpl);
        // Generic infos
        if (genericInfos != null) {
            for (String key : genericInfos.keySet()) {
                target = target.queryParamNoTemplate(key, genericInfos.get(key));
            }
        }
        Response response = target.request()
                                  .header("sessionid", sessionId)
                                  .header("link", workflowUrl)
                                  .post(Entity.entity(variables, MediaType.APPLICATION_JSON));
        if (response.getStatus() != HttpURLConnection.HTTP_OK) {
            if (response.getStatus() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw new NotConnectedRestException("User not authenticated or session timeout.");
            } else {
                throwException(String.format("Job submission failed status code: %d", response.getStatus()), response);
            }
        }
        return response.readEntity(JobIdData.class);
    }

    public List<JobIdDataAndError> submitMultipleUrl(String sessionId, List<WorkflowUrlData> workflowUrlDataList)
            throws Exception {
        String uriTmpl = restEndpointURL + addSlashIfMissing(restEndpointURL) + "scheduler/jobs/body/multi";
        ResteasyClient client = buildResteasyClient(providerFactory);
        ResteasyWebTarget target = client.target(uriTmpl);
        Response response = target.request()
                                  .header("sessionid", sessionId)
                                  .post(Entity.entity(workflowUrlDataList, MediaType.APPLICATION_JSON));
        if (response.getStatus() != HttpURLConnection.HTTP_OK) {
            if (response.getStatus() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw new NotConnectedRestException("User not authenticated or session timeout.");
            } else {
                throwException(String.format("Multiple job submissions failed status code: %d", response.getStatus()),
                               response);
            }
        }
        return response.readEntity(new GenericType<List<JobIdDataAndError>>() {
        });
    }

    private JobIdData submit(String sessionId, InputStream job, MediaType mediaType, Map<String, String> variables,
            Map<String, String> genericInfos) throws NotConnectedRestException {
        String uriTmpl = restEndpointURL + addSlashIfMissing(restEndpointURL) + "scheduler/submit";

        ResteasyClient client = buildResteasyClient(providerFactory);

        ResteasyWebTarget target = client.target(uriTmpl);

        // Generic infos
        if (genericInfos != null) {
            for (String key : genericInfos.keySet()) {
                target = target.queryParamNoTemplate(key, genericInfos.get(key));
            }
        }

        // Multipart form
        MultipartFormDataOutput formData = new MultipartFormDataOutput();
        // Add job to multipart
        formData.addFormData(FILE_KEY, job, mediaType);
        // Add variables to multipart
        if (variables != null && variables.size() > 0) {
            formData.addFormData(VARIABLES_KEY, variables, MediaType.APPLICATION_JSON_TYPE);
        }
        //  Multipart form entity
        GenericEntity<MultipartFormDataOutput> entity = new GenericEntity<MultipartFormDataOutput>(formData) {
        };

        Response response = target.request()
                                  .header("sessionid", sessionId)
                                  .post(Entity.entity(entity, MediaType.MULTIPART_FORM_DATA_TYPE));

        if (response.getStatus() != HttpURLConnection.HTTP_OK) {
            if (response.getStatus() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw new NotConnectedRestException("User not authenticated or session timeout.");
            } else {
                throwException(String.format("Job submission failed status code: %d", response.getStatus()), response);
            }
        }
        return response.readEntity(JobIdData.class);
    }

    public JobIdData reSubmit(String sessionId, String jobId, Map<String, String> variables,
            Map<String, String> genericInfos) throws NotConnectedRestException {
        String uriTmpl = restEndpointURL + addSlashIfMissing(restEndpointURL) + "scheduler/jobs/" + jobId + "/resubmit";

        ResteasyClient client = buildResteasyClient(providerFactory);

        ResteasyWebTarget target = client.target(uriTmpl);

        // Variables
        if (variables != null) {
            for (String key : variables.keySet()) {
                target = target.matrixParam(key, variables.get(key));
            }
        }

        // Generic infos
        if (genericInfos != null) {
            for (String key : genericInfos.keySet()) {
                target = target.queryParam(key, genericInfos.get(key));
            }
        }

        Response response = target.request().header("sessionid", sessionId).get();

        if (response.getStatus() != HttpURLConnection.HTTP_OK) {
            if (response.getStatus() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw new NotConnectedRestException("User not authenticated or session timeout.");
            } else {
                throwException(String.format("Job submission failed status code: %d", response.getStatus()), response);
            }
        }
        return response.readEntity(JobIdData.class);
    }

    private String addSlashIfMissing(String url) {
        return url.endsWith("/") ? "" : "/";
    }

    private boolean isGZipEncoded(Response response) {
        return "gzip".equals(response.getHeaderString(HttpHeaders.CONTENT_ENCODING));
    }

    private boolean isZipEncoded(Response response) {
        return "zip".equals(response.getHeaderString(HttpHeaders.CONTENT_ENCODING));
    }

    private String escapeUrlPathSegment(String unescaped) {
        return UrlEscapers.urlPathSegmentEscaper().escape(unescaped);
    }

    public SchedulerRestInterface getScheduler() {
        return scheduler;
    }

    private void throwException(String errorMessage, Response response) {
        Exception serverException = null;
        try {
            serverException = rebuildServerSideException(response.readEntity(ExceptionToJson.class));
        } catch (Exception ignorable) {
        }
        throw new RuntimeException(errorMessage, serverException);
    }

    private static SchedulerRestInterface createRestProxy(ResteasyProviderFactory provider, String restEndpointURL,
            ClientHttpEngine httpEngine) {
        ResteasyClient client = buildResteasyClient(provider);
        ResteasyWebTarget target = client.target(restEndpointURL);
        SchedulerRestInterface schedulerRestClient = target.proxy(SchedulerRestInterface.class);
        return createExceptionProxy(schedulerRestClient);
    }

    private static SchedulerRestInterface createExceptionProxy(final SchedulerRestInterface scheduler) {
        return (SchedulerRestInterface) Proxy.newProxyInstance(SchedulerRestInterface.class.getClassLoader(),
                                                               new Class[] { SchedulerRestInterface.class },
                                                               new RestClientExceptionHandler(scheduler));
    }

    private static class RestClientExceptionHandler implements InvocationHandler {

        private final SchedulerRestInterface scheduler;

        public RestClientExceptionHandler(SchedulerRestInterface scheduler) {
            this.scheduler = scheduler;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(scheduler, args);
            } catch (InvocationTargetException targetException) {
                if (targetException.getTargetException() instanceof WebApplicationException) {
                    WebApplicationException clientException = (WebApplicationException) targetException.getTargetException();
                    try {
                        ExceptionToJson json = clientException.getResponse().readEntity(ExceptionToJson.class);
                        // here we take the server side exception and recreate it on the client side
                        throw rebuildServerSideException(json);
                    } catch (ProcessingException couldNotReadJsonException) {
                        // rethrow server side exception as runtime exception but do not transform it
                        throw clientException;
                    } catch (IllegalStateException couldNotReadJsonException) {
                        // rethrow server side exception as runtime exception but do not transform it
                        throw clientException;
                    }
                }
                // rethrow real exception as runtime (client side exception)
                throw new RuntimeException(targetException.getTargetException());
            }
        }
    }

    @Provider
    @Consumes({ MediaType.APPLICATION_JSON, "text/json" })
    @Produces({ MediaType.APPLICATION_JSON, "text/json" })
    public static class JacksonContextResolver implements ContextResolver<ObjectMapper> {
        @Override
        public ObjectMapper getContext(Class<?> objectType) {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            return objectMapper;
        }
    }

    private static Exception rebuildServerSideException(ExceptionToJson json)
            throws IllegalArgumentException, InstantiationException, IllegalAccessException, InvocationTargetException {
        Throwable serverException = json.getException();
        String exceptionClassName = json.getExceptionClass();
        String errMsg = json.getErrorMessage();
        if (errMsg == null) {
            errMsg = "An error has occurred.";
        }

        if (exceptionClassName != null) {
            Class<?> exceptionClass = toClass(exceptionClassName);
            if (exceptionClass != null) {
                // wrap the exception serialized in JSON inside an
                // instance of
                // the server exception class
                Constructor<?> constructor = getConstructor(exceptionClass, Throwable.class);
                if (constructor != null && serverException != null) {
                    return (Exception) constructor.newInstance(serverException);
                }
                constructor = getConstructor(exceptionClass, String.class);
                if (constructor != null) {
                    Exception built = (Exception) constructor.newInstance(errMsg);
                    if (serverException != null) {
                        built.setStackTrace(serverException.getStackTrace());
                    }
                    return built;
                }
            }
        }

        RuntimeException built = new RuntimeException(errMsg);
        if (serverException != null) {
            built.setStackTrace(serverException.getStackTrace());
        }
        return built;
    }

    private static Class<?> toClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Constructor<?> getConstructor(Class<?> clazz, Class<?>... paramTypes) {
        try {
            return clazz.getConstructor(paramTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static String encoding(File file) throws FileNotFoundException {
        return file.isDirectory() ? "zip" : (Zipper.isZipFile(file)) ? null : "gzip";
    }

    private static class CompressedStreamingOutput implements StreamingOutput {
        private File file;

        private List<String> includes;

        private List<String> excludes;

        public CompressedStreamingOutput(File file, List<String> includes, List<String> excludes) {
            this.file = file;
            this.includes = includes;
            this.excludes = excludes;
        }

        @Override
        public void write(OutputStream outputStream) throws IOException, WebApplicationException {
            if (file.isFile()) {
                if (Zipper.isZipFile(file)) {
                    Files.asByteSource(file).copyTo(outputStream);
                } else {
                    Zipper.GZIP.zip(file, outputStream);
                }
            } else {
                Zipper.ZIP.zip(file, includes, excludes, outputStream);
            }

        }
    }
}
