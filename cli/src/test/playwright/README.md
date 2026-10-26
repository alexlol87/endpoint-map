Browser smoke test for the viewer (`map.html`). Not run by Maven, to keep the build offline and Node-free.

    java -jar cli/target/endpoint-map.jar map --root test-environment --profile prod --out /tmp/em-out
    NODE_PATH=$(npm root -g) node cli/src/test/playwright/viewer-smoke.js /tmp/em-out

Needs Node, the `playwright` package and Chromium. Writes screenshots and a synthetic 40-service / 1200-endpoint `map-big.html` into the out dir and prints render timings.
