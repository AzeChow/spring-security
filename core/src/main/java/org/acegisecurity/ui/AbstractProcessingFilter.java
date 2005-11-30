/* Copyright 2004, 2005 Acegi Technology Pty Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.acegisecurity.ui;

import java.io.IOException;
import java.util.Properties;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.acegisecurity.AcegiMessageSource;
import org.acegisecurity.Authentication;
import org.acegisecurity.AuthenticationException;
import org.acegisecurity.AuthenticationManager;
import org.acegisecurity.context.SecurityContextHolder;
import org.acegisecurity.event.authentication.InteractiveAuthenticationSuccessEvent;
import org.acegisecurity.ui.rememberme.NullRememberMeServices;
import org.acegisecurity.ui.rememberme.RememberMeServices;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceAware;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.util.Assert;


/**
 * Abstract processor of browser-based HTTP-based authentication requests.
 * 
 * <p>
 * This filter is responsible for processing authentication requests. If
 * authentication is successful, the resulting {@link Authentication} object
 * will be placed into the <code>SecurityContext</code>, which is guaranteed
 * to have already been created by an earlier filter.
 * </p>
 * 
 * <p>
 * If authentication fails, the <code>AuthenticationException</code> will be
 * placed into the <code>HttpSession</code> with the attribute defined by
 * {@link #ACEGI_SECURITY_LAST_EXCEPTION_KEY}.
 * </p>
 * 
 * <p>
 * To use this filter, it is necessary to specify the following properties:
 * </p>
 * 
 * <ul>
 * <li>
 * <code>defaultTargetUrl</code> indicates the URL that should be used for
 * redirection if the <code>HttpSession</code> attribute named {@link
 * #ACEGI_SECURITY_TARGET_URL_KEY} does not indicate the target URL once
 * authentication is completed successfully. eg: <code>/</code>. This will be
 * treated as relative to the web-app's context path, and should include the
 * leading <code>/</code>.
 * </li>
 * <li>
 * <code>authenticationFailureUrl</code> indicates the URL that should be used
 * for redirection if the authentication request fails. eg:
 * <code>/login.jsp?login_error=1</code>.
 * </li>
 * <li>
 * <code>filterProcessesUrl</code> indicates the URL that this filter will
 * respond to. This parameter varies by subclass.
 * </li>
 * <li>
 * <code>alwaysUseDefaultTargetUrl</code> causes successful authentication to
 * always redirect to the <code>defaultTargetUrl</code>, even if the
 * <code>HttpSession</code> attribute named {@link
 * #ACEGI_SECURITY_TARGET_URL_KEY} defines the intended target URL.
 * </li>
 * </ul>
 * 
 * <p>
 * To configure this filter to redirect to specific pages as the result of
 * specific {@link AuthenticationException}s you can do the following.
 * Configure the <code>exceptionMappings</code> property in your application
 * xml. This property is a java.util.Properties object that maps a
 * fully-qualified exception class name to a redirection url target.<br>
 * For example:<br>
 * <code> &lt;property name="exceptionMappings"&gt;<br>
 * &nbsp;&nbsp;&lt;props&gt;<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;&lt;prop&gt; key="org.acegisecurity.BadCredentialsException"&gt;/bad_credentials.jsp&lt;/prop&gt;<br>
 * &nbsp;&nbsp;&lt;/props&gt;<br>
 * &lt;/property&gt;<br>
 * </code><br>
 * The example above would redirect all {@link
 * org.acegisecurity.BadCredentialsException}s thrown, to a page in the
 * web-application called /bad_credentials.jsp.
 * </p>
 * 
 * <p>
 * Any {@link AuthenticationException} thrown that cannot be matched in the
 * <code>exceptionMappings</code> will be redirected to the
 * <code>authenticationFailureUrl</code>
 * </p>
 * 
 * <p>
 * If authentication is successful, an {@link
 * org.acegisecurity.event.authentication.InteractiveAuthenticationSuccessEvent}
 * will be published to the application context. No events will be published
 * if authentication was unsuccessful, because this would generally be
 * recorded via an <code>AuthenticationManager</code>-specific application
 * event.
 * </p>
 */
public abstract class AbstractProcessingFilter implements Filter,
    InitializingBean, ApplicationEventPublisherAware, MessageSourceAware {
    //~ Static fields/initializers =============================================

    public static final String ACEGI_SECURITY_TARGET_URL_KEY = "ACEGI_SECURITY_TARGET_URL";
    public static final String ACEGI_SECURITY_LAST_EXCEPTION_KEY = "ACEGI_SECURITY_LAST_EXCEPTION";
    protected static final Log logger = LogFactory.getLog(AbstractProcessingFilter.class);

    //~ Instance fields ========================================================

    private ApplicationEventPublisher eventPublisher;
    private AuthenticationManager authenticationManager;
    protected MessageSourceAccessor messages = AcegiMessageSource.getAccessor();
    private Properties exceptionMappings = new Properties();
    private RememberMeServices rememberMeServices = new NullRememberMeServices();

    /** Where to redirect the browser to if authentication fails */
    private String authenticationFailureUrl;

    /**
     * Where to redirect the browser to if authentication is successful but
     * ACEGI_SECURITY_TARGET_URL_KEY is <code>null</code>
     */
    private String defaultTargetUrl;

    /**
     * The URL destination that this filter intercepts and processes (usually
     * something like <code>/j_acegi_security_check</code>)
     */
    private String filterProcessesUrl = getDefaultFilterProcessesUrl();

    /**
     * If <code>true</code>, will always redirect to {@link #defaultTargetUrl}
     * upon successful authentication, irrespective of the page that caused
     * the authentication request (defaults to <code>false</code>).
     */
    private boolean alwaysUseDefaultTargetUrl = false;

    /**
     * Indicates if the filter chain should be continued prior to delegation to
     * {@link #successfulAuthentication(HttpServletRequest,
     * HttpServletResponse, Authentication)}, which may be useful in certain
     * environment (eg Tapestry). Defaults to <code>false</code>.
     */
    private boolean continueChainBeforeSuccessfulAuthentication = false;

    //~ Methods ================================================================

    public void afterPropertiesSet() throws Exception {
        Assert.hasLength(filterProcessesUrl,
            "filterProcessesUrl must be specified");
        Assert.hasLength(defaultTargetUrl, "defaultTargetUrl must be specified");
        Assert.hasLength(authenticationFailureUrl,
            "authenticationFailureUrl must be specified");
        Assert.notNull(authenticationManager,
            "authenticationManager must be specified");
        Assert.notNull(this.rememberMeServices);
    }

    /**
     * Performs actual authentication.
     *
     * @param request from which to extract parameters and perform the
     *        authentication
     *
     * @return the authenticated user
     *
     * @throws AuthenticationException if authentication fails
     */
    public abstract Authentication attemptAuthentication(
        HttpServletRequest request) throws AuthenticationException;

    /**
     * Does nothing. We use IoC container lifecycle services instead.
     */
    public void destroy() {}

    public void doFilter(ServletRequest request, ServletResponse response,
        FilterChain chain) throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest)) {
            throw new ServletException("Can only process HttpServletRequest");
        }

        if (!(response instanceof HttpServletResponse)) {
            throw new ServletException("Can only process HttpServletResponse");
        }

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (requiresAuthentication(httpRequest, httpResponse)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Request is to process authentication");
            }

            onPreAuthentication(httpRequest, httpResponse);

            Authentication authResult;

            try {
                authResult = attemptAuthentication(httpRequest);
            } catch (AuthenticationException failed) {
                // Authentication failed
                unsuccessfulAuthentication(httpRequest, httpResponse, failed);

                return;
            }

            // Authentication success
            if (continueChainBeforeSuccessfulAuthentication) {
                chain.doFilter(request, response);
            }

            successfulAuthentication(httpRequest, httpResponse, authResult);

            return;
        }

        chain.doFilter(request, response);
    }

    public String getAuthenticationFailureUrl() {
        return authenticationFailureUrl;
    }

    public AuthenticationManager getAuthenticationManager() {
        return authenticationManager;
    }

    /**
     * Specifies the default <code>filterProcessesUrl</code> for the
     * implementation.
     *
     * @return the default <code>filterProcessesUrl</code>
     */
    public abstract String getDefaultFilterProcessesUrl();

    public String getDefaultTargetUrl() {
        return defaultTargetUrl;
    }

    public Properties getExceptionMappings() {
        return new Properties(exceptionMappings);
    }

    public String getFilterProcessesUrl() {
        return filterProcessesUrl;
    }

    public RememberMeServices getRememberMeServices() {
        return rememberMeServices;
    }

    /**
     * Does nothing. We use IoC container lifecycle services instead.
     *
     * @param arg0 ignored
     *
     * @throws ServletException ignored
     */
    public void init(FilterConfig arg0) throws ServletException {}

    public boolean isAlwaysUseDefaultTargetUrl() {
        return alwaysUseDefaultTargetUrl;
    }

    public boolean isContinueChainBeforeSuccessfulAuthentication() {
        return continueChainBeforeSuccessfulAuthentication;
    }

    protected void onPreAuthentication(HttpServletRequest request,
        HttpServletResponse response) throws IOException {}

    protected void onSuccessfulAuthentication(HttpServletRequest request,
        HttpServletResponse response, Authentication authResult)
        throws IOException {}

    protected void onUnsuccessfulAuthentication(HttpServletRequest request,
        HttpServletResponse response) throws IOException {}

    /**
     * <p>
     * Indicates whether this filter should attempt to process a login request
     * for the current invocation.
     * </p>
     * 
     * <p>
     * It strips any parameters from the "path" section of the request URL
     * (such as the jsessionid parameter in
     * <em>http://host/myapp/index.html;jsessionid=blah</em>) before matching
     * against the <code>filterProcessesUrl</code> property.
     * </p>
     * 
     * <p>
     * Subclasses may override for special requirements, such as Tapestry
     * integration.
     * </p>
     *
     * @param request as received from the filter chain
     * @param response as received from the filter chain
     *
     * @return <code>true</code> if the filter should attempt authentication,
     *         <code>false</code> otherwise
     */
    protected boolean requiresAuthentication(HttpServletRequest request,
        HttpServletResponse response) {
        String uri = request.getRequestURI();
        int pathParamIndex = uri.indexOf(';');

        if (pathParamIndex > 0) {
            // strip everything after the first semi-colon
            uri = uri.substring(0, pathParamIndex);
        }

        return uri.endsWith(request.getContextPath() + filterProcessesUrl);
    }

    public void setAlwaysUseDefaultTargetUrl(boolean alwaysUseDefaultTargetUrl) {
        this.alwaysUseDefaultTargetUrl = alwaysUseDefaultTargetUrl;
    }

    public void setApplicationEventPublisher(
        ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void setAuthenticationFailureUrl(String authenticationFailureUrl) {
        this.authenticationFailureUrl = authenticationFailureUrl;
    }

    public void setAuthenticationManager(
        AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    public void setContinueChainBeforeSuccessfulAuthentication(
        boolean continueChainBeforeSuccessfulAuthentication) {
        this.continueChainBeforeSuccessfulAuthentication = continueChainBeforeSuccessfulAuthentication;
    }

    public void setDefaultTargetUrl(String defaultTargetUrl) {
        this.defaultTargetUrl = defaultTargetUrl;
    }

    public void setExceptionMappings(Properties exceptionMappings) {
        this.exceptionMappings = exceptionMappings;
    }

    public void setFilterProcessesUrl(String filterProcessesUrl) {
        this.filterProcessesUrl = filterProcessesUrl;
    }

    public void setMessageSource(MessageSource messageSource) {
        this.messages = new MessageSourceAccessor(messageSource);
    }

    public void setRememberMeServices(RememberMeServices rememberMeServices) {
        this.rememberMeServices = rememberMeServices;
    }

    protected void successfulAuthentication(HttpServletRequest request,
        HttpServletResponse response, Authentication authResult)
        throws IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("Authentication success: " + authResult.toString());
        }

        SecurityContextHolder.getContext().setAuthentication(authResult);

        if (logger.isDebugEnabled()) {
            logger.debug(
                "Updated SecurityContextHolder to contain the following Authentication: '"
                + authResult + "'");
        }

        String targetUrl = (String) request.getSession()
                                           .getAttribute(ACEGI_SECURITY_TARGET_URL_KEY);
        request.getSession().removeAttribute(ACEGI_SECURITY_TARGET_URL_KEY);

        if (alwaysUseDefaultTargetUrl == true) {
            targetUrl = null;
        }

        if (targetUrl == null) {
            targetUrl = request.getContextPath() + defaultTargetUrl;
        }

        if (logger.isDebugEnabled()) {
            logger.debug(
                "Redirecting to target URL from HTTP Session (or default): "
                + targetUrl);
        }

        onSuccessfulAuthentication(request, response, authResult);

        rememberMeServices.loginSuccess(request, response, authResult);

        // Fire event
        if (this.eventPublisher != null) {
            eventPublisher.publishEvent(new InteractiveAuthenticationSuccessEvent(
                    authResult, this.getClass()));
        }

        response.sendRedirect(response.encodeRedirectURL(targetUrl));
    }

    protected void unsuccessfulAuthentication(HttpServletRequest request,
        HttpServletResponse response, AuthenticationException failed)
        throws IOException {
        SecurityContextHolder.getContext().setAuthentication(null);

        if (logger.isDebugEnabled()) {
            logger.debug(
                "Updated SecurityContextHolder to contain null Authentication");
        }

        String failureUrl = exceptionMappings.getProperty(failed.getClass()
                                                                .getName(),
                authenticationFailureUrl);

        if (logger.isDebugEnabled()) {
            logger.debug("Authentication request failed: " + failed.toString());
        }

        try {
            request.getSession()
                   .setAttribute(ACEGI_SECURITY_LAST_EXCEPTION_KEY, failed);
        } catch (Exception ignored) {}

        onUnsuccessfulAuthentication(request, response);

        rememberMeServices.loginFail(request, response);

        response.sendRedirect(response.encodeRedirectURL(request.getContextPath()
                + failureUrl));
    }
}
