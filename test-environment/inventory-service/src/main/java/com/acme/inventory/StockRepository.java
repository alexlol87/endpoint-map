package com.acme.inventory;

import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.Repository;

/** Extends the bare Repository marker plus PagingAndSortingRepository. */
public interface StockRepository extends Repository<StockItem, String>, PagingAndSortingRepository<StockItem, String> {

    Optional<StockItem> findBySku(String sku);

    boolean existsBySku(String sku);

    Stream<StockItem> streamAllByWarehouse(String warehouse);

    @Modifying
    @Query(value = "UPDATE stock_item SET quantity = quantity - 1 WHERE sku = :sku AND quantity > 0", nativeQuery = true)
    int decrement(String sku);

    @Modifying
    @Query(value = "DELETE FROM stock_reservations WHERE sku = :sku", nativeQuery = true)
    void deleteReservationsBySku(String sku);
}
