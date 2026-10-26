package com.acme.order.data;

import jakarta.persistence.EntityManager;

class OrderRepositoryImpl implements OrderRepositoryCustom {

    private final EntityManager entityManager;

    OrderRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public int purgeLines(long orderId) {
        return entityManager.createNativeQuery("DELETE FROM order_lines WHERE order_id = :id")
                .setParameter("id", orderId).executeUpdate();
    }
}
