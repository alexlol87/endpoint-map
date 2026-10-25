package com.acme.inventory;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockService {

    private final StockRepository repository;
    private final EntityManager entityManager;

    public StockService(StockRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    public Map<String, Object> get(String sku) {
        StockItem item = repository.findBySku(sku).orElse(null);
        return Map.of("sku", sku, "available", item == null ? 0 : item.getQuantity());
    }

    @Transactional
    public void reserve(String sku) {
        int updated = repository.decrement(sku);
        while (updated == 0 && repository.existsBySku(sku)) {
            updated = repository.decrement(sku);
        }
    }

    @Transactional
    public void release(String sku) {
        repository.deleteReservationsBySku(sku);
    }

    /** EntityManager usage (merge / remove / createQuery). */
    @Transactional
    public void adjust(String sku) {
        StockItem item = entityManager.find(StockItem.class, sku);
        if (item.getQuantity() < 0) {
            entityManager.remove(item);
        } else {
            entityManager.merge(item);
        }
        entityManager.createQuery("delete from StockItem s where s.quantity < 0").executeUpdate();
    }

    public List<StockItem> byWarehouse(String code) {
        return repository.streamAllByWarehouse(code).toList();
    }
}
