package com.acme.inventory.audit;

/** Native statements shared by the audit code. */
final class AuditSql {

    static final String PURGE_OLD = "DELETE FROM stock_audit WHERE created_at < now() - interval '90 days'";

    /** EXTRACT(.. FROM ..) and the quoted literal name no table. */
    static final String MONTHLY = """
            SELECT EXTRACT(MONTH FROM a.created_at) AS m, count(*) -- per month
            FROM stock_audit a
            WHERE a.sku = :sku AND a.note <> 'moved FROM warehouse'
            GROUP BY 1""";

    private AuditSql() {
    }
}
