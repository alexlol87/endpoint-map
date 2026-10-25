package com.acme.search.bundle;

/** Static path builder: the URL is only known after following this call. */
public final class SearchPathUtils {

    private static final String PRODUCTS = "/catalog/products/";

    private SearchPathUtils() {
    }

    public static String productDetails(String sku) {
        return PRODUCTS + sku + "/details";
    }
}
