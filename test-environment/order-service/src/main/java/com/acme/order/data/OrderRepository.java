package com.acme.order.data;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    List<OrderEntity> findByCustomerId(long customerId);

    int countByCustomerId(long customerId);

    @Modifying
    @Query("update OrderEntity o set o.status = :status where o.id = :id")
    int updateStatus(@Param("id") long id, @Param("status") String status);

    @Query(value = "SELECT price FROM order_lines WHERE sku = :sku ORDER BY created_at DESC LIMIT 1", nativeQuery = true)
    double findLastPriceForSku(@Param("sku") String sku);
}
