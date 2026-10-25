package com.acme.libs.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @ConfigurationProperties holder for base URLs (bound from "acme.clients.*"). */
@ConfigurationProperties(prefix = "acme.clients")
public class ServiceEndpointsProperties {

    private String shippingUrl;
    private String billingUrl;
    private String notificationUrl = "http://localhost:8088";

    public String getShippingUrl() {
        return shippingUrl;
    }

    public void setShippingUrl(String shippingUrl) {
        this.shippingUrl = shippingUrl;
    }

    public String getBillingUrl() {
        return billingUrl;
    }

    public void setBillingUrl(String billingUrl) {
        this.billingUrl = billingUrl;
    }

    public String getNotificationUrl() {
        return notificationUrl;
    }

    public void setNotificationUrl(String notificationUrl) {
        this.notificationUrl = notificationUrl;
    }
}
