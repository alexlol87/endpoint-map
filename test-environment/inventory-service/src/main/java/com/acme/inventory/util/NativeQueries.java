package com.acme.inventory.util;

import jakarta.persistence.EntityManager;
import java.util.List;

/** Static helpers running native SQL handed in by the caller. */
public final class NativeQueries {

    private NativeQueries() {
    }

    public static int execute(EntityManager em, String sql) {
        return em.createNativeQuery(sql).executeUpdate();
    }

    public static List<?> list(EntityManager em, String sql, String sku) {
        return em.createNativeQuery(sql).setParameter("sku", sku).getResultList();
    }
}
