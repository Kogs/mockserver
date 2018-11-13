package org.mockserver.mock.action;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.apache.commons.lang3.StringUtils;
import org.mockserver.client.netty.NettyHttpClient;
import org.mockserver.client.netty.SocketCommunicationException;
import org.mockserver.client.netty.SocketConnectionException;
import org.mockserver.client.netty.proxy.ProxyConfiguration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.filters.HopByHopHeaderFilter;
import org.mockserver.log.model.ExpectationMatchLogEntry;
import org.mockserver.log.model.RequestLogEntry;
import org.mockserver.log.model.RequestResponseLogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpStateHandler;
import org.mockserver.model.*;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.Set;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.ConfigurationProperties.enableCORSForAPI;
import static org.mockserver.configuration.ConfigurationProperties.enableCORSForAllResponses;
import static org.mockserver.cors.CORSHeaders.isPreflightRequest;
import static org.mockserver.log.model.MessageLogEntry.LogMessageType.*;
import static org.mockserver.model.HttpResponse.notFoundResponse;

/**
 * @author jamesdbloom
 */
public class ActionHandler {

    public static final AttributeKey<InetSocketAddress> REMOTE_SOCKET = AttributeKey.valueOf("REMOTE_SOCKET");

    private final HttpStateHandler httpStateHandler;
    private final Scheduler scheduler;
    private MockServerLogger mockServerLogger;
    private HttpResponseActionHandler httpResponseActionHandler;
    private HttpResponseTemplateActionHandler httpResponseTemplateActionHandler;
    private HttpResponseClassCallbackActionHandler httpResponseClassCallbackActionHandler;
    private HttpResponseObjectCallbackActionHandler httpResponseObjectCallbackActionHandler;
    private HttpForwardActionHandler httpForwardActionHandler;
    private HttpForwardTemplateActionHandler httpForwardTemplateActionHandler;
    private HttpForwardClassCallbackActionHandler httpForwardClassCallbackActionHandler;
    private HttpForwardObjectCallbackActionHandler httpForwardObjectCallbackActionHandler;
    private HttpOverrideForwardedRequestActionHandler httpOverrideForwardedRequestCallbackActionHandler;
    private HttpErrorActionHandler httpErrorActionHandler;

    // forwarding
    private NettyHttpClient httpClient;
    private HopByHopHeaderFilter hopByHopHeaderFilter = new HopByHopHeaderFilter();
    private HttpRequestToCurlSerializer httpRequestToCurlSerializer = new HttpRequestToCurlSerializer();

    public ActionHandler(HttpStateHandler httpStateHandler, ProxyConfiguration proxyConfiguration) {
        this.httpStateHandler = httpStateHandler;
        this.scheduler = httpStateHandler.getScheduler();
        this.mockServerLogger = httpStateHandler.getMockServerLogger();
        this.httpClient = new NettyHttpClient(proxyConfiguration);
        this.httpResponseActionHandler = new HttpResponseActionHandler();
        this.httpResponseTemplateActionHandler = new HttpResponseTemplateActionHandler(mockServerLogger);
        this.httpResponseClassCallbackActionHandler = new HttpResponseClassCallbackActionHandler(mockServerLogger);
        this.httpResponseObjectCallbackActionHandler = new HttpResponseObjectCallbackActionHandler(httpStateHandler);
        this.httpForwardActionHandler = new HttpForwardActionHandler(mockServerLogger, httpClient);
        this.httpForwardTemplateActionHandler = new HttpForwardTemplateActionHandler(mockServerLogger, httpClient);
        this.httpForwardClassCallbackActionHandler = new HttpForwardClassCallbackActionHandler(mockServerLogger, httpClient);
        this.httpForwardObjectCallbackActionHandler = new HttpForwardObjectCallbackActionHandler(httpStateHandler, httpClient);
        this.httpOverrideForwardedRequestCallbackActionHandler = new HttpOverrideForwardedRequestActionHandler(mockServerLogger, httpClient);
        this.httpErrorActionHandler = new HttpErrorActionHandler();
    }

    public void processAction(final HttpRequest request, final ResponseWriter responseWriter, final ChannelHandlerContext ctx, Set<String> localAddresses, boolean proxyThisRequest, final boolean synchronous) {
        final Expectation expectation = httpStateHandler.firstMatchingExpectation(request);
        if (request.getHeaders().containsEntry("x-forwarded-by", "MockServer")) {

            mockServerLogger.trace("Received \"x-forwarded-by\" header caused by exploratory HTTP proxy - falling back to no proxy: {}", request);
            returnNotFound(responseWriter, request);

        } else if (expectation != null && expectation.getAction() != null) {

            final Action action = expectation.getAction();
            httpStateHandler.log(new ExpectationMatchLogEntry(request, expectation));
            switch (action.getType()) {
                case RESPONSE: {
                    final HttpResponse httpResponse = (HttpResponse) action;
                    scheduler.schedule(new Runnable() {
                        public void run() {
                            HttpResponse response = httpResponseActionHandler.handle(httpResponse);
                            writeResponseActionResponse(response, responseWriter, request, action);
                        }
                    }, httpResponse.getDelay(), synchronous);
                    break;
                }
                case RESPONSE_TEMPLATE: {
                    final HttpTemplate httpTemplate = (HttpTemplate) action;
                    scheduler.schedule(new Runnable() {
                        public void run() {
                            HttpResponse response = httpResponseTemplateActionHandler.handle(httpTemplate, request);
                            writeResponseActionResponse(response, responseWriter, request, action);
                        }
                    }, httpTemplate.getDelay(), synchronous);
                    break;
                }
                case RESPONSE_CLASS_CALLBACK: {
                    final HttpClassCallback classCallback = (HttpClassCallback) action;
                    scheduler.submit(new Runnable() {
                        public void run() {
                            HttpResponse response = httpResponseClassCallbackActionHandler.handle(classCallback, request);
                            writeResponseActionResponse(response, responseWriter, request, action);
                        }
                    }, synchronous);
                    break;
                }
                case RESPONSE_OBJECT_CALLBACK: {
                    final HttpObjectCallback objectCallback = (HttpObjectCallback) action;
                    scheduler.submit(new Runnable() {
                        public void run() {
                            httpResponseObjectCallbackActionHandler.handle(ActionHandler.this, objectCallback, request, responseWriter);
                        }
                    }, synchronous);
                    break;
                }
                case FORWARD: {
                    final HttpForward httpForward = (HttpForward) action;
                    scheduler.schedule(new Runnable() {
                        public void run() {
                            final HttpForwardActionResult responseFuture = httpForwardActionHandler.handle(httpForward, request);
                            writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous);
                        }
                    }, httpForward.getDelay(), synchronous);
                    break;
                }
                case FORWARD_TEMPLATE: {
                    final HttpTemplate httpTemplate = (HttpTemplate) action;
                    scheduler.schedule(new Runnable() {
                        public void run() {
                            final HttpForwardActionResult responseFuture = httpForwardTemplateActionHandler.handle(httpTemplate, request);
                            writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous);
                        }
                    }, httpTemplate.getDelay(), synchronous);
                    break;
                }
                case FORWARD_CLASS_CALLBACK: {
                    final HttpClassCallback classCallback = (HttpClassCallback) action;
                    scheduler.submit(new Runnable() {
                        public void run() {
                            final HttpForwardActionResult responseFuture = httpForwardClassCallbackActionHandler.handle(classCallback, request);
                            writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous);
                        }
                    }, synchronous);
                    break;
                }
                case FORWARD_OBJECT_CALLBACK: {
                    final HttpObjectCallback objectCallback = (HttpObjectCallback) action;
                    scheduler.submit(new Runnable() {
                        public void run() {
                            httpForwardObjectCallbackActionHandler.handle(ActionHandler.this, objectCallback, request, responseWriter, synchronous);
                        }
                    }, synchronous);
                    break;
                }
                case FORWARD_REPLACE: {
                    final HttpOverrideForwardedRequest httpOverrideForwardedRequest = (HttpOverrideForwardedRequest) action;
                    scheduler.schedule(new Runnable() {
                        public void run() {
                            final HttpForwardActionResult responseFuture = httpOverrideForwardedRequestCallbackActionHandler.handle(httpOverrideForwardedRequest, request);
                            writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous);
                        }
                    }, httpOverrideForwardedRequest.getDelay(), synchronous);
                    break;
                }
                case ERROR: {
                    final HttpError httpError = (HttpError) action;
                    scheduler.schedule(new Runnable() {
                        public void run() {
                            httpErrorActionHandler.handle(httpError, ctx);
                            mockServerLogger.info(EXPECTATION_RESPONSE, request, "returning error:{}for request:{}for action:{}", httpError, request, action);
                        }
                    }, httpError.getDelay(), synchronous);
                    break;
                }
            }

        } else if ((enableCORSForAPI() || enableCORSForAllResponses()) && isPreflightRequest(request)) {

            responseWriter.writeResponse(request, OK);

        } else if (proxyThisRequest || (!StringUtils.isEmpty(request.getFirstHeader(HOST.toString())) && !localAddresses.contains(request.getFirstHeader(HOST.toString())))) {

            final boolean exploratoryHttpProxy = !proxyThisRequest;
            final InetSocketAddress remoteAddress = ctx != null ? ctx.channel().attr(REMOTE_SOCKET).get() : null;
            final HttpRequest clonedRequest = hopByHopHeaderFilter.onRequest(request);
            if (exploratoryHttpProxy) {
                clonedRequest.withHeader("x-forwarded-by", "MockServer");
            }
            final HttpForwardActionResult responseFuture = new HttpForwardActionResult(clonedRequest, httpClient.sendRequest(clonedRequest, remoteAddress, exploratoryHttpProxy ? 1000 : ConfigurationProperties.socketConnectionTimeout()));
            scheduler.submit(responseFuture, new Runnable() {
                public void run() {
                    try {
                        HttpResponse response = responseFuture.getHttpResponse().get();
                        if (response == null) {
                            response = notFoundResponse();
                        }
                        responseWriter.writeResponse(request, response, false);
                        if (response.containsHeader("x-forwarded-by", "MockServer")) {
                            httpStateHandler.log(new RequestLogEntry(request));
                            mockServerLogger.info(EXPECTATION_NOT_MATCHED, request, "no expectation for:{}returning response:{}", request, notFoundResponse());
                        } else {
                            httpStateHandler.log(new RequestResponseLogEntry(request, response));
                            mockServerLogger.info(FORWARDED_REQUEST, request, "returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}", response, request, httpRequestToCurlSerializer.toCurl(request, remoteAddress));
                        }
                    } catch (SocketCommunicationException sce) {
                        returnNotFound(responseWriter, request);
                    } catch (Exception ex) {
                        if (exploratoryHttpProxy && (ex.getCause() instanceof ConnectException || ex.getCause() instanceof SocketConnectionException)) {
                            mockServerLogger.trace("Failed to connect to proxied socket due to exploratory HTTP proxy for: {}falling back to no proxy: {}", request, ex.getCause());
                            returnNotFound(responseWriter, request);
                        } else {
                            mockServerLogger.error(request, ex, ex.getMessage());
                        }
                    }
                }
            }, synchronous);

        } else {

            returnNotFound(responseWriter, request);

        }
    }

    void writeResponseActionResponse(HttpResponse response, ResponseWriter responseWriter, HttpRequest request, Action action) {
        responseWriter.writeResponse(request, response, false);
        mockServerLogger.info(EXPECTATION_RESPONSE, request, "returning response:{}for request:{}for action:{}", response, request, action);
    }

    void writeForwardActionResponse(final HttpForwardActionResult responseFuture, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous) {
        scheduler.submit(responseFuture, new Runnable() {
            public void run() {
                try {
                    HttpResponse response = responseFuture.getHttpResponse().get();
                    responseWriter.writeResponse(request, response, false);
                    httpStateHandler.log(new RequestResponseLogEntry(request, response));
                    mockServerLogger.info(FORWARDED_REQUEST, request, "returning response:{}for forwarded request\n\n in json:{}\n\n in curl:{}", response, responseFuture.getHttpRequest(), httpRequestToCurlSerializer.toCurl(responseFuture.getHttpRequest()));
                } catch (Exception ex) {
                    mockServerLogger.error(request, ex, ex.getMessage());
                }
            }
        }, synchronous);
    }

    private void returnNotFound(ResponseWriter responseWriter, HttpRequest request) {
        HttpResponse response = notFoundResponse();
        if (request.getHeaders().containsEntry("x-forwarded-by", "MockServer")) {
            response.withHeader("x-forwarded-by", "MockServer");
        } else {
            httpStateHandler.log(new RequestLogEntry(request));
            mockServerLogger.info(EXPECTATION_NOT_MATCHED, request, "no expectation for:{}returning response:{}", request, notFoundResponse());
        }
        responseWriter.writeResponse(request, response, false);
    }

}
