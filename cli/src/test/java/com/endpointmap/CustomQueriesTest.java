package com.endpointmap;

import static com.endpointmap.RepositoryTablesTest.byId;
import static com.endpointmap.RepositoryTablesTest.list;
import static com.endpointmap.RepositoryTablesTest.run;
import static com.endpointmap.RepositoryTablesTest.strings;
import static com.endpointmap.RepositoryTablesTest.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Black-box: tables and ops from @Query SQL/JPQL, EntityManager, R2dbcEntityTemplate, DatabaseClient and JDBC (issue #11). */
class CustomQueriesTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path dir;
    static Map<String, JsonNode> nodes;
    static List<JsonNode> edges;
    static String report;

    @BeforeAll
    static void discoverAndMap() throws Exception {
        Path res = dir.resolve("resolutions.yml");
        run("discover", RepositoryTablesTest.ENV, "--resolutions", res.toString());
        report = Files.readString(dir.resolve("discover-report.md"));
        run("map", RepositoryTablesTest.ENV, "--resolutions", res.toString(), "--out", dir.resolve("out").toString());
        JsonNode graph = JSON.readTree(dir.resolve("out/graph.json").toFile());
        nodes = byId(graph);
        edges = list(graph.get("edges"));
    }

    /** "to op repoMethod [low]" of every db edge from {@code from}. */
    static List<String> dbEdges(List<JsonNode> edges, String from) {
        return edges.stream().filter(e -> e.get("from").asText().equals(from) && e.get("kind").asText().equals("db"))
                .map(e -> e.get("to").asText() + " " + e.get("op").asText() + " " + e.get("repoMethod").asText()
                        + (e.has("confidence") ? " low" : "")).distinct().sorted().toList();
    }

    @Test
    void everyTableOfTheTestEnvironment() {
        assertEquals(new TreeSet<>(List.of("db:audit-service:audit_event", "db:catalog-service:categories", "db:catalog-service:products",
                "db:customer-service:customer_accounts", "db:customer-service:customer_preferences", "db:inventory-service:stock_audit",
                "db:inventory-service:stock_item", "db:inventory-service:stock_reservations", "db:loyalty-service:loyalty_points",
                "db:order-service:order_lines", "db:order-service:orders", "db:order-service:tax_rate_snapshot",
                "db:report-service:report_rows", "db:report-service:report_runs", "db:report-service:report_tmp",
                "db:review-service:product_ratings", "db:review-service:review_votes", "db:review-service:reviews",
                "db:review-service:reviews_archive", "db:user-profile-service:user_profiles")),
                new TreeSet<>(nodes.keySet().stream().filter(id -> id.startsWith("db:")).toList()));
    }

    @Test
    void queryAnnotationsGiveTablesAndOpFromTheirSql() {
        // native JOIN: both tables read
        assertEquals(List.of("db:customer-service:customer_accounts read CustomerRepository.findPreferencesJson",
                        "db:customer-service:customer_preferences read CustomerRepository.findPreferencesJson"),
                dbEdges(edges, "customer-service GET /customers-api/customers/{id}/preferences"));
        assertTrue(dbEdges(edges, "catalog-service GET /catalog/products").containsAll(List.of(
                "db:catalog-service:categories read ProductRepository.findByCategory",
                "db:catalog-service:products read ProductRepository.findByCategory")));
        // @Modifying native DELETE on another table than the repository's entity
        assertEquals(List.of("db:inventory-service:stock_reservations delete StockRepository.deleteReservationsBySku"),
                dbEdges(edges, "inventory-service DELETE /inventory/{sku}/reservation"));
        // @Modifying native UPDATE; JPQL update OrderEntity -> the entity's @Table "orders"
        assertTrue(dbEdges(edges, "inventory-service PUT /inventory/{sku}/reserve").contains("db:inventory-service:stock_item write StockRepository.decrement"));
        assertTrue(dbEdges(edges, "order-service POST /api/orders/{id}/cancel").contains("db:order-service:orders write OrderRepository.updateStatus"));
        assertEquals(List.of("db:loyalty-service:loyalty_points read PointsRepository.sumByUserId"),
                dbEdges(edges, "loyalty-service GET /loyalty/{userId}/balance"));
    }

    @Test
    void entityManagerCalls() {
        // the custom fragment's EntityManager native DELETE, reached through OrderRepositoryImpl
        JsonNode purge = edges.stream().filter(e -> e.get("from").asText().equals("order-service DELETE /api/orders/{id}")
                && e.get("to").asText().equals("db:order-service:order_lines")).findFirst().orElseThrow();
        assertEquals("delete", purge.get("op").asText());
        assertEquals("jpa", purge.get("client").asText());
        assertEquals(List.of("OrderService.delete", "OrderRepositoryImpl.purgeLines", "EntityManager.createNativeQuery"), strings(purge.get("via")));
        assertEquals("order-service/src/main/java/com/acme/order/data/OrderRepositoryImpl.java", purge.get("file").asText());
        assertEquals(List.of(
                "db:inventory-service:stock_audit delete EntityManager.createNativeQuery", // constant passed into a static util
                "db:inventory-service:stock_audit read EntityManager.createNativeQuery",   // INSERT INTO <runtime> SELECT FROM stock_audit
                "db:inventory-service:stock_audit read EntityManager.createQuery",         // JPQL naming two entities
                "db:inventory-service:stock_audit write EntityManager.persist",            // new StockAudit(..)
                "db:inventory-service:stock_item delete EntityManager.createQuery",        // JPQL delete from StockItem
                "db:inventory-service:stock_item delete EntityManager.remove",
                "db:inventory-service:stock_item read EntityManager.createQuery",
                "db:inventory-service:stock_item read EntityManager.find",                 // find(StockItem.class, ..)
                "db:inventory-service:stock_item write EntityManager.merge",
                "unresolved:db inventory-service EntityManager.createNativeQuery write EntityManager.createNativeQuery"),
                dbEdges(edges, "inventory-service PATCH /inventory/{sku}/quantity").stream().filter(e -> e.contains("EntityManager")).toList());
        List<String> via = edges.stream().filter(e -> e.get("from").asText().equals("inventory-service PATCH /inventory/{sku}/quantity")
                && e.get("op").asText("").equals("delete") && e.get("to").asText().endsWith("stock_audit")).map(e -> strings(e.get("via"))).findFirst().orElseThrow();
        assertEquals(List.of("StockService.adjust", "StockAuditService.record", "NativeQueries.execute", "EntityManager.createNativeQuery"), via);
        // a text block constant, EXTRACT(.. FROM ..) and a quoted 'FROM' name no table
        assertEquals(List.of("db:inventory-service:stock_audit read EntityManager.createNativeQuery",
                "db:inventory-service:stock_item read StockRepository.findBySku"), dbEdges(edges, "inventory-service GET /inventory/{sku}"));
        // a table name built at runtime: unresolved, with the SQL as the reason, never a guessed table
        JsonNode u = nodes.get("unresolved:db inventory-service EntityManager.createNativeQuery");
        assertEquals("unresolved", u.get("kind").asText());
        assertTrue(u.get("reason").asText().startsWith("table name not known from code: INSERT INTO stock_audit_{*}"), u.toString());
        assertTrue(report.contains("unresolved:db inventory-service EntityManager.createNativeQuery"), report);
        assertFalse(nodes.keySet().stream().anyMatch(id -> id.contains("stock_audit_")), nodes.keySet().toString());
    }

    @Test
    void r2dbcEntityTemplateAndDatabaseClient() {
        assertEquals(List.of("db:review-service:review_votes read DatabaseClient.sql", "db:review-service:reviews read DatabaseClient.sql",
                "db:review-service:reviews_archive read DatabaseClient.sql"), dbEdges(edges, "review-service GET /reviews/product/{sku}"));
        assertEquals(List.of("db:review-service:product_ratings write DatabaseClient.sql",
                "db:review-service:reviews write R2dbcEntityTemplate.insert"), dbEdges(edges, "review-service POST /reviews"));
        assertEquals(List.of("db:review-service:review_votes delete DatabaseClient.sql",
                "db:review-service:reviews delete R2dbcEntityTemplate.delete"), dbEdges(edges, "review-service DELETE /reviews/{id}"));
        assertEquals("r2dbc", nodes.get("db:review-service:reviews").get("tech").asText());
    }

    @Test
    void jdbcTemplateAndJdbcClient() {
        assertEquals(List.of("db:report-service:report_rows read JdbcTemplate.queryForList", "db:report-service:report_runs write JdbcClient.sql"),
                dbEdges(edges, "report-service GET /reports/{type}"));
        assertEquals(List.of("db:report-service:report_runs write JdbcTemplate.update", "db:report-service:report_tmp delete JdbcTemplate.update"),
                dbEdges(edges, "report-service POST /reports/export"));
        assertEquals("jdbc", nodes.get("db:report-service:report_tmp").get("tech").asText());
    }

    @Test
    void sqlEdgeCases(@TempDir Path root) throws Exception {
        Path s = root.resolve("s-service/src/main/java/s");
        write(s.resolve("App.java"), "package s;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(s.resolve("Entities.java"), """
                package s;
                @jakarta.persistence.Entity(name = "Purchase") @jakarta.persistence.Table(name = "orders") class Order {}
                @jakarta.persistence.Entity class LineItem {}
                class Dto {}
                """);
        write(s.resolve("OrderRepository.java"), """
                package s;
                import org.springframework.data.jpa.repository.*;
                interface OrderRepository extends JpaRepository<Order, Long> {
                  @Query("select p from Purchase p join p.items i where exists (select 1 from s.LineItem l where l.id = i.id)")
                  java.util.List<Order> withItems();
                  @Query("select o from #{#entityName} o") java.util.List<Order> spel();
                  @Query("select g from Ghost g") java.util.List<Order> ghost();
                  @Modifying @Query(value = \"""
                      WITH old AS (SELECT id FROM orders WHERE created < now() - interval '1 year')
                      UPDATE "orders" SET status = 'X' FROM old WHERE orders.id = old.id\""", nativeQuery = true)
                  int archive();
                  @Modifying @Query(value = "insert into order_totals (id, n) select o.id, count(*) from orders o join `line_item` l on l.order_id = o.id"
                      + " group by o.id on conflict (id) do update set n = excluded.n", nativeQuery = true)
                  int totals();
                  @Query(value = "SELECT * FROM orders WHERE id = :id FOR UPDATE", nativeQuery = true) Order lock(long id);
                  @Query(value = "SELECT * FROM generate_series(1, 3) g /* FROM comments */ JOIN LATERAL (SELECT 1) x ON true", nativeQuery = true)
                  int noTable();
                  @Query(value = "CALL archive_all()", nativeQuery = true) int call();
                }
                """);
        write(s.resolve("Dao.java"), """
                package s;
                import jakarta.persistence.EntityManager;
                import org.springframework.r2dbc.core.DatabaseClient;
                import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
                abstract class Dao<E> {
                  EntityManager em;
                  void save(E e) { em.persist(e); }
                }
                @org.springframework.stereotype.Repository class LineItemDao extends Dao<LineItem> {}
                class EntityManagerLike { void persist(Object o) {} }
                @org.springframework.stereotype.Service class Svc {
                  EntityManager em; DatabaseClient db; R2dbcEntityTemplate tpl; LineItemDao items; EntityManagerLike fake;
                  void run(String table, Object any, java.util.function.Supplier<String> sql) {
                    items.save(new LineItem());
                    em.createNamedQuery("Order.recent");
                    em.persist(any);
                    em.createNativeQuery("select * from " + table);
                    db.sql(sql);
                    tpl.select(Order.class).all();
                    fake.persist(null);
                  }
                }
                """);
        write(s.resolve("Ctl.java"), """
                package s;
                import org.springframework.web.bind.annotation.*;
                @RestController class Ctl {
                  OrderRepository orders; Svc svc;
                  @GetMapping("/a") Object a() { orders.withItems(); orders.spel(); return orders.ghost(); }
                  @PostMapping("/b") Object b() { orders.archive(); orders.totals(); orders.noTable(); orders.call(); return orders.lock(1); }
                  @PostMapping("/c") void c(@RequestParam String t) { svc.run(t, null, null); }
                }
                """);
        Path o = root.resolve("out");
        run("map", root, "--out", o.toString());
        JsonNode graph = JSON.readTree(o.resolve("graph.json").toFile());
        List<JsonNode> got = list(graph.get("edges"));
        assertEquals(List.of(
                "db:s-service:ghost read OrderRepository.ghost low",          // no such @Entity: snake_case name, as a guess
                "db:s-service:line_item read OrderRepository.withItems",       // fully qualified entity in a subquery
                "db:s-service:orders read OrderRepository.withItems",          // @Entity(name = "Purchase"); path join skipped
                "unresolved:db s-service OrderRepository.spel read OrderRepository.spel"), dbEdges(got, "s-service GET /a"));
        assertEquals(List.of(
                "db:s-service:line_item read OrderRepository.totals",
                "db:s-service:order_totals write OrderRepository.totals",      // ON CONFLICT DO UPDATE SET is no table
                "db:s-service:orders read OrderRepository.lock",               // FOR UPDATE is no table
                "db:s-service:orders read OrderRepository.totals",
                "db:s-service:orders write OrderRepository.archive",           // CTE: its name is no table; the target is not also read
                "unresolved:db s-service OrderRepository.call read OrderRepository.call",   // CALL: no verb read,
                "unresolved:db s-service OrderRepository.noTable read OrderRepository.noTable"), dbEdges(got, "s-service POST /b"));
        assertEquals(List.of(
                "db:s-service:line_item write EntityManager.persist",          // Dao<E>.save: E bound by LineItemDao
                "db:s-service:orders read R2dbcEntityTemplate.select",
                "unresolved:db s-service DatabaseClient.sql unknown DatabaseClient.sql",
                "unresolved:db s-service EntityManager.createNamedQuery unknown EntityManager.createNamedQuery",
                "unresolved:db s-service EntityManager.createNativeQuery read EntityManager.createNativeQuery",
                "unresolved:db s-service EntityManager.persist write EntityManager.persist"), dbEdges(got, "s-service POST /c"));
    }
}
