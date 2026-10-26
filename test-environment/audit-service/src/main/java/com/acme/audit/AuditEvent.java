package com.acme.audit;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** No name on @Table -> class name in snake_case (audit_event). */
@Table
public record AuditEvent(@Id Long id, String type, String userId) {
}
