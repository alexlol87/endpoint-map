package com.acme.warehouse;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Record-style @ConfigurationProperties; values differ per profile (application-prod.yml). */
@ConfigurationProperties("warehouse.clients")
public record WarehouseClientsProperties(String inventoryUrl, String shippingUrl) {
}
