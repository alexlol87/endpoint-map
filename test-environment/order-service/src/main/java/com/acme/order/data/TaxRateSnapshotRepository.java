package com.acme.order.data;

import com.acme.libs.common.data.AuditedRepository;

/** Extends the library's base repository: the entity comes through its type parameter. */
public interface TaxRateSnapshotRepository extends AuditedRepository<TaxRateSnapshot, Long> {

    void removeByRegion(String region);
}
