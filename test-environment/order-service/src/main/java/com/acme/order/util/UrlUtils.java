package com.acme.order.util;

/** Static URL builder: the path constant passes through here before reaching RestTemplate. */
public final class UrlUtils {

    private UrlUtils() {
    }

    public static String join(String baseUrl, String path) {
        return baseUrl.endsWith("/") ? baseUrl + path.substring(1) : baseUrl + path;
    }
}
