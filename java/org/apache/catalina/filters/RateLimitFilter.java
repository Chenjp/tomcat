/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.catalina.filters;

import java.io.IOException;

import org.apache.catalina.util.RateLimiter;
import org.apache.catalina.util.RateLimiter.RateLimitItem;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.res.StringManager;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

/**
 * <p>
 * Servlet filter that can help mitigate Denial of Service (DoS) and Brute Force attacks by limiting the number of a
 * requests that are allowed from a single IP address within a time window (also referred to as a time bucket), e.g. 300
 * Requests per 60 seconds.
 * </p>
 * <p>
 * The filter works by incrementing a counter in a time bucket for each IP address, and if the counter exceeds the
 * allowed limit then further requests from that IP are dropped with a &quot;429 Too many requests&quot; response until
 * the bucket time ends and a new bucket starts.
 * </p>
 * <p>
 * The RateLimiter implementation can be set via the <code>rateLimiterClassName</code> init param. The default
 * implementation, <code>org.apache.catalina.util.FastRateLimiter</code>, is optimized for efficiency and low overhead
 * so it converts some configured values to more efficient values. For example, a configuration of a 60 seconds time
 * bucket is converted to 65.536 seconds. That allows for very fast bucket calculation using bit shift arithmetic. In
 * order to remain true to the user intent, the configured number of requests is then multiplied by the same ratio, so a
 * configuration of 100 Requests per 60 seconds, has the real values of 109 Requests per 65 seconds. An alternative
 * implementation, <code>org.apache.catalina.util.FastRateLimiter</code>, is intended to provide an accurate request
 * control, whose effective duration in seconds and number of requests configuration are consist with the user declared.
 * You can specify a different class as long as it implements the <code>org.apache.catalina.util.RateLimiter</code>
 * interface.
 * </p>
 * <p>
 * It is common to set up different restrictions for different URIs. For example, a login page or authentication script
 * is typically expected to get far less requests than the rest of the application, so you can add a filter definition
 * that would allow only 5 requests per 15 seconds and map those URIs to it.
 * </p>
 * <p>
 * You can set <code>enforce</code> to <code>false</code> to disable the termination of requests that exceed the allowed
 * limit. Then your application code can inspect the Request Attribute
 * <code>org.apache.catalina.filters.RateLimitFilter.Count</code> and decide how to handle the request based on other
 * information that it has, e.g. allow more requests to certain users based on roles, etc.
 * </p>
 * <p>
 * The <code>exposeHeaders</code> allows output runtime information of rate limiter via response header, is disabled by
 * default, only for http-api or debugging purpose.
 * <p>
 * <strong>WARNING:</strong> if Tomcat is behind a reverse proxy then you must make sure that the Rate Limit Filter sees
 * the client IP address, so if for example you are using the <a href="#Remote_IP_Filter">Remote IP Filter</a>, then the
 * filter mapping for the Rate Limit Filter must come <em>after</em> the mapping of the Remote IP Filter to ensure that
 * each request has its IP address resolved before the Rate Limit Filter is applied. Failure to do so will count
 * requests from different IPs in the same bucket and will result in a self inflicted DoS attack.
 * </p>
 */
public class RateLimitFilter extends FilterBase {

    /**
     * default duration in seconds
     */
    public static final int DEFAULT_BUCKET_DURATION = 60;

    /**
     * default number of requests per duration
     */
    public static final int DEFAULT_BUCKET_REQUESTS = 300;

    /**
     * default value for enforce
     */
    public static final boolean DEFAULT_ENFORCE = true;

    /**
     * default value of expose headers flag
     */
    public static final boolean DEFAULT_EXPOSE_HEADERS = false;
    /**
     * default status code to return if requests per duration exceeded
     */
    public static final int DEFAULT_STATUS_CODE = 429;

    /**
     * default status message to return if requests per duration exceeded
     */
    public static final String DEFAULT_STATUS_MESSAGE = "Too many requests";

    /**
     * request attribute that will contain the number of requests per duration
     */
    public static final String RATE_LIMIT_ATTRIBUTE_COUNT = "org.apache.catalina.filters.RateLimitFilter.Count";

    protected transient RateLimiter rateLimiter;

    private String rateLimiterClassName = "org.apache.catalina.util.FastRateLimiter";

    private int bucketRequests = DEFAULT_BUCKET_REQUESTS;

    private int bucketDuration = DEFAULT_BUCKET_DURATION;

    private boolean enforce = DEFAULT_ENFORCE;
    
    /**
     * determines output rate limit header fields defined at "draft-ietf-httpapi-ratelimit-headers-08"
     */
    private boolean exposeHeaders = DEFAULT_EXPOSE_HEADERS;
    
    /**
     * RateLimit header fields defined at <a href="https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/">ietf rate limit headers</a>
     */
    public static final String HEADER_RATE_LIMIT_POLICY = "RateLimit-Policy";
    /**
     * RateLimit header fields defined at <a href="https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/">ietf rate limit headers</a>
     */
    public static final String HEADER_RATE_LIMIT = "RateLimit";

    private int statusCode = DEFAULT_STATUS_CODE;

    private String statusMessage = DEFAULT_STATUS_MESSAGE;

    private String filterName;

    private String policyName = null;
    private transient Log log = LogFactory.getLog(RateLimitFilter.class);

    private static final StringManager sm = StringManager.getManager(RateLimitFilter.class);

    public void setBucketDuration(int bucketDuration) {
        if (bucketDuration <= 0) {
            throw new IllegalArgumentException(sm.getString("rateLimitFilter.invalidBucketDuration", bucketDuration));
        }
        this.bucketDuration = bucketDuration;
    }


    public void setBucketRequests(int bucketRequests) {
        if (bucketRequests <= 0) {
            throw new IllegalArgumentException(sm.getString("rateLimitFilter.invalidBucketRequests", bucketRequests));
        }
        this.bucketRequests = bucketRequests;
    }


    public void setEnforce(boolean enforce) {
        this.enforce = enforce;
    }


    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }


    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }


    public void setRateLimiterClassName(String rateLimiterClassName) {
        this.rateLimiterClassName = rateLimiterClassName;
    }

    /**
     * Set to <code>true</code> to output rate limit header fields defined at "draft-ietf-httpapi-ratelimit-headers-08"
     * @param exposeHeaders 
     */
    public void setExposeHeaders(boolean exposeHeaders) {
        this.exposeHeaders = exposeHeaders;
    }

    public void setPolicyName(String policyName) {
        this.policyName = policyName;
    }

    @Override
    protected boolean isConfigProblemFatal() {
        return true;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        super.init(filterConfig);

        try {
            rateLimiter = (RateLimiter) Class.forName(rateLimiterClassName).getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new ServletException(e);
        }

        rateLimiter.setDuration(bucketDuration);
        rateLimiter.setRequests(bucketRequests);
        if (policyName != null && !policyName.trim().isEmpty()) {
            rateLimiter.setPolicyName(policyName.trim());
        }
        rateLimiter.setFilterConfig(filterConfig);

        filterName = filterConfig.getFilterName();

        log.info(sm.getString("rateLimitFilter.initialized", filterName, Integer.valueOf(bucketRequests),
                Integer.valueOf(bucketDuration), Integer.valueOf(rateLimiter.getRequests()),
                Integer.valueOf(rateLimiter.getDuration()), (!enforce ? "Not " : "") + "enforcing"));
    }
    /**
     * Parse the identifier of limit factor. Currently rate limit policy is "requests per ip".  
     * @param request 
     * @return identifier of quotation
     */
    protected String parseQuotaIdentifier(ServletRequest request) {
        return request.getRemoteAddr();
    }
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse httpServletResp = (HttpServletResponse) response;

        String identifier = parseQuotaIdentifier(request);
        int reqCount = rateLimiter.increment(identifier);

        request.setAttribute(RATE_LIMIT_ATTRIBUTE_COUNT, Integer.valueOf(reqCount));

        int rateLimiterMaxRequests = rateLimiter.getRequests();
        int rateLimiterDuration = rateLimiter.getDuration();

        if (exposeHeaders) {
            httpServletResp.addHeader(HEADER_RATE_LIMIT_POLICY, rateLimiter.getPolicy());
            if (enforce) {
                int remaining =
                        (reqCount < 0 || reqCount > rateLimiterMaxRequests) ? 0 : (rateLimiterMaxRequests - reqCount);
                RateLimitItem current = new RateLimitItem(rateLimiter.getPolicyName(), remaining);
                httpServletResp.addHeader(HEADER_RATE_LIMIT, current.toString());
            }
        }

        if (reqCount > rateLimiterMaxRequests) {
            log.warn(sm.getString("rateLimitFilter.maxRequestsExceeded", filterName, Integer.valueOf(reqCount), identifier,
                    Integer.valueOf(rateLimiterMaxRequests), Integer.valueOf(rateLimiterDuration)));

            if (enforce) {
                httpServletResp.sendError(statusCode, statusMessage);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        rateLimiter.destroy();
        super.destroy();
    }


    @Override
    protected Log getLogger() {
        return log;
    }
}
