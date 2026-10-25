package com.acme.libs.common;

import org.springframework.web.util.UriComponentsBuilder;

/** URL building helpers: UriComponentsBuilder, String.format and concatenation. */
public final class UrlTemplates {

    private UrlTemplates() {
    }

    public static String invoiceUrl(String baseUrl, long invoiceId) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .path("/billing/invoices/{id}")
                .buildAndExpand(invoiceId)
                .toUriString();
    }

    public static String tenantUrl(String baseUrl, String tenant, String suffix) {
        return String.format("%s/tenants/%s%s", baseUrl, tenant, suffix);
    }

    public static String join(String baseUrl, String... segments) {
        StringBuilder sb = new StringBuilder(baseUrl);
        for (String s : segments) {
            sb.append('/').append(s);
        }
        return sb.toString();
    }
}
