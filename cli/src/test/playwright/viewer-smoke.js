// Browser smoke test for map.html (not part of mvn verify: needs Chromium + the playwright npm package).
// Usage: node viewer-smoke.js <out-dir-with-map.html>   (exit code 1 on any failed check)
// Checks the test-environment map, then synthetic maps for hostile names and for responsiveness at 40 services / 1200 endpoints.
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path'), url = require('url');
const assert = (c, m) => { if (!c) throw new Error('FAIL: ' + m); console.log('ok   ' + m); };

const dir = path.resolve(process.argv[2] || '.');
const html = fs.readFileSync(path.join(dir, 'map.html'), 'utf8');
// same escaping as MapHtmlWriter, so injected graphs embed exactly like real ones
const embed = g => JSON.stringify(g).replace(/</g, '\\u003c').replace(/>/g, '\\u003e').replace(/&/g, '\\u0026');
function writeWith(name, g) {
  const file = path.join(dir, name);
  fs.writeFileSync(file, html.replace(/const GRAPH = [\s\S]*?;\n\/\/ ANY/, () => 'const GRAPH = ' + embed(g) + ';\n// ANY'));
  return url.pathToFileURL(file).href;
}
const ep = (svc, p) => ({ id: `${svc} GET ${p}`, kind: 'endpoint', service: svc, method: 'GET', path: p, handler: 'H.h' });
const edge = (from, to, extra) => ({ from, to, kind: 'http', client: 'feign', via: [], file: 'x', line: 1, ...extra });

function bigGraph() {
  const services = [], nodes = [], edges = [];
  for (let s = 0; s < 40; s++) {
    const name = 'svc-' + String(s).padStart(2, '0');
    services.push({ name, kind: 'service', colorIndex: s });
    for (let e = 0; e < 30; e++) nodes.push(ep(name, '/r' + e));
    nodes.push({ id: `db:${name}:shared`, kind: 'db', tech: 'jpa', table: 'shared' });
  }
  nodes.forEach((n, i) => {
    if (n.kind !== 'endpoint') return;
    edges.push(edge(n.id, nodes[(i * 7 + 31) % nodes.length].id));
    if (i % 10 === 0) edges.push(edge(n.id, `db:${n.service}:shared`, { kind: 'db', client: 'jpa' }));
  });
  return { services, nodes, edges, flows: [] };
}

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
  const errors = [], requests = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', d => d.dismiss());
  page.on('request', r => { if (!r.url().startsWith('file:') && !r.url().startsWith('data:')) requests.push(r.url()); });
  const ids = sel => page.evaluate(s => cy.nodes(s).map(n => n.id()).sort(), sel);
  const pick = async (key, on = true) => {
    await page.fill('#search', key);
    await page.locator('#picks label').filter({ hasText: new RegExp('^' + key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$') })
      .locator('input').setChecked(on);
  };
  const setDepth = async v => { await page.fill('#depth', String(v)); await page.dispatchEvent('#depth', 'change'); };

  await page.goto(url.pathToFileURL(path.join(dir, 'map.html')).href);
  const all = await ids('*');
  // downstream: two picks merge into the union of their single maps
  // a: a root whose map is deeper than one hop, b: any other caller
  const [a, b] = await page.evaluate(() => {
    const size = (id, d) => { roots = new Set([id]); depth = d; const n = reach().nodes.size; roots = new Set(); depth = 11; return n; };
    const deep = endpoints.find(n => size(n.id, 11) > size(n.id, 1));
    return [deep.id, endpoints.find(n => n !== deep && (out[n.id] || []).length).id];
  });
  await pick(a); const mapA = await ids('*');
  await pick(a, false); await pick(b); const mapB = await ids('*');
  await pick(a);
  const both = await ids('*');
  assert(JSON.stringify(both) === JSON.stringify([...new Set([...mapA, ...mapB])].sort()), `two picks = union (${both.length} nodes)`);
  assert((await ids('.root')).length === 2, 'both picks highlighted');
  assert(await page.evaluate(() => cy.nodes('.dim').length) > 0, 'search highlight survives a pick');
  await setDepth(1);
  const d1 = await ids('*');
  assert(d1.length < both.length, `depth 1 trims ${both.length} -> ${d1.length}`);
  assert(await page.evaluate(() => cy.nodes().every(n => n.isParent() || n.hasClass('root') || n.incomers('node').some(m => m.hasClass('root')))),
    'depth 1 keeps only roots and their direct callees');
  await setDepth(11);
  assert((await ids('*')).length === both.length, 'depth "all" restores the map');
  await page.screenshot({ path: path.join(dir, 'viewer-downstream.png') });

  // L3: flow tree per pick, a leaf click selects its edge, details show via chain
  await page.click('#l3');
  assert((await ids('*')).length === both.length, 'L3 graph = L2 map of the picks');
  assert(await page.locator('#flow > details').count() === 2, 'L3 tree per pick');
  const kws = await page.evaluate(() => [...new Set(Object.values(GRAPH.flows).flatMap(f =>
    [...tree({ type: 'call', label: '', children: f.children }).querySelectorAll('.k')].map(k => k.textContent)))].sort());
  assert(['if', 'loop', 'oneOf', 'parallel', 'try', 'fallback'].every(k => kws.includes(k)), 'L3 tree keywords: ' + kws.join(' '));
  const leaf = page.locator('#flow button').first();
  await leaf.click();
  assert(await page.evaluate(() => cy.edges(':selected').length === 1), 'leaf click highlights its edge');
  assert(/via: .*\nat: /.test(await page.textContent('#details')), 'leaf click shows via chain and file:line');
  await page.locator('#flow > details > summary').first().click();
  assert(!(await page.locator('#flow > details').first().evaluate(d => d.open)), 'tree collapses');
  await page.screenshot({ path: path.join(dir, 'viewer-l3.png') });
  await page.click('#l2');

  // upstream from a table and a Redis key
  await page.click('#clear');
  await page.click('#up');
  await pick('table audit_event');
  const up = await page.evaluate(() => cy.nodes('.endpoint').length);
  assert(up > 0 && up < all.length, `upstream from table audit_event: ${up} endpoint(s)`);
  await page.fill('#search', 'redis ');
  const redisKey = (await page.locator('#picks label').filter({ hasText: /^redis / }).first().textContent());
  await pick(redisKey);
  assert((await ids('.redis.root')).length === 1, 'upstream from ' + redisKey);
  await page.screenshot({ path: path.join(dir, 'viewer-upstream.png') });

  // L1: table/Redis nodes are clickable start points; services too
  await page.click('#clear');
  await page.click('#l1');
  const tapped = await page.evaluate(() => { const n = cy.nodes('.db').first(); n.emit('tap'); return [n.id(), [...roots]]; });
  assert(tapped[1].length === 1 && tapped[1][0] === tapped[0], 'L1 tap on a table selects it: ' + tapped[0]);
  await page.evaluate(() => cy.nodes('.service1').first().emit('tap'));
  assert(await page.evaluate(() => roots.size > 0), 'L1 tap on a service selects its endpoints');
  await page.click('#clear');
  assert((await ids('.unresolved')).length > 0, 'unresolved nodes shown at L1');
  await page.click('#l2');
  assert((await ids('.unresolved')).length > 0, 'unresolved nodes shown at L2');
  assert(!errors.length, 'no page errors ' + errors.join('; '));
  assert(!requests.length, 'offline: no network requests ' + requests.join(' '));

  // low confidence + hostile names (injected graph)
  const evil = 'svc"><img src=x onerror=window.PWN=1>';
  await page.goto(writeWith('map-evil.html', {
    services: [{ name: evil, kind: 'service', colorIndex: 0 }, { name: 'b', kind: 'service', colorIndex: 1 }],
    nodes: [ep(evil, '/x</script><!--<script>'), ep('b', '/y\u2028')],
    edges: [edge(`${evil} GET /x</script><!--<script>`, 'b GET /y\u2028', { confidence: 'low' })], flows: []
  }));
  assert(await page.evaluate(() => typeof cy === 'object' && cy.nodes().length === 4), 'hostile names: viewer still runs');
  assert(await page.evaluate(() => window.PWN === undefined && !document.querySelector('img')), 'hostile names: no injected markup');
  assert(await page.evaluate(() => [...document.querySelectorAll('#services .row')].some(r => r.textContent.includes('onerror'))), 'hostile name shown as text');
  assert(await page.evaluate(() => cy.edges('.low').length === 1 && cy.edges('.low').style('line-style') === 'dashed'), 'low-confidence edge dashed');

  // big map: must stay responsive
  let t = Date.now();
  await page.goto(writeWith('map-big.html', bigGraph()));
  const ms = { load: Date.now() - t };
  t = Date.now(); await pick('svc-07 GET /r1'); ms.pick = Date.now() - t;
  t = Date.now(); await page.click('#up'); ms.upstream = Date.now() - t;
  await page.fill('#depth', '3'); await page.dispatchEvent('#depth', 'input');
  assert(await page.textContent('#depthv') === '3', 'depth label follows the drag');
  t = Date.now(); await setDepth(2); ms.depth = Date.now() - t;
  t = Date.now(); await page.click('#clear'); await page.click('#l2'); ms.l2refused = Date.now() - t;
  let alerts = 0; page.on('dialog', () => alerts++);
  await page.click('#up'); await page.click('#clear');
  assert(alerts === 0, 'big map: routine actions raise no alert');
  await page.click('#l3');
  assert(alerts === 1 && await page.evaluate(() => level) === 'L1', 'big map: whole-map L3 refused with one alert');
  console.log('     big map ms', JSON.stringify(ms));
  assert(await page.evaluate(() => level) === 'L1', 'big map: whole-map L2 is refused');
  assert(Object.values(ms).every(v => v < 5000), 'big map: every step under 5 s');
  assert(!errors.length, 'no page errors on synthetic maps ' + errors.join('; '));
  await browser.close();
})().catch(e => { console.error(e.message); process.exit(1); });
