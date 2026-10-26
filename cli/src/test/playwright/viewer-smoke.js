// Browser smoke test for map.html (not part of mvn verify: needs Chromium + the playwright npm package).
// Usage: node viewer-smoke.js <out-dir-with-map.html>
// Checks the test-environment map, then a synthetic 40-service / 1200-endpoint map for responsiveness.
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const assert = (c, m) => { if (!c) throw new Error('FAIL: ' + m); console.log('ok   ' + m); };

function bigGraph() {
  const services = [], nodes = [], edges = [];
  for (let s = 0; s < 40; s++) {
    const name = 'svc-' + String(s).padStart(2, '0');
    services.push({ name, kind: 'service', colorIndex: s });
    for (let e = 0; e < 30; e++) nodes.push({ id: `${name} GET /r${e}`, kind: 'endpoint', service: name, method: 'GET', path: '/r' + e, handler: 'H.h' });
    nodes.push({ id: `db:${name}:shared`, kind: 'db', tech: 'jpa', table: 'shared' });
  }
  nodes.forEach((n, i) => {
    if (n.kind !== 'endpoint') return;
    const t = nodes[(i * 7 + 31) % nodes.length];
    edges.push({ from: n.id, to: t.id, kind: 'http', client: 'feign', via: [], file: 'x', line: 1 });
    if (i % 10 === 0) edges.push({ from: n.id, to: `db:${n.service}:shared`, kind: 'db', client: 'jpa', via: [], file: 'x', line: 1 });
  });
  return { services, nodes, edges, flows: [] };
}

(async () => {
  const dir = process.argv[2];
  const html = fs.readFileSync(path.join(dir, 'map.html'), 'utf8');
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto('file://' + path.resolve(dir, 'map.html'));
  const n = sel => page.evaluate(s => cy.nodes(s).length, sel);
  const total = await n('*');

  await page.fill('#search', 'orders');
  assert(await page.locator('#picks input').count() > 1, 'search lists start points');
  await page.locator('#picks input').nth(0).check();
  await page.locator('#picks input').nth(1).check();
  assert(await n('.root') === 2, 'two endpoints merged into one map');
  const two = await n('*');
  await page.fill('#depth', '1'); await page.dispatchEvent('#depth', 'input');
  assert(await n('*') <= two, 'depth slider trims');
  await page.screenshot({ path: path.join(dir, 'viewer-downstream.png') });

  await page.click('#clear');
  await page.fill('#depth', '10'); await page.dispatchEvent('#depth', 'input');
  await page.click('#up');
  await page.fill('#search', 'table ');
  await page.locator('#picks input').first().check();
  const up = await page.evaluate(() => cy.nodes('.endpoint').length);
  assert(up > 0 && up < total, `upstream from a table shows ${up} endpoint(s)`);
  await page.fill('#search', 'redis ');
  await page.locator('#picks label', { hasText: /^redis / }).first().locator('input').check();
  assert(await page.evaluate(() => cy.nodes('.redis.root').length) === 1, 'upstream from a Redis key');
  await page.screenshot({ path: path.join(dir, 'viewer-upstream.png') });
  assert(!errors.length, 'no page errors ' + errors.join('; '));

  const big = path.join(dir, 'map-big.html');
  fs.writeFileSync(big, html.replace(/const GRAPH = [\s\S]*?;\n\/\/ ANY/, () => 'const GRAPH = ' + JSON.stringify(bigGraph()) + ';\n// ANY'));
  let t = Date.now();
  await page.goto('file://' + big);
  await page.waitForFunction(() => window.cy && cy.nodes().length > 0);
  console.log('     big map initial render ' + (Date.now() - t) + ' ms');
  t = Date.now();
  await page.fill('#search', 'svc-07 GET /r1');
  await page.locator('#picks input').first().check();
  console.log('     big map select ' + (Date.now() - t) + ' ms, ' + await n('*') + ' nodes');
  t = Date.now();
  await page.click('#up');
  console.log('     big map upstream ' + (Date.now() - t) + ' ms');
  assert(!errors.length, 'no page errors on big map ' + errors.join('; '));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
