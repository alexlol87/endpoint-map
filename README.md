# endpoint-map

Static analyzer that maps Spring Boot / Spring WebFlux endpoints across many repos. It reads source code only: no network, no tracing, no LLM. Output is `graph.json` plus a self-contained `map.html` you open in a browser.

Status: walking skeleton. `map` detects annotated controller endpoints. `discover` is a stub. A fuller manual comes later.

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
- Files that fail to parse are listed on stdout, and the scan continues without them.
- `discover --root <dir> --profile <p>` exists but is not implemented yet.

## Layout

- `cli/`: the tool, a Maven project with packages `com.endpointmap.{workspace,config,endpoints,graph,viewer}`.
- `cli/src/main/resources/viewer/`: the `map.html` template and the vendored JS that is inlined into every generated map: Cytoscape.js 3.30.4, dagre 0.8.5, cytoscape-dagre 2.5.0 (from the npm registry).
- `test-environment/`: fake services that are scanned but never run. Its README maps every detection case to a fixture file.
