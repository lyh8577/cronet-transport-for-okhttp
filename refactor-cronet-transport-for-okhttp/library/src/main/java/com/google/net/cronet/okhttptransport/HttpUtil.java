package com.google.net.cronet.okhttptransport;

import androidx.annotation.NonNull;

import org.chromium.net.UrlResponseInfo;

import java.util.ArrayList;
import java.util.HashMap;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.ResponseBody;

final class HttpUtil {
    public static final String COOKIE_HEADER_NAME = "Cookie";

    public static void receiveHeaders(UrlResponseInfo info, CookieJar cookieJar) {
        if (cookieJar == CookieJar.NO_COOKIES)
            return;

        final var url = HttpUrl.get(info.getUrl());
        final var responseHeaders = info.getAllHeaders();

        final var cookies = new ArrayList<Cookie>();
        for (final String headerKey : responseHeaders.keySet()) {
            // RFC 2965 3.2.2, key must be 'Set-Cookie2'
            // we also accept 'Set-Cookie' here for backward compatibility
            if (headerKey == null
                    || !(headerKey.equalsIgnoreCase("Set-Cookie2")
                    || headerKey.equalsIgnoreCase("Set-Cookie")
            )
            ) {
                continue;
            }

            final var headerValues = responseHeaders.get(headerKey);
            if (headerValues == null)
                continue;

            for (String headerValue : headerValues) {
                final var cookie = Cookie.parse(url, headerValue);
                if (cookie != null) {
                    cookies.add(cookie);
                }
            }
        }

        if (!cookies.isEmpty()) {
            cookieJar.saveFromResponse(url, cookies);
        }
    }

    @NonNull
    public static String cookieHeader(CookieJar cookieJar, HttpUrl url, Headers headers) {
        // combine cookies
        final var cookies = cookieJar.loadForRequest(url);
        final var cookieStrings = headers.values(COOKIE_HEADER_NAME);

        final var cookieMap = new HashMap<String, String>();
        for (final var cookie : cookieStrings) {
            for (final var item : cookie.split("; ")) {
                final var index = item.indexOf('=');
                cookieMap.put(item.substring(0, index), item.substring(index + 1));
            }
        }

        for (final var cookie : cookies) {
            if (cookieMap.containsKey(cookie.name()))
                continue;

            cookieMap.put(cookie.name(), cookie.value());
        }

        if (cookieMap.isEmpty())
            return "";

        final var values = new StringBuilder();
        var index = 0;
        for (final var entry : cookieMap.entrySet()) {
            if (index > 0)
                values.append("; ");
            values.append(entry.getKey()).append('=').append(entry.getValue());
            index++;
        }

        return values.toString();
    }

    public static void closeQuietly(ResponseBody body) {
        try {
            body.close();
        } catch (RuntimeException rethrown) {
            throw rethrown;
        } catch (Exception ignore) {
        }
    }
}
