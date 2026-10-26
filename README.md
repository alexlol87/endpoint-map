# endpoint-map

Static analyzer that maps Spring Boot / Spring WebFlux endpoints across many repos. It reads source code only: no network, no tracing, no LLM. Output is `graph.json` plus a self-contained `map.html` you open in a browser.

Status: milestone M1. `map` detects annotated controller endpoints and Feign calls between them. `discover` is a stub. A fuller manual comes later.

## Build

Requires JDK 21 or newer (the jar targets Java 21 and runs on 21 and 25) and Maven 3.9+.

```bash
cd cli
mvn -B verify          # builds and runs the black-box tests against ../test-environment
```

This produces `cli/target/endpoint-map.jar`, a fat jar with every dependency included.

## Run

`--root` is a folder where each sub-folder is one repo. The folder name is used as the service name. A repo with a `@SpringBootApplication` class is a service, and any other Java repo is a library. Folders with no Java sources (Terraform, Python, ...) are skipped.

```bash
java -jar cli/target/endpoint-map.jar map --root test-environment --profile prod --out ./out
open out/map.html
```

- `--profile` selects `application-{profile}.yml/.properties` and `---` documents with `spring.config.activate.on-profile`. These are used for `server.servlet.context-path` and `spring.webflux.base-path`.
- `${KEY:default}` placeholders in those values are expanded from the same config, then the default. A placeholder with neither (an environment variable) becomes `{*}`.
- Class names (constants in mappings, implemented interfaces, superclasses) are resolved with the JavaParser symbol solver over the JDK and every repo's `src/main/java`, so two `ApiPaths` classes in different repos or packages don't mix. When the solver fails, the simple name is used, preferring the same repo.
- Mappings inherited from superclasses and interfaces (also interfaces that extend interfaces) are detected. `file`/`line` point at the method that handles the call.
- A mapping with no `method` (plain `@RequestMapping("/x")`) gets the verb `ANY`: it serves every verb, so call matching treats it as a wildcard. The viewer shows it grey.
- When two handlers map the same `service METHOD path` (headers/params/produces versioning, or two unresolvable `{*}` paths), they share one node: the first handler is `handler`, the rest are listed in `alsoHandledBy` and printed as a warning.
- Files that fail to parse, and config files that fail to load, are listed on stdout, and the scan continues without them.
- Each handler is traced through every call it makes (services, interfaces with their implementations, static utils, library code, method references) down to Feign client methods. An edge has `from` (endpoint), `to`, `kind` (`http`, or `external` for a host that is not one of the services), `client` (`feign`), `via` (every method entered after the handler, the Feign method last), and the `file`/`line` of the call.
- A Feign target comes from `@FeignClient(url, path)` + the method's `@*Mapping` (no `method` = GET). `url` may be a literal, a constant or `${prop:default}`, resolved with the calling service's config and profile, also for clients defined in a library. The host names a service when its first label is a repo name or a `spring.application.name` (`http://inventory-service.prod...`), or when it is `localhost` with a service's `server.port`.
- Calls are matched to endpoints by verb (`ANY` matches every verb) and path segment by segment; template variables match anything, so every candidate gets an edge.
- A Feign client without `url` (`feign:<name>`) or with a placeholder nothing defines (`${SHIPPING_SERVICE_URL}`) points to an `unresolved:<service>:<key> VERB path` node, and a known service with no matching endpoint to `unresolved:<service> VERB path`. For `url = "${REWARDS_HOST}/rewards-api"` the key is the placeholder and `/rewards-api` stays in front of the path. `map` prints them all with the reason, and reads `./resolutions.yml` when `--resolutions` is not given. Calls the symbol solver could not resolve were matched by name and arity and carry `confidence: "low"`.
- OpenAPI files (`**/openapi*`, `**/api-docs*`, `**/swagger*`, `.json`/`.yaml`/`.yml`, OpenAPI 3 or Swagger 2) are found in every Java repo, plus every OpenAPI file under `--openapi-dir <dir>` (written as `openapi-dir:<path>` when outside `--root`). Which service a file documents is decided by its content: the service whose code endpoints match most of its operations (verb and path, tried with and without the server URL path, `servers[0]` with variable defaults / `basePath`, and the service's context path), when that is at least 3 or half of them (2 for the repo holding the file or the service its server URL names). A file no service matches well enough describes an external API when its server host is not a service, else it is not used; `discover-report.md` lists how each file was classified. Local `$ref` path items are followed; refs to other files are reported. An operation code does not have becomes an endpoint node with `source: "openapi-only"` (handler `(OpenAPI only)`, plus `operationId`/`summary`); both directions are listed in `discover-report.md`. External nodes an external API's file matches get `operation` (its path template, literal segments preferred), `operationId`, `summary` and `openapi`, and calls it lacks are reported.
- `flows` (graph.json): per endpoint with edges, a `seq` tree of `call` (each method entered), `if`/`else` (if statements and ternaries; `if` is labelled with the condition, `else` with `!(<condition>)`, so an `else` whose `if` side has no edge still says what it is the else of), `switch`/`case`, `loop` (for, for-each, while, do, `forEach` and `map`/`flatMap` on a Java stream), `try`/`catch` (a `finally` block is a `try` labelled `finally`), reactive operators (`flatMap`/`concatMap`/`flatMapSequential`/`map` over a Flux = `loop` labelled with its chain, arguments elided (`skus.flatMap(..).concatMap`); `Mono.zip`/`Flux.zip`/`Mono.when` = `parallel` around their arguments, also when an argument is a local variable holding the publisher; `switchIfEmpty`/`defaultIfEmpty`/`onErrorResume`/`onErrorReturn` = `fallback` labelled with the operator, around its argument when that is lazy (a lambda, a method reference, or any `switchIfEmpty`/`onErrorResume` publisher; a value passed to `defaultIfEmpty`/`onErrorReturn` is computed eagerly, so a call there stays in the plain sequence); `filter` = `if` labelled `filter(<predicate>)` around the operators after it; `retry`/`retryWhen` = `retry` labelled `retry(<args>)` around everything upstream of it), and leaves: `edge` / `unresolved` (`"edge": <index into edges>`) and `cycle` (`↻ back to <endpoint>`, after an edge whose target leads back to this endpoint). A call through an interface or abstract method that can run several implementations is a `oneOf` node (labelled with the interface) with one `call` per implementation; an interface with one implementation is followed directly. Implementations are narrowed as Spring injects them: classes whose `@ConditionalOnProperty` fails for the service's config are dropped, then for a receiver injected as a field or constructor parameter its `@Qualifier`, else `@Primary`, else the bean named like the field/parameter (bean names: stereotype value or class name, class `@Qualifier`, and `@Bean` methods that create the class). `list.forEach(x -> x.m())` over a `List<Iface>` reaches every implementation. Only paths ending in an edge are kept, so calls into code that reaches no edge are left out. An edge reached along several paths is a leaf under each of them (never twice under the same parent); every edge is a leaf at least once. At most 64 paths per call per method are kept (edges are never dropped); where more were cut, a `truncated` leaf (`"edge": <index>`, "more paths to this edge not shown") marks the spot and `map`/`discover` print a warning (also in `discover-report.md`). Children are in source order.
- `map.html` has an L1 (service) and L2 (endpoint) view. Click an endpoint or service to see what it calls downstream; shift-click adds more. Click an edge for its client, `via` chain and call site.
- `discover --root <dir> --profile <p>` exists but is not implemented yet.

## Known limits

- A controller in a library repo is attributed to the library, not to the services that include it.
- `@ConditionalOnProperty` is checked on implementing classes only (not on `@Bean` methods; no `@Profile`), and annotation values must be string literals. Resilience4j/`@Retryable` `fallback`/`retry` flow nodes come later in M4. A Flux is recognised by the declared type of a parameter, local, field or workspace method (Feign/`@HttpExchange` included, by name and arity), a `Flux.x(..)` source, or a known library source (`bodyToFlux`, `findAll*`, `fetch().all()`, reactive Redis `members`/`range`/`scan`/`entries`/...); other library calls are not seen as a Flux. Circuit-breaker fallback methods are not traced yet.
- Feign, WebClient, RestClient, RestTemplate and `@HttpExchange` clients are detected (`client`: `feign`, `webclient`, `restclient`, `resttemplate`, `httpexchange`). A RestTemplate's root URI is read from `RestTemplateBuilder.rootUri(..)` / `DefaultUriBuilderFactory(..)`; an `@HttpExchange` proxy's base URL from the client wrapped by the `HttpServiceProxyFactory` + `WebClientAdapter`/`RestClientAdapter`/`RestTemplateAdapter` that creates it. `exchange(RequestEntity, ..)` is not read yet.

## Layout

- `cli/`: the tool, a Maven project with packages `com.endpointmap.{workspace,config,endpoints,trace,outbound,url,graph,viewer}`. `trace.FlowTracer` walks method bodies and asks each `trace.OutboundDetector` (`outbound.FeignDetector`, `WebClientDetector`, `RestTemplateDetector`, `HttpExchangeDetector`, the last three on top of `url.UrlEvaluator`) to classify every call; `url.TargetMatcher` turns a classified call into target nodes.
- `cli/src/main/resources/viewer/`: the `map.html` template and the vendored JS that is inlined into every generated map: Cytoscape.js 3.30.4, dagre 0.8.5, cytoscape-dagre 2.5.0 (from the npm registry).
- `test-environment/`: fake services that are scanned but never run. Its README maps every detection case to a fixture file.
