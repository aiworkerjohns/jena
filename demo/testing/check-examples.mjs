/* Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0 */

/*
 * Replay every example in test/tests.json through the app's own filter pipeline and run
 * the result against a live Fuseki.
 *
 *   task check-examples            # against http://localhost:3030
 *   FUSEKI_PORT=3031 task check-examples
 *
 * Three demo bugs in a row shared one symptom: an example that looked fine and quietly
 * returned the whole dataset.
 *
 *   - Unescaped '#' in a field IRI truncated the filter at the URL fragment.
 *   - parseCqlFilter understood only s_intersects, so other operators produced no state.
 *   - A polygon was reduced to its outer ring, so holes were dropped.
 *
 * None of those raise. Checking only that a query succeeds, or that it clears
 * minResults, misses all three — an unfiltered query passes both. So the check that
 * matters here is the last one: a filter must actually change the result set.
 *
 * This uses Node's built-in test runner and fetch. No dependencies.
 */

import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';

const HERE = dirname(fileURLToPath(import.meta.url));
const DEMO = join(HERE, '..');
const PORT = process.env.FUSEKI_PORT || '3030';
const DATASET = process.env.FUSEKI_DATASET || 'mining';
const ENDPOINT = `http://localhost:${PORT}/${DATASET}/query`;
const LIMIT = 500;

/** Load app.js in a browser-shaped sandbox so the real functions are under test. */
function loadApp() {
    const ctx = {
        console, URLSearchParams, JSON, Math, Date, Object, Array, String, Number,
        Boolean, isNaN, parseInt, parseFloat, setTimeout,
        window: { location: { search: '', pathname: '/' }, history: { pushState() {} }, addEventListener() {} },
        document: { addEventListener() {}, querySelector: () => null },
        L: {}, fetch: () => Promise.reject(new Error('no network in sandbox')),
    };
    ctx.globalThis = ctx;
    ctx.WKT = createRequire(import.meta.url)(join(DEMO, 'app-static/wkt.js'));
    vm.createContext(ctx);
    vm.runInContext(readFileSync(join(DEMO, 'app-static/app.js'), 'utf8'), ctx);
    return ctx;
}

/** Field name to IRI, as the app derives it from the running configuration. */
function fieldIRIsFromConfig() {
    const cfg = readFileSync(join(DEMO, 'test/config.ttl'), 'utf8');
    const map = {};
    for (const m of cfg.matchAll(/idx:fieldName\s+"([^"]+)"/g)) {
        map[m[1]] = 'urn:jena:lucene:field#' + m[1];
    }
    return map;
}

const sparqlQuote = (s) => "'" + String(s).replace(/\\/g, '\\\\').replace(/'/g, "\\'") + "'";

async function rowCount(term, cqlFilter) {
    const q = `PREFIX luc: <urn:jena:lucene:index#>
SELECT ?entity WHERE {
  (?hit ?entity ?score ?totalHits ?rank) luc:query ('default' 'default' ${sparqlQuote(term)} ${cqlFilter ? sparqlQuote(cqlFilter) : "''"} '' ${LIMIT} 0)
}`;
    const resp = await fetch(ENDPOINT, {
        method: 'POST',
        headers: { 'Content-Type': 'application/sparql-query', Accept: 'text/tab-separated-values' },
        body: q,
    });
    const text = await resp.text();
    if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${text.split('\n')[0].slice(0, 160)}`);
    return text.trim() ? text.trim().split('\n').length - 1 : 0;
}

const ctx = loadApp();
const fieldIRIs = fieldIRIsFromConfig();
const tests = JSON.parse(readFileSync(join(DEMO, 'test/tests.json'), 'utf8'));

let baseline = null;
before(async () => {
    try {
        baseline = await rowCount('*', null);
    } catch (e) {
        throw new Error(`No Fuseki at ${ENDPOINT} — start the demo first (${e.message})`);
    }
});

let group = null;
for (const t of tests) {
    if (t.group) { group = t.group; continue; }
    const label = `${group} / ${t.label}`;
    const raw = (t.params || '').replace(/^\?/, '');

    test(label, async () => {
        // Encode then read back, exactly as applyExample and loadFromUrl do.
        const qs = raw ? new URLSearchParams(raw).toString() : '';
        const params = new URLSearchParams(new URL('http://x/?' + qs).search);

        if (params.get('filter')) {
            assert.doesNotThrow(() => JSON.parse(params.get('filter')),
                'filter did not survive the URL round trip');
        }

        const term = (params.get('q') || '*').trim() || '*';
        const { selected, bbox, polygon, spatialRaw } =
            ctx.parseCqlFilter(params.get('filter'), fieldIRIs);
        const cql = ctx.buildCqlFilter(
            selected, bbox, polygon, fieldIRIs, spatialRaw ? [spatialRaw] : []);

        // An example that declares a filter must end up with one. Losing it here is how
        // all three of the bugs above presented.
        if (params.get('filter')) {
            assert.ok(cql, 'the declared filter was dropped rebuilding the query');
        }

        const rows = await rowCount(term, cql);
        assert.ok(rows >= (t.minResults ?? 0),
            `got ${rows} rows, minResults is ${t.minResults}`);

        // The check that catches a silently inert filter.
        if (cql && baseline !== null && rows === baseline) {
            assert.fail(`filter has no effect: still the unfiltered ${rows} rows`);
        }
    });
}
