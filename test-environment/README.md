# test-environment

Fixture workspace for endpoint-map. Point the CLI at this folder:

```bash
java -jar endpoint-map.jar discover --root test-environment --profile prod --resolutions resolutions.yml
```

Each sub-folder is one "repository". The code is never run, only scanned, but it all compiles:

```bash
cd test-environment && mvn -B package -DskipTests   # needs JDK 25 (servlet services target 25)
```

`pom.xml` here is just a build aggregator so everything compiles with one command. It is a file, not a sub-folder, so the workspace loader never sees it as a repo.

Stack: Spring Boot 4.0.9, Spring Cloud 2025.1.4 (OpenFeign), Resilience4j annotations 2.4, Spring Retry 2.0.

## Repositories

| Folder | Kind | Stack | Port / base path |
|---|---|---|---|
| lib-http-clients | library | Feign, RestClient wrapper | – |
| lib-common-clients | library | RestTemplate wrapper, `@ConfigurationProperties`, base repository | – |
| lib-reactive-clients | library | WebClient wrapper, `@HttpExchange`, `Notifier` impls | – |
| order-service | service | MVC, Java 25 | 8081, context path `/api` |
| customer-service | service | MVC, Java 25 | 8082, context path `/customers-api` |
| inventory-service | service | MVC, Java 25 | 8083 |
| pricing-service | service | MVC, Java 25 | 8084 |
| payment-service | service | MVC, Java 25 | 8085 |
| shipping-service | service | MVC, Java 25 | 8086 |
| billing-service | service | MVC, Java 25 | 8087 |
| notification-service | service | MVC, Java 25 | 8088 |
| report-service | service | MVC, Java 25 | 8089 |
| tax-service | service | MVC, Java 25 | 8090, context path `/tax-api` |
| catalog-service | service | WebFlux, Java 21 | 9081 |
| cart-service | service | WebFlux, Java 21 | 9082 |
| search-service | service | WebFlux functional (builder), Java 21 | 9083 |
| recommendation-service | service | WebFlux functional (static), Java 21 | 9084 |
| review-service | service | WebFlux, Java 21 | 9085 |
| user-profile-service | service | WebFlux, Java 21 | 9086, base path `/profile-api` |
| audit-service | service | WebFlux, Java 21 | 9087 |
| analytics-service | service | WebFlux, Java 21 | 9088 |
| loyalty-service | service | WebFlux annotated + functional, Java 21 | 9089 |
| warehouse-service | service | WebFlux, Java 21 | 9090 |
| infra-network-terraform, infra-databases-terraform, infra-k8s-terraform | non-Java | Terraform | must be skipped |
| data-pipeline-py, ml-scoring-py, ops-scripts-py | non-Java | Python | must be skipped |

## Where each detection case lives

### Inbound endpoints
| Case | Location |
|---|---|
| Class prefix + verb annotations, multiple paths per method (`{"", "/search"}`) | order-service `OrderController` |
| Class-level prefix given as an array (each endpoint appears twice) | inventory-service `InventoryController` |
| Path from a constant / constant concatenation, regex path variable | inventory-service `InventoryController` |
| `@RequestMapping(method = {GET, PATCH})` | customer-service `CustomerController.preferences` |
| Plain `@Controller` + `@ResponseBody`, single `method = RequestMethod.POST`, prefix and path from another class's constants, unmapped public method | tax-service `TaxAdminController` / `TaxPaths` |
| No class prefix, prefix without a leading slash (`"payments"`, `"charge"`) | pricing-service `PriceController`, payment-service `PaymentController` |
| Controller implementing an `@HttpExchange` interface | shipping-service `ShipmentApi` / `ShipmentController`; tax-service `TaxController` implements lib-common-clients `TaxRatesApi` |
| Same simple class name (`ApiPaths`) in two repos with different values, used as the class prefix | billing-service / notification-service `ApiPaths` |
| Mapping inherited from an abstract base controller | warehouse-service `BaseController` / `WarehouseController` |
| Servlet context path (yml and .properties) | order-service, customer-service, tax-service |
| WebFlux base path (annotated and functional routes) | user-profile-service `application.properties` |
| Builder routes: `route().path(..)`, `.nest(path(..))` 3 levels deep, `accept(..)`, lambda handler, `.add(otherRouter())` | search-service `SearchRoutes.searchRouter` / `adminRoutes` |
| Routes the walker cannot understand (built in a loop) -> report | search-service `SearchRoutes.facetRouter` |
| Static routes: `route(..).andRoute(..)`, `path(..).and(method(GET))`, `andNest`, `nest(path, route(..))`, `.and(privateMethod())`, lambda | recommendation-service `RecommendationRouter` |
| Router split into parts: local `String`/`RequestPredicate` variables, a helper filling `RouterFunctions.Builder`, `.nest(.., this::trendingRoutes)`, handler injected by interface (`TrendingHandler` -> `TrendingHandlerImpl`), `nest(POST(..), route(path(..), ..))` gives POST | recommendation-service `RecommendationSplitRoutes` |
| Annotated and functional endpoints in one service | loyalty-service |
| Functional routes under `spring.webflux.base-path`, paths from constants, Supplier-style `.path(CONST, () -> route(..).andRoute(..))`, block-body lambda handler | user-profile-service `ProfileRoutes` |

### OpenAPI files
| File | Mismatch |
|---|---|
| order-service/openapi.json | `GET /orders/{id}/invoice` only in OpenAPI; `POST /orders/{id}/cancel` only in code |
| pricing-service/api-docs.json | `POST /prices/bulk` only in OpenAPI; `POST /prices/quote` only in code |
| report-service/swagger.json (Swagger 2) | `GET /reports/{type}/pdf` only in OpenAPI; `POST /reports/export` only in code |
| catalog-service/openapi.json | `PUT /catalog/products/{sku}` only in OpenAPI; `POST .../bulk` only in code |
| search-service/openapi-search.json | `/search/facets/*` only in OpenAPI (generated in a loop in code) |
| audit-service/openapi/openapi.json | matches code exactly (control case) |
| payment-service/docs/external/openapi-stripe.json | external API (api.stripe.com, once `${PAYMENT_HOST}` is resolved to it): `POST /v1/charges`, `POST /v1/refunds` matched; `GET /v1/charges/{id}` (`PaymentGateway.refund`) is not in it -> reported |
| audit-service/openapi/openapi-siem-vendor.json | external API (api.siem-vendor.example.com): both calls matched, `GET /v2/events/{id}` to template `/v2/events/{eventId}` |
| ml-scoring-py/openapi.json | belongs to a non-Java repo |

### Outbound HTTP
| Case | Location |
|---|---|
| Feign, name only, no url -> `feign:pricing-service` | lib-http-clients `PricingFeignClient` |
| Feign, `@FeignClient("name")` value form -> `feign:user-profile-service` | notification-service `UserProfileClient` |
| Feign, `${property}` url + `path` prefix + `@RequestMapping(method=PATCH)` | lib-http-clients `InventoryFeignClient` |
| Feign, literal url that includes the target's context path | lib-http-clients `CustomerFeignClient` |
| Feign, env-var url `${SHIPPING_SERVICE_URL}` | order-service `ShippingFeignClient` |
| Feign, env-var host plus context path `${REWARDS_HOST}/rewards-api` -> resolutions.yml key `${REWARDS_HOST}`, `/rewards-api` kept in front of the path | notification-service `RewardsClient` |
| Feign, `${property}` url set in application-prod.yml to an internal DNS name that looks public (`https://loyalty-service.prod.acme.io`) -> external by heuristic, `host:` entry in resolutions.yml | notification-service `LoyaltyClient` |
| RestTemplate: `postForEntity`, `postForObject`, `getForObject`, `getForEntity`, `patchForObject`, `exchange`, `put`, `execute`, `delete` | order-service `PaymentClient` / `RestockService`, payment-service `PaymentGateway`, lib-common-clients |
| RestTemplate root URI: `RestTemplateBuilder.rootUri(..)`, `setUriTemplateHandler(new DefaultUriBuilderFactory(..))`, bean picked by `@Qualifier` | order-service `RestClientsConfig`, `RestockService` (`POST /orders/restock/{sku}`) |
| RestClient: `baseUrl` builder, `RestClient.create(url)`, `uri(Function)` URI builder | pricing-service `PriceService`, shipping-service carriers |
| WebClient: `WebClient.create(url)`, builder `baseUrl`, two beans told apart by `@Qualifier`/bean name | catalog-service `WebClientConfig`, user-profile-service `ClientsConfig` |
| WebClient `.method(HttpMethod.PATCH)` in a static util with the bean, verb and URI from the caller; bean picked by field name among two (no `@Qualifier`); `.patch()` + `UriComponentsBuilder.fromPath(..).pathSegment(..)`; RestClient `.patch()` + `String.format`, an unset property (-> `properties`) and a URL from the request body (-> `callSites`) | review-service `moderation/*` (`POST /reviews/{id}/moderate`) |
| `@HttpExchange` client via `RestClientAdapter` | shipping-service `BillingExchangeClient` + `ClientConfig` |
| `@HttpExchange` client via `WebClientAdapter`, inline and through a factory bean | recommendation-service `ExchangeConfig`, loyalty-service `CartClientConfig` |
| `@HttpExchange` interface shared from a library | lib-reactive-clients `ShippingExchange`, used by warehouse-service |
| `@HttpExchange` client via `RestTemplateAdapter`; the library interface is implemented by another service's controller (still an outbound call) | order-service `RestClientsConfig` + `RestockService` -> lib-common-clients `TaxRatesApi` -> tax-service `TaxController` |
| Library wrapper, URL/verb passed as parameters (edge `client` = the outermost library class the service calls, `transport` = the client underneath) | billing-service -> `ApiGateway`, `InternalRestTemplateClient`; search-service, warehouse-service -> `ReactiveHttp` |
| Wrapper objects built with `new`: in a constructor (`new ReactiveHttp(WebClient.create(url))`), as a `@Bean`, inline `new ReactiveHttp(url).post(..)` through a `this(..)` constructor | warehouse-service `RestockCoordinator` / `WarehouseHttpConfig` (`POST /warehouse/restock/{sku}`) |
| Parameter chain: through 3 calls resolves (`threeLevels`), through 4 (`fourLevels`) or more (`level1`) -> callSites | billing-service `syncTenant` -> `DeepChainCaller` |
| `static final` constants, constant concatenation | lib-http-clients `HttpPaths`, used by pricing-service and billing-service |
| `@Value("${x:default}")`, `@Value("${ENV_VAR}")` | order-service `PaymentClient`, payment-service `PaymentGateway` |
| `@ConfigurationProperties` class (library) and record (service) | lib-common-clients `ServiceEndpointsProperties`, warehouse-service `WarehouseClientsProperties` |
| Profile values: `application-prod.yml` and `---` + `on-profile` | order-service, warehouse-service, pricing-service |
| `${ENV:default}` in yml | payment-service, catalog-service, billing-service |
| `UriComponentsBuilder`, `String.format`, `StringBuilder` loop over varargs | lib-common-clients `UrlTemplates` |
| Runtime-built URLs (config + request data, URL taken from the request body) | report-service `ReportService.export`, billing-service `syncTenant` |
| External hosts (Stripe, DHL, UPS, FCM, SendGrid, FX, SIEM, fraud vendor) | payment, shipping, notification, lib-reactive-clients, pricing, audit |
| Service calling itself through a library impl built by `@Bean new SmsNotifier(url)` (`/sms` also fits `/{userId}`: both edges) | notification-service `SmsNotifier` -> `POST /notifications/sms` |

### Database and Redis
Table name: `@Table(name)` (JPA or `spring.data.relational`), else `@Entity(name)`, else the entity class name in snake_case (Spring Boot's default naming: `StockItem` -> `stock_item`). Op from the method name prefix; inherited CRUD methods (`save`, `findById`, `deleteById`, `flush`) count too. Explicit names are kept as written minus quotes/backticks, with `@Table(schema)` as `schema.table`. A custom fragment method (`OrderRepositoryCustom`) is no repository call: its `*Impl` is walked instead.

| Case | Location |
|---|---|
| JPA `JpaRepository`, `@Table`, derived queries, `@Modifying @Query` JPQL, native `@Query` | order-service |
| Custom repository fragment `OrderRepositoryCustom.purgeLines` implemented by `OrderRepositoryImpl` (`EntityManager` native DELETE, #11): walked, no `OrderRepository.purgeLines` edge | order-service `DELETE /api/orders/{id}` |
| JPA `CrudRepository`, table from `@Entity(name)`, native query with JOIN | customer-service |
| JPA bare `Repository` + `PagingAndSortingRepository`, table = class name (`stock_item`), `exists`/`stream` prefixes, `EntityManager` | inventory-service |
| `JdbcTemplate` and `JdbcClient` raw SQL (SELECT / INSERT / UPDATE / DELETE) | report-service |
| R2DBC `ReactiveCrudRepository` + `@Query` JOIN | catalog-service, loyalty-service |
| R2DBC `R2dbcRepository` | user-profile-service |
| R2DBC `@Modifying @Query`, `@Table` without a name (`audit_event`) | audit-service |
| JPA repository extending a library base repository (`AuditedRepository<E, K> extends ListCrudRepository<E, K>`), entity without `@Table` (`tax_rate_snapshot`), inherited `save` + `remove` prefix, 5 hops down the checkout chain | order-service `TaxRateSnapshotRepository` <- `TaxRestUtils`, lib-common-clients `AuditedRepository` |
| `DatabaseClient.sql(..)`, `R2dbcEntityTemplate.insert/delete` | review-service |
| `StringRedisTemplate` opsForValue / expire / hasKey, `@Cacheable/@CachePut/@CacheEvict` | customer-service |
| `RedisTemplate` opsForHash / opsForList / opsForZSet, constant key, `@Cacheable("name")` | tax-service |
| `ReactiveRedisTemplate` opsForHash / expire / delete / opsForValue | cart-service |
| `ReactiveStringRedisTemplate` opsForList / opsForValue.increment / opsForZSet | analytics-service |
| `ReactiveStringRedisTemplate` opsForSet / opsForHash + `@Cacheable` on a Mono method | warehouse-service |

### Control flow
| Case | Location |
|---|---|
| if/else, for-each, try/catch/finally, arrow switch, ternary, method ref in forEach | order-service `OrderService.create/cancel/list` |
| Classic switch with break/default, switch expression with `yield` | pricing-service `PriceService` |
| `while`, if/else | inventory-service `StockService` |
| `do/while`, `stream().filter().forEach()`, ternary URL choice | report-service `ReportService.build` |
| `@CircuitBreaker(fallbackMethod)` where the fallback makes its own HTTP call, `@Retry` | pricing-service `taxRate` / `defaultTaxRate` |
| `@CircuitBreaker` with a DB fallback | order-service `priceOf` / `priceFallback` |
| `@Retryable` + `@Recover` | payment-service `PaymentGateway` |
| `flatMap`, `concatMap`, `filter`, `switchIfEmpty` | catalog-service `ProductService` |
| `Mono.zip`, `switchIfEmpty`, `defaultIfEmpty`, `onErrorReturn` | cart-service `CartService` |
| `onErrorResume`, `retryWhen`, `retry`, `then` chains | review-service, warehouse-service |
| `Flux.zip`, `retryWhen`, `filter`, `Mono.fromCallable` over blocking RestClient | analytics-service |
| `Mono.when`, `zipWith`, `flatMapMany` | recommendation-service, user-profile-service |
| Interface with `@Primary` + `@Qualifier` impls | shipping-service `CarrierGateway` (Dhl primary, Ups qualified) |
| Interface with two conditional impls and no qualifier -> oneOf | shipping-service `LabelPrinter` |
| Library interface, impls from library `@Bean`s and a local `@Primary`, `@Qualifier("sms")`, `List<Notifier>` | notification-service + lib-reactive-clients `Notifier` |
| Cross-service cycle (MVC) | order `GET /orders/by-customer/{id}/summary` <-> customer `GET /customers/{id}/orders-summary` |
| Cross-service cycle (WebFlux) | search `GET /search/products/{sku}/similar` <-> analytics `GET /analytics/trending` |
| Cross-service cycle (functional route + `@HttpExchange`) | loyalty `POST /loyalty/{userId}/simulate` <-> cart `GET /carts/{userId}/points-preview` |
| Self-recursion | analytics-service `AnalyticsService.exportAll` |

### Deep call chains (controller -> handler -> service -> other services -> utils)
| Case | Location |
|---|---|
| MVC: controller -> handler interface (one `@Component` impl) -> `CheckoutService` -> `OrderPricingService` -> `OrderTaxService` -> `TaxRestUtils` (`@Component` util doing a RestTemplate call 5 hops down, URL via static `UrlUtils.join` + `HttpPaths` constant; the same util writes `tax_rate_snapshot` through a repository) | order-service `POST /orders/{id}/checkout` |
| MVC: same chain, `CheckoutService` -> static library helper `CustomerChecks.requireActive` -> private static `load` -> `CustomerFeignClient.getCustomer` (Feign client passed as an argument; via has 5 hops) | order-service `CheckoutService`, lib-http-clients `CustomerChecks` |
| MVC: same chain, second branch -> `FulfilmentService` -> pure static `ShippingPayloadUtils`, then Feign + RestTemplate; pure static `MoneyUtils` (no I/O, nothing to report) | order-service `CheckoutService` |
| WebFlux annotated: controller -> `ProductDetailsHandler` -> `ProductDetailsService` (`Mono.zip`) -> `AvailabilityService` -> `StockLookupService` / `PriceLookupService` -> one static util `ReactiveClientUtils.getJson` called with two different `WebClient` beans (inventory vs pricing) | catalog-service `GET /catalog/products/{sku}/details` |
| WebFlux annotated: controller -> `ModerationHandler` -> `ModerationService` -> `ProfileSync` / `StockSync` / `VendorSync` -> static `WebClientCalls.send(client, HttpMethod.PATCH, ModerationPaths.profile(userId))` (URL from a static `join` helper) | review-service `POST /reviews/{id}/moderate` |
| WebFlux functional: route -> `SearchHandler.bundle` -> `BundleService` -> `RelatedProductsService` -> path from static `SearchPathUtils` -> catalog `GET /catalog/products/{sku}/details` (deep chain calling another deep chain) | search-service `GET /search/products/{sku}/bundle` |

### Robustness
| Case | Location |
|---|---|
| Unparseable Java file (excluded from the Maven build by the aggregator's `Unparseable*.java` rule) | report-service `legacy/UnparseableLegacyExporter.java` |
| Terraform repos, one with a Spring-looking `application.yml` | infra-*-terraform |
| Python repos: Spring annotations inside a docstring, FastAPI `openapi.json`, an invalid `.py` file | data-pipeline-py, ml-scoring-py, ops-scripts-py |
