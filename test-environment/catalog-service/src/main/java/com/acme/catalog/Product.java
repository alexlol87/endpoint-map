package com.acme.catalog;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("products")
public record Product(@Id Long id, String sku, String name, String category) {
}
