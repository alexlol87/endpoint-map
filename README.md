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
- A Feign client without `url` (`feign:<name>`) or with a placeholder nothing defines (`${SHIPPING_SERVICE_URL}`) points to an `unresolved:<service>:<key> VERB path` node, and a known service with no matching endpoint to `unresolved:<service> VERB path`. `map` prints them all. Calls the symbol solver could not resolve were matched by name and arity and carry `confidence: "low"`.
- `map.html` has an L1 (service) and L2 (endpoint) view. Click an endpoint or service to see what it calls downstream; shift-click adds more. Click an edge for its client, `via` chain and call site.
- `discover --root <dir> --profile <p>` exists but is not implemented yet.

## Known limits

- A controller in a library repo is attributed to the library, not to the services that include it.
- Every implementation of an interface is followed (`@Qualifier`/`@Primary` are not used yet), and `flows` (the L3 tree) stays empty: both come with M4. Circuit-breaker fallback methods are not traced yet.
- Only Feign is detected; WebClient, RestClient, RestTemplate and `@HttpExchange` clients come with M2.

## Layout

- `cli/`: the tool, a Maven project with packages `com.endpointmap.{workspace,config,endpoints,trace,outbound,url,graph,viewer}`. `trace.FlowTracer` walks method bodies and asks each `trace.OutboundDetector` (today `outbound.FeignDetector`) to classify every call; `url.TargetMatcher` turns a classified call into target nodes.
- `cli/src/main/resources/viewer/`: the `map.html` template and the vendored JS that is inlined into every generated map: Cytoscape.js 3.30.4, dagre 0.8.5, cytoscape-dagre 2.5.0 (from the npm registry).
- `test-environment/`: fake services that are scanned but never run. Its README maps every detection case to a fixture file.
