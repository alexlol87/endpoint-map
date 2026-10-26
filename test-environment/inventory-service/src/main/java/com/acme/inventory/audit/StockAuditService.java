package com.acme.inventory.audit;

import com.acme.inventory.util.NativeQueries;
import jakarta.persistence.EntityManager;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Audit trail of stock changes, written straight through the EntityManager. */
@Service
public class StockAuditService {

    private final EntityManager entityManager;

    public StockAuditService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional
    public void record(String sku, int quantity) {
        entityManager.persist(new StockAudit(sku, quantity));
        NativeQueries.execute(entityManager, AuditSql.PURGE_OLD);
    }

    /** JPQL naming two entities: each maps to its table. */
    public List<StockAudit> negativeStock() {
        return entityManager.createQuery(
                "select a from StockAudit a join StockItem i on i.sku = a.sku where i.quantity < 0", StockAudit.class)
                .getResultList();
    }

    public List<?> monthly(String sku) {
        return NativeQueries.list(entityManager, AuditSql.MONTHLY, sku);
    }

    /** Rows move into a monthly partition: the table name is only known at runtime. */
    @Transactional
    public void archive(String sku) {
        String partition = "stock_audit_" + YearMonth.now().toString().replace('-', '_');
        entityManager.createNativeQuery("INSERT INTO " + partition + " SELECT * FROM stock_audit WHERE sku = :sku")
                .setParameter("sku", sku)
                .executeUpdate();
    }
}
