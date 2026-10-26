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
  const flows = {};
  edges.forEach((e, i) => ((flows[e.from] = flows[e.from] || { type: 'seq', children: [] }).children.push({ type: 'edge', edge: i })));
  return { services, nodes, edges, flows };
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
  const heads = await page.$$eval('#flow > details > summary', s => s.map(x => x.textContent.trim()).sort());
  assert(JSON.stringify(heads) === JSON.stringify([a, b].sort()), 'L3 one tree per pick: ' + heads.join(' | '));
  // every leaf of both trees: selects exactly the cy edge carrying that graph.json edge, details show its via and file:line
  const leafErr = await page.evaluate(() => {
    const bad = [];
    document.querySelectorAll('#flow button').forEach(btn => {
      btn.click();
      const sel = cy.edges(':selected'), txt = document.getElementById('details').textContent;
      const e = GRAPH.edges.find(x => sel.length === 1 && sel[0].data('edges').includes(x) && txt.includes(`via: ${x.via.join(' -> ')}\nat: ${x.file}:${x.line}`));
      if (!e || !btn.textContent.endsWith(e.to)) bad.push(btn.textContent);
    });
    return [document.querySelectorAll('#flow button').length, bad];
  });
  assert(leafErr[0] > 0 && !leafErr[1].length, `${leafErr[0]} leaf clicks select their own edge with via + file:line ${leafErr[1].join('; ')}`);
  await page.locator('#flow > details > summary').first().click();
  assert(!(await page.locator('#flow > details').first().evaluate(d => d.open)), 'tree collapses');
  await page.screenshot({ path: path.join(dir, 'viewer-l3.png') });
  // keywords read from the rendered panel with every flow picked
  await page.evaluate(() => { roots = new Set(Object.keys(GRAPH.flows)); render(); });
  const kws = [...new Set(await page.$$eval('#flow .k', k => k.map(x => x.textContent)))];
  assert(['if', 'else', 'loop', 'oneOf', 'parallel', 'try', 'catch', 'fallback', 'retry', 'switch', 'case'].every(k => kws.includes(k)), 'L3 panel keywords rendered');
  // upstream at L3: callers' flows; a leaf outside the view says so instead of silently doing nothing
  const callee = await page.evaluate(() => endpoints.find(n => (inc[n.id] || []).length).id);
  await page.click('#clear'); await pick(callee); await page.click('#up'); await setDepth(1);
  const upHeads = await page.$$eval('#flow > details > summary', s => s.map(x => x.textContent.trim()));
  const callers = await page.evaluate(() => cy.nodes('.endpoint').map(n => n.id()).filter(id => !roots.has(id) && GRAPH.flows[id]));
  assert(JSON.stringify(upHeads.sort()) === JSON.stringify(callers.sort()), `upstream L3 shows the ${callers.length} caller flow(s)`);
  const upOk = await page.evaluate(() => [...document.querySelectorAll('#flow button')].every(btn => {
    btn.click();
    return cy.edges(':selected').length === 1 || document.getElementById('details').textContent.startsWith('Not in the current view');
  }));
  assert(upOk, 'upstream leaf clicks highlight or say not in view');
  await setDepth(11); await page.click('#down'); await page.click('#clear');
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
  t = Date.now(); await page.click('#l3'); ms.l3 = Date.now() - t;
  assert(await page.locator('#flow > details').count() === 1, 'big map: L3 tree for the pick');
  await page.click('#l1');
  t = Date.now(); await page.click('#up'); ms.upstream = Date.now() - t;
  await page.fill('#depth', '3'); await page.dispatchEvent('#depth', 'input');
  assert(await page.textContent('#depthv') === '3', 'depth label follows the drag');
  t = Date.now(); await setDepth(2); ms.depth = Date.now() - t;
  // fast layout on a big selection starts from the picks
  await page.click('#down'); await setDepth(11);
  const lay = await page.evaluate(() => {
    const orig = cy.layout.bind(cy); let o; cy.layout = opts => { o = opts; return orig(opts); };
    level = 'L2'; roots = new Set(endpoints.filter(n => n.service <= 'svc-09').map(n => n.id)); render(); cy.layout = orig; return { name: o.name, roots: o.roots && o.roots.map(n => n.id()), want: [...roots] };
  });
  assert(lay.name === 'breadthfirst' && JSON.stringify((lay.roots || []).sort()) === JSON.stringify(lay.want.sort()), 'big selection: breadthfirst rooted at the picks');
  let alerts = 0; page.on('dialog', () => alerts++);
  await page.click('#l2'); await page.click('#clear');
  assert(alerts === 0 && await page.evaluate(() => level) === 'L1', 'big map: clear at L2 falls back to L1 without an alert');
  t = Date.now(); await page.click('#l2'); ms.l2refused = Date.now() - t;
  assert(alerts === 1, 'big map: clicking L2 alerts once');
  await page.click('#l3');
  assert(alerts === 2 && await page.evaluate(() => level) === 'L1', 'big map: whole-map L3 refused with one alert');
  console.log('     big map ms', JSON.stringify(ms));
  assert(await page.evaluate(() => level) === 'L1', 'big map: whole-map L2 is refused');
  assert(Object.values(ms).every(v => v < 5000), 'big map: every step under 5 s');
  assert(!errors.length, 'no page errors on synthetic maps ' + errors.join('; '));
  await browser.close();
})().catch(e => { console.error(e.message); process.exit(1); });
