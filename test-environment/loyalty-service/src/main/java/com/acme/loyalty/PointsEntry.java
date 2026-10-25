package com.acme.loyalty;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("loyalty_points")
public record PointsEntry(@Id Long id, String userId, int points) {
}
