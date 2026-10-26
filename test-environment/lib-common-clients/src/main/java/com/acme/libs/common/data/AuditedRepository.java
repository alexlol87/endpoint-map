package com.acme.libs.common.data;

import java.util.List;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.NoRepositoryBean;

/** Shared base repository: services extend it with their entity; save/findById come from ListCrudRepository. */
@NoRepositoryBean
public interface AuditedRepository<E, K> extends ListCrudRepository<E, K> {

    List<E> findByCreatedBy(String user);
}
