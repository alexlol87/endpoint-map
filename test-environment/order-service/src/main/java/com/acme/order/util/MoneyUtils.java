package com.acme.order.util;

/** Pure static helpers: no I/O, the walker must not report anything here. */
public final class MoneyUtils {

    private MoneyUtils() {
    }

    public static double withTax(double amount, double rate) {
        return amount * (1 + rate);
    }

    public static double round(double amount) {
        return Math.round(amount * 100) / 100.0;
    }
}
