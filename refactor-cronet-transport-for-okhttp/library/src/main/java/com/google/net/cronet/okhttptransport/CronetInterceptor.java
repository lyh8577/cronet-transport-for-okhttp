/*
 * Copyright 2022 Google LLC
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

package com.google.net.cronet.okhttptransport;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;
import static okhttp3.internal.http.HttpStatusCodesKt.HTTP_PERM_REDIRECT;
import static okhttp3.internal.http.HttpStatusCodesKt.HTTP_TEMP_REDIRECT;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.net.cronet.okhttptransport.RequestResponseConverter.CronetRequestAndOkHttpResponse;

import org.chromium.net.CronetEngine;
import org.chromium.net.UrlRequest;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.UnreadableResponseBodyKt;
import okhttp3.internal.http.HttpMethod;

/**
 * An OkHttp interceptor that redirects HTTP traffic to use Cronet instead of using the OkHttp
 * network stack.
 *
 * <p>The interceptor should be used as the last application interceptor to ensure that all other
 * interceptors are visited before sending the request on wire and after a response is returned.
 *
 * <p>The interceptor is a plug-and-play replacement for the OkHttp stack for the most part,
 * however, there are some caveats to keep in mind:
 *
 * <ol>
 *   <li>The entirety of OkHttp core is bypassed. This includes caching configuration and network
 *       interceptors.
 *   <li>Some response fields are not being populated due to mismatches between Cronet's and
 *       OkHttp's architecture. TODO(danstahr): add a concrete list).
 * </ol>
 */
public final class CronetInterceptor extends EventListener implements Interceptor/*, AutoCloseable*/ {
    private static final String TAG = "CronetInterceptor";

//    private static final int CANCELLATION_CHECK_INTERVAL_MILLIS = 500;

    private final RequestResponseConverter converter;
    private final Map<Call, UrlRequest> activeCalls = new ConcurrentHashMap<>();
//    private final ScheduledExecutorService scheduledExecutor = new ScheduledThreadPoolExecutor(1);

    private CronetInterceptor(RequestResponseConverter converter) {
        this.converter = checkNotNull(converter);

        // TODO(danstahr): There's no other way to know if the call is canceled but polling
        //  (https://github.com/square/okhttp/issues/7164).
//        ScheduledFuture<?> unusedFuture =
//                scheduledExecutor.scheduleAtFixedRate(
//                        () -> {
//                            Iterator<Entry<Call, UrlRequest>> activeCallsIterator =
//                                    activeCalls.entrySet().iterator();
//
//                            while (activeCallsIterator.hasNext()) {
//                                try {
//                                    Entry<Call, UrlRequest> activeCall = activeCallsIterator.next();
//                                    if (activeCall.getKey().isCanceled()) {
//                                        activeCallsIterator.remove();
//                                        activeCall.getValue().cancel();
//                                    }
//                                } catch (RuntimeException e) {
//                                    Log.w(TAG, "Unable to propagate cancellation status", e);
//                                }
//                            }
//                        },
//                        CANCELLATION_CHECK_INTERVAL_MILLIS,
//                        CANCELLATION_CHECK_INTERVAL_MILLIS,
//                        MILLISECONDS);
    }

    @Override
    public void canceled(@NonNull Call call) {
        final var activeUrlRequest = activeCalls.remove(call);
        if (activeUrlRequest != null) {
            activeUrlRequest.cancel();
        }
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        final var call = chain.call();
        Request request = chain.request();
        Response priorResponse = null;
        var followUpCount = 0;

        while (true) {
            try {
                if (call.isCanceled()) {
                    throw new IOException("Canceled");
                }

                CronetRequestAndOkHttpResponse requestAndOkHttpResponse =
                        converter.convert(request, chain.readTimeoutMillis(), chain.writeTimeoutMillis());

                activeCalls.put(call, requestAndOkHttpResponse.getRequest());

                requestAndOkHttpResponse.getRequest().start();
                var response = toInterceptorResponse(requestAndOkHttpResponse.getResponse(), call);
                // Clear out downstream interceptor's additional request headers, cookies, etc.
                final var builder = response
                        .newBuilder()
                        .request(request);
                if (priorResponse != null) {
                    builder.priorResponse(UnreadableResponseBodyKt.stripBody(priorResponse));
                }
                response = builder.build();

                final var followUp = followUpRequest(response);
                if (followUp == null)
                    return response;

                final var followUpBody = followUp.body();
                if (followUpBody != null && followUpBody.isOneShot())
                    return response;

                HttpUtil.closeQuietly(response.body());

                if (++followUpCount > converter.redirectStrategy.numberOfRedirectsToFollow()) {
                    throw new ProtocolException("Too many follow-up requests: $followUpCount");
                }
                request = followUp;
                priorResponse = response;
            } catch (RuntimeException | IOException e) {
                // If the response is retrieved successfully the caller is responsible for closing
                // the response, which will remove it from the active calls map.
                activeCalls.remove(call);
                throw e;
            }
        }
    }

    @Nullable
    private Request followUpRequest(Response userResponse) {
        final var responseCode = userResponse.code();
        switch (responseCode) {
            case HTTP_PERM_REDIRECT, HTTP_TEMP_REDIRECT, HTTP_MULT_CHOICE, HTTP_MOVED_PERM,
                 HTTP_MOVED_TEMP, HTTP_SEE_OTHER -> {
                return buildRedirectRequest(userResponse);
            }
            default -> {
            }
        }
        return null;
    }

    @Nullable
    private Request buildRedirectRequest(Response userResponse) {
        // Does the client allow redirects?
        if (!converter.redirectStrategy.followRedirects()) return null;

        final var method = userResponse.request().method();

        final var location = userResponse.header("Location");
        if (location == null) return null;

        // Don't follow redirects to unsupported protocols.
        final var url = userResponse.request().url().resolve(location);
        if (url == null) return null;

        // If configured, don't follow redirects between SSL and non-SSL.
        final var sameScheme = url.scheme().equals(userResponse.request().url().scheme());
        if (!sameScheme) return null;

        // Most redirects don't include a request body.
        final var requestBuilder = userResponse.request().newBuilder();
        if (HttpMethod.permitsRequestBody(method)) {
            final var responseCode = userResponse.code();
            final var maintainBody =
                    HttpMethod.INSTANCE.redirectsWithBody(method) ||
                            responseCode == HTTP_PERM_REDIRECT ||
                            responseCode == HTTP_TEMP_REDIRECT;
            if (HttpMethod.INSTANCE.redirectsToGet(method) && responseCode != HTTP_PERM_REDIRECT && responseCode != HTTP_TEMP_REDIRECT) {
                requestBuilder.method("GET", null);
            } else {
                final RequestBody requestBody;
                if (maintainBody)
                    requestBody = userResponse.request().body();
                else
                    requestBody = null;
                requestBuilder.method(method, requestBody);
            }
            if (!maintainBody) {
                requestBuilder.removeHeader("Transfer-Encoding");
                requestBuilder.removeHeader("Content-Length");
                requestBuilder.removeHeader("Content-Type");
            }
        }

        // When redirecting across hosts, drop all authentication headers. This
        // is potentially annoying to the application layer since they have no
        // way to retain them.

        if (!canReuseConnectionFor(userResponse.request().url(), url)) {
            requestBuilder.removeHeader("Authorization");
        }

        return requestBuilder.url(url).build();
    }

    /**
     * Returns true if an HTTP request for this URL and [other] can reuse a connection.
     */
    private boolean canReuseConnectionFor(HttpUrl origin, HttpUrl other) {
        return origin.host().equals(other.host()) &&
                origin.port() == other.port() &&
                origin.scheme().equals(other.scheme());
    }


    /**
     * Creates a {@link CronetInterceptor} builder.
     */
    public static Builder newBuilder(CronetEngine cronetEngine) {
        return new Builder(cronetEngine);
    }

//    @Override
//    public void close() {
//        scheduledExecutor.shutdown();
//    }

    /**
     * A builder for {@link CronetInterceptor}.
     */
    public static final class Builder
            extends RequestResponseConverterBasedBuilder<Builder, CronetInterceptor> {

        Builder(CronetEngine cronetEngine) {
            super(cronetEngine, Builder.class);
        }

        /**
         * Builds the interceptor. The same builder can be used to build multiple interceptors.
         */
        @Override
        CronetInterceptor build(RequestResponseConverter converter) {
            return new CronetInterceptor(converter);
        }
    }

    private Response toInterceptorResponse(Response response, Call call) {
        checkNotNull(response.body());

        if (response.body() instanceof CronetInterceptorResponseBody) {
            return response;
        }

        return response
                .newBuilder()
                .body(new CronetInterceptorResponseBody(response.body(), call))
                .build();
    }

    private class CronetInterceptorResponseBody extends CronetTransportResponseBody {
        private final Call call;

        private CronetInterceptorResponseBody(ResponseBody delegate, Call call) {
            super(delegate);
            this.call = call;
        }

        @Override
        void customCloseHook() {
            activeCalls.remove(call);
        }
    }
}
