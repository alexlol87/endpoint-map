package com.endpointmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Black-box: database tables reached through Spring Data JPA and R2DBC repositories (issue #10). */
class RepositoryTablesTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path ENV = Path.of("..", "test-environment");

    @TempDir
    static Path dir;
    static Map<String, JsonNode> nodes;
    static List<JsonNode> edges;
    static String resolutions;
    static String report;

    @BeforeAll
    static void discoverAndMap() throws Exception {
        Path res = dir.resolve("resolutions.yml");
        run("discover", ENV, "--resolutions", res.toString());
        resolutions = Files.readString(res);
        report = Files.readString(dir.resolve("discover-report.md"));
        run("map", ENV, "--resolutions", res.toString(), "--out", dir.resolve("out").toString());
        JsonNode graph = JSON.readTree(dir.resolve("out/graph.json").toFile());
        nodes = byId(graph);
        edges = list(graph.get("edges"));
    }

    @Test
    void dbNodesCarryTechAndTableFromTableThenEntityThenSnakeCaseClassName() {
        assertEquals(new TreeSet<>(List.of("db:audit-service:audit_event", "db:catalog-service:products",
                "db:customer-service:customer_accounts", "db:inventory-service:stock_item", "db:loyalty-service:loyalty_points",
                "db:order-service:orders", "db:order-service:tax_rate_snapshot", "db:user-profile-service:user_profiles")),
                new TreeSet<>(nodes.keySet().stream().filter(id -> id.startsWith("db:")).toList()));
        node("db:order-service:orders", "jpa", "orders");                     // jakarta @Table(name)
        node("db:customer-service:customer_accounts", "jpa", "customer_accounts"); // @Entity(name), no @Table
        node("db:inventory-service:stock_item", "jpa", "stock_item");          // bare @Entity: StockItem -> stock_item
        node("db:order-service:tax_rate_snapshot", "jpa", "tax_rate_snapshot");
        node("db:catalog-service:products", "r2dbc", "products");              // relational @Table("products")
        node("db:loyalty-service:loyalty_points", "r2dbc", "loyalty_points");
        node("db:user-profile-service:user_profiles", "r2dbc", "user_profiles"); // R2dbcRepository
        node("db:audit-service:audit_event", "r2dbc", "audit_event");          // @Table without a name
    }

    @Test
    void jpaRepositoriesGiveOpAndRepoMethodByNamePrefixIncludingInheritedCrudMethods() {
        // JpaRepository: derived queries and methods only Spring declares (findAll, findById, save, flush, deleteById)
        db("order-service GET /api/orders", "orders", "read", "OrderRepository.findAll");
        db("order-service GET /api/orders", "orders", "read", "OrderRepository.findByCustomerId");
        db("order-service GET /api/orders/{id}", "orders", "read", "OrderRepository.findById");
        db("order-service GET /api/orders/by-customer/{customerId}/summary", "orders", "read", "OrderRepository.countByCustomerId");
        db("order-service POST /api/orders", "orders", "write", "OrderRepository.save");
        db("order-service POST /api/orders", "orders", "write", "OrderRepository.flush");
        db("order-service DELETE /api/orders/{id}", "orders", "delete", "OrderRepository.deleteById");
        // a custom fragment method (OrderRepositoryCustom, hand-written in OrderRepositoryImpl) is no repository call
        assertTrue(edges.stream().noneMatch(e -> e.get("repoMethod") != null && e.get("repoMethod").asText().contains("purgeLines")),
                edges.toString());
        db("order-service POST /api/orders/{id}/cancel", "orders", "write", "OrderRepository.updateStatus"); // @Modifying @Query
        // CrudRepository + a native @Query (op from the name for now)
        db("customer-service GET /customers-api/customers/{id}", "customer_accounts", "read", "CustomerRepository.findById");
        db("customer-service PUT /customers-api/customers/{id}", "customer_accounts", "write", "CustomerRepository.save");
        db("customer-service GET /customers-api/customers/{id}/preferences", "customer_accounts", "read",
                "CustomerRepository.findPreferencesJson");
        // bare Repository + PagingAndSortingRepository: exists/stream prefixes, @Modifying without a known prefix = write
        db("inventory-service GET /inventory/{sku}", "stock_item", "read", "StockRepository.findBySku");
        db("inventory-service PUT /inventory/{sku}/reserve", "stock_item", "read", "StockRepository.existsBySku");
        db("inventory-service PUT /inventory/{sku}/reserve", "stock_item", "write", "StockRepository.decrement");
        db("inventory-service GET /inventory/warehouse/{code}/items", "stock_item", "read", "StockRepository.streamAllByWarehouse");
        db("inventory-service DELETE /inventory/{sku}/reservation", "stock_item", "delete", "StockRepository.deleteReservationsBySku");
        JsonNode e = db("order-service GET /api/orders/{id}", "orders", "read", "OrderRepository.findById");
        assertEquals("jpa", e.get("client").asText());
        assertEquals("order-service/src/main/java/com/acme/order/service/OrderService.java", e.get("file").asText());
        assertEquals(39, e.get("line").asInt());
        assertEquals(List.of("OrderService.get", "OrderRepository.findById"), strings(e.get("via")));
        assertFalse(e.has("confidence"), e.toString());
    }

    @Test
    void r2dbcRepositoriesGiveOpAndRepoMethod() {
        db("catalog-service GET /catalog/products", "products", "read", "ProductRepository.findAll");
        db("catalog-service GET /catalog/products", "products", "read", "ProductRepository.findByCategory"); // @Query JOIN
        db("catalog-service GET /catalog/products/{sku}", "products", "read", "ProductRepository.findBySku");
        db("catalog-service POST /catalog/products/bulk", "products", "write", "ProductRepository.save");
        db("loyalty-service GET /loyalty/{userId}/balance", "loyalty_points", "read", "PointsRepository.sumByUserId"); // @Query
        db("loyalty-service POST /loyalty/{userId}/simulate", "loyalty_points", "write", "PointsRepository.save");
        db("user-profile-service GET /profile-api/profiles/{userId}", "user_profiles", "read", "UserProfileRepository.findByUserId");
        db("user-profile-service PATCH /profile-api/profiles/{userId}", "user_profiles", "write", "UserProfileRepository.save");
        db("audit-service POST /audit/events", "audit_event", "write", "AuditEventRepository.save");
        assertEquals("r2dbc", db("audit-service GET /audit/events/{id}", "audit_event", "read", "AuditEventRepository.findById")
                .get("client").asText());
    }

    @Test
    void repositoryDeepInTheChainAndBuiltOnALibraryBaseRepository() {
        String checkout = "order-service POST /api/orders/{id}/checkout";
        List<String> chain = List.of("CheckoutHandlerImpl.handle", "CheckoutService.checkout", "OrderPricingService.finalTotal",
                "OrderTaxService.rateFor", "TaxRestUtils.fetchRate");
        // TaxRateSnapshotRepository extends lib-common-clients AuditedRepository<E, K> extends ListCrudRepository<E, K>
        JsonNode save = db(checkout, "tax_rate_snapshot", "write", "TaxRateSnapshotRepository.save");
        List<String> via = new ArrayList<>(chain);
        via.add("TaxRateSnapshotRepository.save");
        assertEquals(via, strings(save.get("via")));
        assertEquals("order-service/src/main/java/com/acme/order/util/TaxRestUtils.java", save.get("file").asText());
        db(checkout, "tax_rate_snapshot", "delete", "TaxRateSnapshotRepository.removeByRegion");
        db(checkout, "orders", "read", "OrderRepository.findById");
        db(checkout, "orders", "write", "OrderRepository.updateStatus");
        // the HTTP edge at the end of the same chain is still there
        assertTrue(edges.stream().anyMatch(x -> x.get("from").asText().equals(checkout)
                && x.get("to").asText().equals("tax-service GET /tax-api/tax/rates/{region}")), edges.toString());
        db("catalog-service GET /catalog/products/{sku}/details", "products", "read", "ProductRepository.findBySku");
    }

    @Test
    void dbCallsNeedNoResolution() {
        assertFalse(resolutions.contains("Repository"), resolutions);
        assertFalse(report.contains("db:"), report);
    }

    @Test
    void edgeCasesOfRepositoryTypes(@TempDir Path root) throws Exception {
        Path lib = root.resolve("lib-data/src/main/java/lib");
        // type parameters in another order than Spring's: the entity is still the right one
        write(lib.resolve("KeyFirstRepository.java"), """
                package lib;
                public interface KeyFirstRepository<K, E> extends org.springframework.data.repository.reactive.ReactiveCrudRepository<E, K> {}
                """);
        Path s = root.resolve("s-service/src/main/java/s");
        write(s.resolve("App.java"), "package s;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(s.resolve("Names.java"), "package s;\npublic class Names { public static final String T = \"line_items\"; }\n");
        write(s.resolve("LineItem.java"), """
                package s;
                import org.springframework.data.relational.core.mapping.Table;
                @Table(value = Names.T) public class LineItem {}
                """);
        write(s.resolve("Draft.java"), "package s;\n@jakarta.persistence.Table(schema = \"x\") @jakarta.persistence.Entity public class Draft {}\n");
        write(s.resolve("LineItemRepository.java"), "package s;\npublic interface LineItemRepository extends lib.KeyFirstRepository<Long, LineItem> {}\n");
        write(s.resolve("DraftRepository.java"),
                "package s;\npublic interface DraftRepository extends org.springframework.data.jpa.repository.JpaRepository<Draft, Long> {}\n");
        // a class named *Repository that is not Spring Data: walked like any other code
        write(s.resolve("CacheRepository.java"), "package s;\npublic class CacheRepository { public void save(Object o) {} }\n");
        write(s.resolve("ItemController.java"), """
                package s;
                import java.util.List;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class ItemController {
                  private final LineItemRepository items;
                  private final DraftRepository drafts;
                  private final CacheRepository cache;
                  ItemController(LineItemRepository items, DraftRepository drafts, CacheRepository cache) {
                    this.items = items; this.drafts = drafts; this.cache = cache;
                  }
                  @GetMapping("/items/{id}") public Object get(@PathVariable Long id) { cache.save(id); return items.findById(id); }
                  @DeleteMapping("/items") public void purge(@RequestBody List<Long> ids) { ids.forEach(items::deleteById); }
                  @PostMapping("/drafts") public Object draft() { drafts.hashCode(); return drafts.saveAndFlush(new Draft()); }
                }
                """);
        Path o = root.resolve("out");
        run("map", root, "--out", o.toString());
        JsonNode graph = JSON.readTree(o.resolve("graph.json").toFile());
        Map<String, JsonNode> n = byId(graph);
        assertEquals("r2dbc", n.get("db:s-service:line_items").get("tech").asText());
        assertEquals("jpa", n.get("db:s-service:x.draft").get("tech").asText()); // @Table(schema) with no name: schema.class_name
        List<String> got = list(graph.get("edges")).stream().map(e -> e.get("from").asText() + " -> " + e.get("to").asText()
                + " " + e.get("op").asText() + " " + e.get("repoMethod").asText() + " " + strings(e.get("via"))).sorted().toList();
        assertEquals(List.of(
                "s-service DELETE /items -> db:s-service:line_items delete LineItemRepository.deleteById [LineItemRepository.deleteById]",
                "s-service GET /items/{id} -> db:s-service:line_items read LineItemRepository.findById [LineItemRepository.findById]",
                "s-service POST /drafts -> db:s-service:x.draft write DraftRepository.saveAndFlush [DraftRepository.saveAndFlush]"), got);
    }

    @Test
    void entityTypeVariablesCustomImplementationsAndOtherStores(@TempDir Path root) throws Exception {
        Path lib = root.resolve("lib-data/src/main/java/lib");
        write(lib.resolve("Base.java"), """
                package lib;
                public interface Base<E, K> extends org.springframework.data.repository.ListCrudRepository<E, K> {}
                """);
        // a shared abstract CRUD service: its repositories' entity is its own type variable
        write(lib.resolve("CrudSvc.java"), """
                package lib;
                import org.springframework.data.jpa.repository.JpaRepository;
                public abstract class CrudSvc<E> {
                  protected final JpaRepository<E, Long> repo;
                  protected final Base<E, Long> base;
                  protected CrudSvc(JpaRepository<E, Long> repo, Base<E, Long> base) { this.repo = repo; this.base = base; }
                  public java.util.List<E> all() { return repo.findAll(); }
                  public java.util.List<E> all2() { return base.findAll(); }
                  public void keep(java.util.List<E> es) { es.forEach(base::save); }
                  public static <T> long size(Base<T, Long> any) { return any.count(); }
                }
                """);
        write(lib.resolve("Mid.java"), "package lib;\npublic abstract class Mid<X> extends CrudSvc<X> {\n"
                + "  protected Mid(org.springframework.data.jpa.repository.JpaRepository<X, Long> r, Base<X, Long> b) { super(r, b); }\n}\n");
        Path s = root.resolve("s-service/src/main/java/s");
        write(s.resolve("App.java"), "package s;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(s.resolve("Entities.java"), """
                package s;
                @jakarta.persistence.Entity @jakarta.persistence.Table(name = "orders")
                @jakarta.persistence.NamedQuery(name = "Order.recent", query = "select o from Order o") class Order {}
                @jakarta.persistence.Entity class Pet {}
                @jakarta.persistence.Entity @jakarta.persistence.Table(name = "\\"Audit Log\\"", schema = "ops") class Audit {}
                @jakarta.persistence.Entity(name = "`Line`") class Line {}
                @org.springframework.data.redis.core.RedisHash("sessions") class Sess {}
                @org.springframework.data.mongodb.core.mapping.Document("docs") class Doc {}
                """);
        write(s.resolve("Services.java"), """
                package s;
                @org.springframework.stereotype.Service class OrderSvc extends lib.CrudSvc<Order> {
                  OrderSvc(OrderRepository r, lib.Base<Order, Long> b) { super(r, b); }
                }
                @org.springframework.stereotype.Service class PetSvc extends lib.Mid<Pet> {
                  PetSvc(org.springframework.data.jpa.repository.JpaRepository<Pet, Long> r, lib.Base<Pet, Long> b) { super(r, b); }
                }
                """);
        write(s.resolve("Repos.java"), """
                package s;
                import org.springframework.data.jpa.repository.Query;
                import org.springframework.data.repository.CrudRepository;
                interface OrderRepositoryCustom { int archiveOld(); java.util.List<Order> findArchived(); }
                class OrderRepositoryImpl implements OrderRepositoryCustom {
                  AuditRepository audit;
                  public int archiveOld() { audit.save(new Audit()); return 0; }
                  public java.util.List<Order> findArchived() { audit.findAll(); return null; }
                }
                interface OrderRepository extends org.springframework.data.jpa.repository.JpaRepository<Order, Long>, OrderRepositoryCustom {
                  @Query("select o from Order o") java.util.List<Order> everything();
                  @org.springframework.data.jpa.repository.query.Procedure("archive_all") int archiveAll();
                  java.util.List<Order> recent();
                }
                // not Spring Data: another library's Repository types, and a plain interface whose *Impl is no implementation
                interface LegacyRepository extends com.legacy.CrudRepository<Line, Long> {}
                interface Notifier { void send(); }
                class NotifierImpl { AuditRepository audit; public void send() { audit.save(null); } }
                interface LineRepository extends CrudRepository<Line, Long> { int compact(); }
                class LineRepositoryImpl { AuditRepository audit; public int compact() { audit.deleteAll(); return 0; } }
                interface AuditRepository extends CrudRepository<Audit, Long> {}
                interface SessRepository extends CrudRepository<Sess, String> {}
                interface DocRepository extends CrudRepository<Doc, String> {}
                """);
        write(s.resolve("Ctl.java"), """
                package s;
                import lib.CrudSvc;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Ctl {
                  OrderSvc svc; lib.Base<Pet, Long> pets; OrderRepository orders; LineRepository lines; SessRepository sess; DocRepository docs;
                  PetSvc petSvc; CrudSvc<?> anySvc; LegacyRepository legacy; com.legacy.Repository<Line> legacy2; Notifier notifier;
                  @GetMapping("/petsAll") public Object petsAll() { return petSvc.all(); }
                  @GetMapping("/any") public Object any() { return anySvc.all(); }
                  @PostMapping("/keep") public void keep() { svc.keep(null); }
                  @PostMapping("/proc") public Object proc() { return orders.archiveAll(); }
                  @GetMapping("/recent") public Object recent() { return orders.recent(); }
                  @PostMapping("/legacy") public void legacy() { legacy.save(null); legacy2.save(null); notifier.send(); }
                  @GetMapping("/all") public Object all() { return svc.all(); }
                  @GetMapping("/all2") public Object all2() { return svc.all2(); }
                  @GetMapping("/pets") public Object pets() { return pets.findAll(); }
                  @GetMapping("/size") public Object size() { return CrudSvc.size(pets); }
                  @PostMapping("/archive") public Object archive() { return orders.archiveOld(); }
                  @GetMapping("/archived") public Object archived() { return orders.findArchived(); }
                  @GetMapping("/everything") public Object everything() { orders.getClass(); orders.hashCode(); return orders.everything(); }
                  @PostMapping("/compact") public Object compact() { return lines.compact(); }
                  @PostMapping("/other") public Object other() { sess.save(null); return docs.findAll(); }
                }
                """);
        Path res = root.resolve("resolutions.yml");
        run("discover", root, "--resolutions", res.toString());
        String md = Files.readString(root.resolve("discover-report.md"));
        Path o = root.resolve("out");
        run("map", root, "--resolutions", res.toString(), "--out", o.toString());
        JsonNode graph = JSON.readTree(o.resolve("graph.json").toFile());
        List<String> got = list(graph.get("edges")).stream().filter(e -> e.get("kind").asText().equals("db"))
                .map(e -> e.get("from").asText().substring("s-service ".length()) + " -> " + e.get("to").asText() + " "
                        + e.get("op").asText() + " " + e.get("repoMethod").asText() + (e.has("confidence") ? " low" : "")).sorted().toList();
        assertEquals(List.of(
                // E bound by the receiver's type on the path (svc is an OrderSvc extends CrudSvc<Order>), also through Mid<X>
                "GET /all -> db:s-service:orders read JpaRepository.findAll",
                "GET /all2 -> db:s-service:orders read Base.findAll",
                // CrudSvc<?>: nothing on the path binds E, so each subclass's entity, as a guess
                "GET /any -> db:s-service:orders read JpaRepository.findAll low",
                "GET /any -> db:s-service:pet read JpaRepository.findAll low",
                "GET /archived -> db:s-service:ops.Audit Log read AuditRepository.findAll", // fragment impl walked, even findX
                "GET /everything -> db:s-service:orders read OrderRepository.everything", // @Query; getClass/hashCode no call
                "GET /pets -> db:s-service:pet read Base.findAll",                        // field typed Base<Pet, Long>
                "GET /petsAll -> db:s-service:pet read JpaRepository.findAll",            // PetSvc extends Mid<Pet>
                "GET /recent -> db:s-service:orders read OrderRepository.recent",         // @NamedQuery on the entity
                "GET /size -> unresolved:db s-service Base.count read Base.count",        // a method's own <T>: nothing binds it
                "POST /archive -> db:s-service:ops.Audit Log write AuditRepository.save",
                "POST /compact -> db:s-service:ops.Audit Log delete AuditRepository.deleteAll", // LineRepositoryImpl by name
                "POST /keep -> db:s-service:orders write Base.save",                      // method reference on the field
                "POST /proc -> db:s-service:orders read OrderRepository.archiveAll"), got); // @Procedure: read fallback
        Map<String, JsonNode> n = byId(graph);
        assertEquals("unresolved", n.get("unresolved:db s-service Base.count").get("kind").asText());
        assertTrue(md.contains("unresolved:db s-service Base.count`: the entity of Base is the type variable T"), md);
        assertFalse(n.containsKey("db:s-service:e"), n.keySet().toString());
        assertFalse(n.containsKey("db:s-service:line") || n.containsKey("db:s-service:`Line`"), n.keySet().toString());
    }

    @Test
    void entityBoundByTheImplementingClassInheritedMethodsAndWideFanOuts(@TempDir Path root) throws Exception {
        Path lib = root.resolve("lib-data/src/main/java/lib");
        write(lib.resolve("CrudSvc.java"), """
                package lib;
                public abstract class CrudSvc<E> {
                  protected org.springframework.data.jpa.repository.JpaRepository<E, Long> repo;
                  public java.util.List<E> all() { return repo.findAll(); }
                  public java.util.List<E> all2() { return all(); }
                }
                """);
        Path s = root.resolve("s-service/src/main/java/s");
        write(s.resolve("App.java"), "package s;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        StringBuilder many = new StringBuilder("package s;\n");
        for (int i = 0; i < 6; i++) { // more entities on one base than the per-path URL cap
            many.append("@jakarta.persistence.Entity @jakarta.persistence.Table(name = \"t").append(i).append("\") class E").append(i).append(" {}\n")
                    .append("@org.springframework.stereotype.Service class Svc").append(i).append(" extends lib.CrudSvc<E").append(i).append("> {}\n");
        }
        write(s.resolve("Many.java"), many.toString());
        write(s.resolve("Shapes.java"), """
                package s;
                import org.springframework.web.bind.annotation.*;
                @jakarta.persistence.Entity @jakarta.persistence.Table(name = "orders") class Order {}
                interface OrderService { java.util.List<Order> all(); java.util.List<Order> mine(); }
                @org.springframework.stereotype.Service
                class OrderServiceImpl extends lib.CrudSvc<Order> implements OrderService {
                  public java.util.List<Order> mine() { return all2(); }
                }
                abstract class CrudController<E> {
                  protected lib.CrudSvc<E> svc;
                  @GetMapping("/list") public Object list() { return svc.all(); }
                }
                @RestController @RequestMapping("/g/orders") class OrderController extends CrudController<Order> {}
                @RestController @RequestMapping("/g/t1") class E1Controller extends CrudController<E1> {}
                interface PingService { String ping(); }
                abstract class BasePing {
                  org.springframework.web.client.RestTemplate rt;
                  public String ping() { return rt.getForObject("http://ping-service/p", String.class); }
                }
                @org.springframework.stereotype.Service class PingServiceImpl extends BasePing implements PingService {}
                @RestController
                class Ctl {
                  OrderService os; lib.CrudSvc<?> any; PingService ping;
                  @GetMapping("/if/mine") public Object mine() { return os.mine(); }
                  @GetMapping("/if/all") public Object all() { return os.all(); }
                  @GetMapping("/any") public Object any() { return any.all2(); }
                  @GetMapping("/ping") public Object ping() { return ping.ping(); }
                  @GetMapping("/both") public Object both() { any.all2(); return os.all(); }
                }
                """);
        Path res = root.resolve("resolutions.yml");
        run("discover", root, "--resolutions", res.toString());
        Path o = root.resolve("out");
        run("map", root, "--resolutions", res.toString(), "--out", o.toString());
        JsonNode graph = JSON.readTree(o.resolve("graph.json").toFile());
        List<String> got = list(graph.get("edges")).stream()
                .map(e -> e.get("from").asText().substring("s-service ".length()) + " -> " + e.get("to").asText()
                        + (e.has("confidence") ? " low" : "")).sorted().toList();
        List<String> want = new ArrayList<>(List.of(
                // CrudSvc<?>: each subclass's table as a guess, never collapsed into an unresolved HTTP call
                "GET /any -> db:s-service:orders low", "GET /any -> db:s-service:t0 low", "GET /any -> db:s-service:t1 low",
                "GET /any -> db:s-service:t2 low", "GET /any -> db:s-service:t3 low", "GET /any -> db:s-service:t4 low",
                "GET /any -> db:s-service:t5 low",
                // the same repository call guessed (any) and certain (os): one edge per table, the certain one wins
                "GET /both -> db:s-service:orders", "GET /both -> db:s-service:t0 low", "GET /both -> db:s-service:t1 low",
                "GET /both -> db:s-service:t2 low", "GET /both -> db:s-service:t3 low", "GET /both -> db:s-service:t4 low",
                "GET /both -> db:s-service:t5 low",
                "GET /g/orders/list -> db:s-service:orders",   // inherited handler: E from the controller it was found on
                "GET /g/t1/list -> db:s-service:t1",
                "GET /if/all -> db:s-service:orders",          // interface method implemented only by the inherited CrudSvc.all
                "GET /if/mine -> db:s-service:orders",         // this-call in OrderServiceImpl: E from its own extends clause
                "GET /ping -> external:http://ping-service GET /p")); // inherited from BasePing
        assertEquals(want, got);
        assertFalse(Files.readString(res).contains("CrudSvc"), Files.readString(res));
    }

    @Test
    void genericServiceInterfaceFollowsOnlyImplementationsTheReceiverCanBe(@TempDir Path root) throws Exception {
        Path s = root.resolve("s-service/src/main/java/s");
        write(s.resolve("App.java"), "package s;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(s.resolve("X.java"), """
                package s;
                import org.springframework.web.bind.annotation.*;
                @jakarta.persistence.Entity @jakarta.persistence.Table(name = "orders") class Order {}
                @jakarta.persistence.Entity @jakarta.persistence.Table(name = "items") class Item {}
                interface CrudService<E> { java.util.List<E> all(); }
                abstract class AbstractCrudService<E> implements CrudService<E> {
                  protected org.springframework.data.jpa.repository.JpaRepository<E, Long> repo;
                  public java.util.List<E> all() { return repo.findAll(); }
                }
                interface OrderService extends CrudService<Order> {}
                interface ItemService extends CrudService<Item> {}
                @org.springframework.stereotype.Service class OrderServiceImpl extends AbstractCrudService<Order> implements OrderService {}
                @org.springframework.stereotype.Service class ItemServiceImpl extends AbstractCrudService<Item> implements ItemService {}
                abstract class CrudController<E> { CrudService<E> svc; @GetMapping("/list") Object list() { return svc.all(); } }
                @RestController @RequestMapping("/g/orders") class OrderController extends CrudController<Order> {}
                @RestController
                class Ctl {
                  OrderService os; CrudService<Item> cs; AbstractCrudService<Order> ab;
                  @GetMapping("/os") Object a() { return os.all(); }
                  @GetMapping("/cs") Object b() { return cs.all(); }
                  @GetMapping("/ab") Object c() { return ab.all(); }
                }
                """);
        Path res = root.resolve("resolutions.yml");
        run("discover", root, "--resolutions", res.toString());
        Path o = root.resolve("out");
        run("map", root, "--resolutions", res.toString(), "--out", o.toString());
        List<String> got = list(JSON.readTree(o.resolve("graph.json").toFile()).get("edges")).stream()
                .map(e -> e.get("from").asText().substring("s-service ".length()) + " -> " + e.get("to").asText()
                        + (e.has("confidence") ? " low" : "")).sorted().toList();
        // one confident edge each: no other entity's implementation, no unbound walk of the abstract base, no low twin
        assertEquals(List.of("GET /ab -> db:s-service:orders", "GET /cs -> db:s-service:items",
                "GET /g/orders/list -> db:s-service:orders", "GET /os -> db:s-service:orders"), got);
    }

    @Test
    void aQualifiedTypeArgumentMatchesTheSameTypeWrittenUnqualified(@TempDir Path root) throws Exception {
        Path s = root.resolve("s-service/src/main/java/s");
        write(s.resolve("App.java"), "package s;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n");
        write(s.resolve("X.java"), """
                package s;
                import java.util.UUID;
                interface Handler<T> { String handle(T t); }
                class LoggingHandler<T> implements Handler<T> { org.springframework.web.client.RestTemplate t; public String handle(T x) { return t.getForObject("http://audit/a", String.class); } }
                class UuidHandler implements Handler<java.util.UUID> { org.springframework.web.client.RestTemplate t; public String handle(java.util.UUID x) { return t.getForObject("http://uuid/u", String.class); } }
                @org.springframework.web.bind.annotation.RestController class Ctl { Handler<UUID> h;
                  @org.springframework.web.bind.annotation.GetMapping("/g") String g() { return h.handle(null); } }
                """);
        Path o = root.resolve("out");
        run("map", root, "--out", o.toString());
        List<String> to = list(JSON.readTree(o.resolve("graph.json").toFile()).get("edges")).stream()
                .map(e -> e.get("to").asText()).toList();
        assertTrue(to.stream().anyMatch(t -> t.contains("http://audit")), to.toString());
        assertTrue(to.stream().anyMatch(t -> t.contains("http://uuid")), to.toString());
    }

    private static void node(String id, String tech, String table) {
        JsonNode n = nodes.get(id);
        assertEquals("db", n.get("kind").asText());
        assertEquals(tech, n.get("tech").asText(), id);
        assertEquals(table, n.get("table").asText(), id);
    }

    private static JsonNode db(String from, String table, String op, String repoMethod) {
        String to = "db:" + from.split(" ")[0] + ":" + table;
        List<JsonNode> hit = edges.stream().filter(e -> e.get("from").asText().equals(from) && e.get("to").asText().equals(to)
                && e.get("repoMethod").asText("").equals(repoMethod)).toList();
        assertFalse(hit.isEmpty(), from + " -> " + to + " " + repoMethod + " missing; db edges of " + from + ": "
                + edges.stream().filter(e -> e.get("from").asText().equals(from) && e.get("kind").asText().equals("db")).toList());
        hit.forEach(e -> {
            assertEquals("db", e.get("kind").asText());
            assertEquals(op, e.get("op").asText(), e.toString());
            assertEquals(repoMethod, strings(e.get("via")).getLast());
        });
        return hit.get(0);
    }

    private static Map<String, JsonNode> byId(JsonNode graph) {
        Map<String, JsonNode> m = new HashMap<>();
        graph.get("nodes").forEach(n -> m.put(n.get("id").asText(), n));
        return m;
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> l = new ArrayList<>();
        array.forEach(l::add);
        return l;
    }

    private static List<String> strings(JsonNode array) {
        List<String> l = new ArrayList<>();
        array.forEach(x -> l.add(x.asText()));
        return l;
    }

    private static void run(String command, Path root, String... more) {
        List<String> args = new ArrayList<>(List.of(command, "--root", root.toString(), "--profile", "prod"));
        args.addAll(List.of(more));
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = Main.run(args.toArray(String[]::new), new PrintStream(o, true), new PrintStream(e, true));
        assertEquals(0, code, e.toString());
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
