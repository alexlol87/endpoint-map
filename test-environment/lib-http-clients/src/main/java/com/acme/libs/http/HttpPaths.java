package com.acme.libs.http;

/** static final constants used to build URLs in services. */
public final class HttpPaths {
    public static final String TAX_RATES = "/tax/rates";
    public static final String TAX_RATE_FOR_REGION = TAX_RATES + "/{region}";
    public static final String SHIPMENTS = "/shipments";
    public static final String BILLING_INVOICES = "/billing/invoices";

    private HttpPaths() {
    }
}
