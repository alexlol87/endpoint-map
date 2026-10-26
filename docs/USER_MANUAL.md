# endpoint-map user manual

endpoint-map reads the source code of many Spring Boot / Spring WebFlux repos and draws who calls whom: every HTTP endpoint, the outbound calls each handler makes (Feign, WebClient, RestClient, RestTemplate, `@HttpExchange`), plus database tables and Redis keys it touches. It only reads files: no network, no tracing, no running services. The output is `graph.json` and a self-contained `map.html` you open in a browser.

## Requirements

Java 21 or newer (tested on 21 and 25). Nothing else.

## Install

Download `endpoint-map.jar` from the latest [GitHub Release](https://github.com/alexlol87/endpoint-map/releases/latest). To build it yourself instead: `cd cli && mvn -B verify` gives `cli/target/endpoint-map.jar`.

## Commands

```
java -jar endpoint-map.jar discover --root <dir> --profile <p> [--resolutions <file>] [--openapi-dir <dir>]
java -jar endpoint-map.jar map      --root <dir> --profile <p> [--resolutions <file>] [--openapi-dir <dir>] --out <dir>
```

| Flag | Meaning |
|---|---|
| `--root <dir>` | Required. A folder whose sub-folders are the repos. The folder name is the service name. A repo with `@SpringBootApplication` is a service, other Java repos are libraries, folders without Java are skipped. |
| `--profile <p>` | Spring profile used to read `application-<p>.yml/.properties` (context paths, base URLs, ports, `${...}` placeholders). |
| `--resolutions <file>` | Where the answers for targets the code does not reveal live. Default: `./resolutions.yml` in the current directory. |
| `--openapi-dir <dir>` | Extra folder of OpenAPI/Swagger files (for example vendor APIs). OpenAPI files inside the repos are always found. |
| `--out <dir>` | `map` only. Where `graph.json` and `map.html` are written. |

`discover` writes `resolutions.yml` (a question per unknown target, existing answers kept) and `discover-report.md` next to it. `map` applies the resolutions and writes the graph. Exit code 0 is success, 2 is bad arguments, 1 is a failure. Files that fail to parse are listed and skipped.

## Typical workflow

```bash
java -jar endpoint-map.jar discover --root test-environment --profile prod
# edit resolutions.yml, then:
java -jar endpoint-map.jar map --root test-environment --profile prod --out out
# open out/map.html in a browser
```

1. **discover** lists every call whose target could not be read from code: a property or environment variable nothing defines (`properties:`), a URL built at runtime (`callSites:`), or a host that looks public (`host:`). Each entry shows where it is used and a hint.
2. **Fill `resolutions.yml`**: `service:<repo-name>` for an internal call (matched to that service's endpoints by verb and path), `external:<https://host>` for a call leaving the system, or leave `""` (stays a red unresolved node). Example:
   ```yaml
   notification-service:
     properties:
       "${REWARDS_HOST}": "service:loyalty-service"
   ```
   Re-running `discover` keeps your values and comments and marks entries no longer found as `# stale`.
3. **map** builds the graph. Read `discover-report.md` for what was not understood (unparsed files, routes built in loops, OpenAPI files and how they were classified, OpenAPI-only operations).

## graph.json

`nodes` (endpoints `service METHOD path`, `external`, `db`, `redis`, `unresolved`), `edges` (`from`, `to`, `kind`, `client`, `via` = the method chain from handler to call, `file`/`line` of the call site, optional `confidence: "low"`), and `flows`: per endpoint, a tree of the code paths to each edge (`call`, `if`/`else`, `switch`, `loop`, `try`/`catch`, `parallel`, `fallback`, `retry`, `oneOf` for several implementations).

## map.html

- **L1 services**: one box per service, external host, database or Redis.
- **L2 endpoints**: endpoints inside their service, coloured by verb (`ANY` is grey).
- **L3 code flow**: the L2 graph plus a *Code flow* panel with the `flows` tree of each selected endpoint; click a leaf to highlight its edge.
- **Select**: click an endpoint/service/table, shift-click to add more, or search and tick start points.
- **Downstream / Upstream**: what the selection calls, or who reaches it.
- **Depth**: hops shown from the selection (1 to all).
- Click an edge for its client, `via` chain and call site.

## Low-confidence and unresolved

- A **dashed edge** (`confidence: "low"`) means a method was matched by name and argument count because its types could not be resolved; check the call site in the edge details.
- A **red ✕ node** (`unresolved:...`) is a call whose target is unknown: the id says what is missing (`unresolved:<service>:<key> VERB path` = a property/call site to answer in `resolutions.yml`; `unresolved:<service> VERB path` = the service is known but has no matching endpoint). `map` prints each one with its reason.

## Known limits

- Controllers in a library repo belong to the library, not to the services that use it.
- `@ConditionalOnProperty` is checked on classes only (no `@Bean` methods, no `@Profile`), string literals only.
- Router functions built in loops or `if`s, and routers chosen by `@Qualifier`, are not followed (listed in the report).
- Some URL data flow is shallow: only the first constructor assignment, appends after a constructor, assignments after use in loops, and clients built per branch are not followed; such calls become `callSites` questions.
- `RestTemplate.exchange(RequestEntity, ..)`, named/stored-procedure SQL, `MERGE ... USING` and fluent `.from("table")` are not read.
- `@HttpExchange` implementations only through a direct `implements`.
- Unknown entity names fall back to a snake_case table (low confidence); only `public`/`dbo` count as the default schema.
- Redis caches: the cache manager is assumed to be Redis.
- Flow trees keep at most 64 paths per call per method (edges are never dropped; a "more paths" marker shows where).
- The viewer switches to a faster, rougher layout above about 400 edges, and the search list shows 50 matches.
