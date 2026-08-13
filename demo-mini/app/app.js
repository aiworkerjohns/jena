/* Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0 */

/*
 * demo-mini — a deliberately small faceted-search client.
 *
 * Two things are worth knowing before reading on:
 *
 * 1. The app discovers the index shape from GET /$/config/effective. It does NOT parse
 *    config.ttl. Field types, facetability, the hierarchy dimension name and the
 *    index-fingerprint status all come from the server, which is the only party that
 *    knows what Jena actually resolved.
 *
 * 2. Labels are never joined into the search query. Every IRI is resolved by its own
 *    cacheable GET through labels.js, so the browser's HTTP cache serves repeat views
 *    with no network at all. See labels.js for why that beats one batched VALUES query.
 */

const BASE = '/fuseki';
const ENDPOINT = `${BASE}/kitchen/query`;
const F = n => `urn:jena:lucene:field#${n}`;
const KT = 'http://example.org/kitchen/';
const PAGE_SIZE = 5;

const labels = new LabelResolver(ENDPOINT, '1');

/** Years covered by the data, discovered at startup — see loadDateAxis(). */
const dateAxis = { years: [] };

/**
 * Facet panels, in display order. `dimension` marks the hierarchical one.
 *
 * The two dimension names are filled in from /$/config/effective at startup — they are
 * derived by joining the levels' idx:fieldName values with "_", which is not something
 * this client should be asserting for itself.
 */
const FACETS = [
    { key: 'region_country', title: 'Region / country', dimension: true },
    { key: F('course'), title: 'Course' },
    { key: F('diet'), title: 'Diet' },
    { key: F('contributor'), title: 'People (author or tester)', note: 'one field, two SHACL paths' },
];

/**
 * Reviews come from the CSV, so they are child-scoped, and a child-scoped field has no
 * entity-level flat facet — luc:facet on field:reviewer alone returns nothing. Their
 * counts are only reachable through this nested hierarchy dimension.
 */
const REVIEW_DIM = { key: 'reviewer_stars' };

const PREP_RANGES = [null, 20, 60, 240, null];

const TESTS = [
    '01-search-bm25', '02-facets-flat', '03-facets-filtered', '04-hierarchy-top',
    '05-hierarchy-drill', '06-date-range', '07-range-facets', '08-typeahead-ngram',
    '09-fanin-contributor', '10-keyword-iri-filter', '11-match', '12-nested-match-reviews',
    '13-nested-correlation', '14-sort-and-page', '15-vector-search', '16-vector-filtered',
    '17-nested-hierarchy-correlated',
];

const state = {
    q: '', mode: 'bm25', sort: '', page: 0,
    sel: {},            // fieldIRI -> Set of values (multi-select)
    region: null,       // hierarchy drill-down: the selected parent level
    country: null,
    // null means "the whole span", which is not a filter at all. Otherwise [lo, hi] are
    // indices into dateAxis.years, so the slider and the filter cannot disagree.
    yearIdx: null,
    minStars: '', reviewer: null, starsExact: null,
    prepBucket: null,   // [low, high]
    // Which hierarchy nodes are OPEN, which is not the same as which are FILTERED.
    // luc:facet takes its own cqlFilter, so a node's children can be fetched with an "="
    // that never reaches the results query — browsing the tree without narrowing anything.
    open: { region: new Set(), reviewer: new Set() },
    config: null,
};

/* ── tiny helpers ─────────────────────────────────────────────────────── */

const el = id => document.getElementById(id);
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const isIri = v => typeof v === 'string' && /^https?:|^urn:/.test(v);
const shortIri = v => isIri(v) ? v.replace(KT, 'kt:').replace('urn:jena:lucene:field#', 'field:') : v;

const prop = f => ({ property: f });
const eq = (f, v) => ({ op: '=', args: [prop(f), v] });
const anyOf = (f, vals) => vals.length === 1 ? eq(f, vals[0]) : { op: 'or', args: vals.map(v => eq(f, v)) };

/** Same endpoint, but asking for Turtle so the raw payload can be shown verbatim. */
async function sparqlTurtle(query) {
    const resp = await fetch(ENDPOINT, {
        method: 'POST',
        headers: { 'Content-Type': 'application/sparql-query', Accept: 'text/turtle' },
        body: query,
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${(await resp.text()).slice(0, 400)}`);
    return resp.text();
}

async function sparql(query) {
    const resp = await fetch(ENDPOINT, {
        method: 'POST',
        headers: { 'Content-Type': 'application/sparql-query', Accept: 'application/sparql-results+json' },
        body: query,
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${(await resp.text()).slice(0, 400)}`);
    return resp.json();
}

/* ── the CQL2-JSON filter, built from the whole UI state ───────────────── */

/**
 * @param skip clause names to leave out — "region", "country", "reviewer", "stars".
 *
 * A facet must not be counted under its own filter, or drilling into a value hides every
 * sibling and the value itself. Asking for the region level with the region clause omitted
 * is what keeps all three regions on screen, counted correctly under the OTHER filters,
 * with the chosen one ticked. Standard faceted-search behaviour; Solr and Elasticsearch
 * spell it as excluding a tagged filter.
 */
function buildFilter(skip = {}, pin = {}) {
    const clauses = [];

    for (const [field, values] of Object.entries(state.sel)) {
        if (values.size) clauses.push(anyOf(field, [...values]));
    }

    // A "=" on a hierarchy level both narrows the results AND tells luc:facet to return
    // that level's children. One clause, both jobs — there is no drill-down argument.
    // `pin` overrides the selection: it is how "show me Asia's countries" is expressed
    // without Asia being a filter on the results.
    const region = pin.region ?? (skip.region ? null : state.region);
    const country = pin.country ?? (skip.country ? null : state.country);
    if (region) clauses.push(eq(F('region'), region));
    if (country) clauses.push(eq(F('country'), country));

    if (state.yearIdx && !skip.date) {
        const [lo, hi] = state.yearIdx;
        clauses.push({ op: 'between', args: [prop(F('publishedOn')),
            `${dateAxis.years[lo]}-01-01`, `${dateAxis.years[hi]}-12-31`] });
    }

    if (state.prepBucket) {
        const [lo, hi] = state.prepBucket;
        if (lo !== null) clauses.push({ op: '>=', args: [prop(F('prepMinutes')), lo] });
        if (hi !== null) clauses.push({ op: '<', args: [prop(F('prepMinutes')), hi] });
    }

    // These are the same-child pair. They sit as siblings in the top-level "and", so the
    // compiler folds them into one block join: ONE review must satisfy both. Selecting
    // "Priya" and "5" finds recipes Priya rated 5 — not recipes Priya reviewed that someone
    // else rated 5. Selecting a reviewer also drives the reviewer_stars drill-down.
    const reviewer = pin.reviewer ?? (skip.reviewer ? null : state.reviewer);
    if (reviewer) clauses.push(eq(F('reviewer'), reviewer));
    if (state.starsExact !== null && !skip.stars) {
        clauses.push(eq(F('stars'), state.starsExact));
    } else if (state.minStars && !skip.stars) {
        clauses.push({ op: '>=', args: [prop(F('stars')), Number(state.minStars)] });
    }

    // The typeahead field is analyzer-backed, so it needs text_query. "=" would compare a
    // raw term against edge n-grams and match nothing.
    if (state.mode === 'code' && state.q.trim()) {
        clauses.push({ op: 'text_query', args: [prop(F('codeText')), state.q.trim()] });
    }

    if (!clauses.length) return '';
    return JSON.stringify(clauses.length === 1 ? clauses[0] : { op: 'and', args: clauses });
}

function fieldSpec() {
    return state.mode === 'semantic' ? JSON.stringify([F('embedding')]) : 'default';
}

function queryString() {
    if (state.mode === 'semantic') return state.q.trim();
    if (state.mode === 'code') return '*';
    return state.q.trim() || '*';
}

function sortSpec() {
    if (!state.sort) return '';
    const [field, order] = state.sort.split(':');
    return JSON.stringify({ field: F(field), order });
}

const lit = s => `"${String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;

/** The 7 luc:query / luc:facet arguments shared by every request in this view. */
function argsFor(kind, skip = {}, facetFields = null, pin = {}) {
    const fs = fieldSpec();
    const spec = fs === 'default' ? '"default"' : `'${fs}'`;
    const q = lit(queryString());
    const filter = buildFilter(skip, pin);
    const f = filter ? `'${filter}'` : '""';
    if (kind === 'facet') {
        const fields = JSON.stringify(facetFields || [
            ...FACETS.filter(x => !x.dimension).map(x => x.key),
            { field: F('prepMinutes'), ranges: PREP_RANGES },
        ]);
        return `"default" ${spec} ${q} '${fields}' ${f} 50 0`;
    }
    const sort = sortSpec();
    const s = sort ? `'${sort}'` : '""';
    return `"default" ${spec} ${q} ${f} ${s} ${PAGE_SIZE} ${state.page * PAGE_SIZE}`;
}

/* ── queries ───────────────────────────────────────────────────────────── */

function searchQuery() {
    return `PREFIX luc:  <urn:jena:lucene:index#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX dct:  <http://purl.org/dc/terms/>
PREFIX kt:   <${KT}>

SELECT ?entity ?rank ?totalHits ?score ?label ?desc ?code ?course ?country ?region ?prep ?published
       (GROUP_CONCAT(DISTINCT STR(?diet);   SEPARATOR="|") AS ?diets)
       (GROUP_CONCAT(DISTINCT STR(?person); SEPARATOR="|") AS ?people)
WHERE {
    (?hit ?entity ?score ?totalHits ?rank) luc:query (${argsFor('query')}) .
    ?entity rdfs:label ?label ;
            dct:description ?desc ;
            kt:code ?code ;
            kt:course ?course ;
            kt:country ?country ;
            kt:prepMinutes ?prep ;
            kt:publishedOn ?published .
    ?country kt:inRegion ?region .
    OPTIONAL { ?entity kt:diet ?diet }
    # The fan-in, on the SPARQL side this time: the index merges these two predicates into
    # one field, so the display has to merge them too.
    OPTIONAL { ?entity kt:author|kt:testedBy ?person }
}
GROUP BY ?entity ?rank ?totalHits ?score ?label ?desc ?code ?course ?country ?region ?prep ?published
ORDER BY ?rank`;
}

function facetQuery(skip = {}, facetFields = null, pin = {}) {
    return `PREFIX luc: <urn:jena:lucene:index#>

SELECT ?field ?value ?low ?high ?count
WHERE {
    (?field ?value ?low ?high ?count) luc:facet (${argsFor('facet', skip, facetFields, pin)})
}`;
}

/**
 * One hierarchy level.
 *
 * The server returns a hierarchy one level at a time: no drill filter means the top level,
 * an "=" on a level means that level's children. So a two-level tree is two requests —
 * each omitting the filter belonging to the level it is asking about, so the level's own
 * values survive being chosen.
 */
function levelQuery(dimension, skip, pin = {}) {
    return facetQuery(skip, [dimension], pin);
}

/**
 * The right-hand Turtle panel.
 *
 * Field IRIs are used directly as predicates, which is what makes the output legible:
 * a hit reads as `field:summary "..."` — the indexed field that matched and the value it
 * matched on — rather than as a reified match object. `luc:match` supplies those; the
 * `luc:matchedRecord` blank node is a child document that the CQL filter selected, so a
 * CSV-backed review shows up as the actual row.
 *
 * The luc:query arguments are identical to the results query, so this shares one Lucene
 * execution with the results and the facets rather than searching again.
 */
function constructQuery() {
    return `PREFIX luc:   <urn:jena:lucene:index#>
PREFIX field: <urn:jena:lucene:field#>
PREFIX rdfs:  <http://www.w3.org/2000/01/rdf-schema#>
PREFIX kt:    <${KT}>

CONSTRUCT {
    ?entity rdfs:label ?label ;
            luc:rank ?rank ;
            luc:score ?score ;
            ?field ?value ;
            luc:matchedRecord ?record .
    ?record ?nfield ?nvalue .
}
WHERE {
    (?hit ?entity ?score ?totalHits ?rank) luc:query (${argsFor('query')}) .
    ?entity rdfs:label ?label .
    OPTIONAL { (?hit ?field ?value) luc:match () }
    OPTIONAL { (?hit ?record ?nfield ?nvalue) luc:nestedMatch () }
}`;
}

/**
 * The record itself, as it sits in the graph — its own triples and nothing else.
 *
 * Deliberately separate from constructQuery(): that one is the *index* view (rank, score,
 * which field matched, which child records the filter selected), and it belongs in the
 * right-hand panel. This is the RDF the index was built FROM.
 *
 * The contrast is worth noticing on any card: reviews never appear here, because they live
 * in data/reviews.csv and were never loaded into the graph. They exist only in the index,
 * which is exactly where the right-hand panel shows them.
 */
function recordQuery() {
    return `PREFIX luc: <urn:jena:lucene:index#>

CONSTRUCT { ?entity ?p ?o }
WHERE {
    (?hit ?entity) luc:query (${argsFor('query')}) .
    ?entity ?p ?o .
}`;
}

/** Which indexed field each hit matched on, for the "matched on" line of a card. */
function matchQuery() {
    return `PREFIX luc: <urn:jena:lucene:index#>

SELECT ?entity ?field ?value
WHERE {
    (?hit ?entity) luc:query (${argsFor('query')}) .
    (?hit ?field ?value) luc:match () .
}`;
}

function nestedQuery() {
    return `PREFIX luc: <urn:jena:lucene:index#>

SELECT ?entity ?record ?field ?value
WHERE {
    (?hit ?entity) luc:query (${argsFor('query')}) .
    (?hit ?record ?field ?value) luc:nestedMatch () .
}`;
}

/* ── the published-date histogram ──────────────────────────────────────── */

/** Bucket edges, one per year plus a closing edge. */
function yearBoundaries() {
    return [...dateAxis.years.map(y => `${y}-01-01`), `${dateAxis.years.at(-1) + 1}-01-01`];
}

/**
 * Learn which years the data covers, so the axis is the data's rather than a guess.
 * Read from the graph, once, at startup — the range facet needs its bucket edges before
 * it can be asked for anything.
 */
async function loadDateAxis() {
    try {
        const res = await sparql(`PREFIX kt: <${KT}>
SELECT (MIN(?d) AS ?from) (MAX(?d) AS ?to) WHERE { ?s kt:publishedOn ?d }`);
        const b = res.results.bindings[0];
        const from = Number(b?.from?.value?.slice(0, 4));
        const to = Number(b?.to?.value?.slice(0, 4));
        if (!from || !to || to < from) return;
        dateAxis.years = Array.from({ length: to - from + 1 }, (_, i) => from + i);
    } catch {
        // A missing histogram is a missing control, not a broken page.
    }
}

/**
 * A range facet drawn as bars, with a two-handle slider over it.
 *
 * The point of the bars is that you can see where the data is before choosing a range —
 * a plain pair of date inputs lets you pick an empty window and gives no clue why.
 */
function renderDateHistogram(res) {
    const node = el('datehist');
    if (!node) return;
    if (!dateAxis.years.length) { node.innerHTML = ''; return; }

    const counts = new Map();
    for (const b of bindings(res || { results: { bindings: [] } })) {
        const year = Number(val(b, 'low')?.slice(0, 4));
        if (!Number.isNaN(year)) counts.set(year, Number(val(b, 'count')));
    }
    const peak = Math.max(1, ...counts.values());
    const last = dateAxis.years.length - 1;
    const [lo, hi] = state.yearIdx || [0, last];

    const bars = dateAxis.years.map((y, i) => {
        const n = counts.get(y) || 0;
        const inRange = i >= lo && i <= hi;
        // A zero-count year still gets a sliver, so the axis stays evenly spaced and a gap
        // in the data reads as a gap rather than as a missing bar.
        const h = n ? Math.max(12, Math.round((n / peak) * 100)) : 3;
        return `<div class="bar ${inRange ? 'on' : 'off'}" style="height:${h}%"
                     title="${y}: ${n} recipe${n === 1 ? '' : 's'}"><span>${n || ''}</span></div>`;
    }).join('');

    node.innerHTML = `
      <div class="bars">${bars}</div>
      <div class="rangewrap">
        <input type="range" id="year-lo" min="0" max="${last}" value="${lo}" step="1" aria-label="earliest year">
        <input type="range" id="year-hi" min="0" max="${last}" value="${hi}" step="1" aria-label="latest year">
      </div>
      <div class="axis"><span>${dateAxis.years[lo]}</span>
        ${state.yearIdx ? `<button class="linkish" id="year-clear">clear</button>` : `<span class="muted">all years</span>`}
        <span>${dateAxis.years[hi]}</span></div>`;

    let timer;
    const onSlide = () => {
        let a = Number(el('year-lo').value);
        let b2 = Number(el('year-hi').value);
        if (a > b2) [a, b2] = [b2, a];      // handles crossed: treat it as a span, not an error
        state.yearIdx = (a === 0 && b2 === last) ? null : [a, b2];
        state.page = 0;
        // Repaint the bars immediately so dragging feels attached to something, but debounce
        // the six requests a redraw costs.
        renderDateHistogram(res);
        clearTimeout(timer);
        timer = setTimeout(run, 250);
    };
    el('year-lo').oninput = onSlide;
    el('year-hi').oninput = onSlide;
    if (el('year-clear')) el('year-clear').onclick = () => { state.yearIdx = null; state.page = 0; run(); };
}

/* ── run and render ────────────────────────────────────────────────────── */

let seq = 0;

async function run() {
    const mine = ++seq;
    el('debug-sparql').textContent = searchQuery() + '\n\n/* facets */\n' + facetQuery();
    renderModeNote();

    const reviewFilterActive = !!(state.reviewer || state.minStars || state.starsExact !== null);
    const openRegions = [...state.open.region];
    const openReviewers = [...state.open.reviewer];

    // Each hierarchy level is its own request, omitting the filter for the level being
    // asked about, plus one per OPEN node for its children. That is what costs the extra
    // round trips, and what lets a node be opened without filtering and stops a chosen
    // value vanishing from the list that offers it. Irrelevant at ten documents; on a real
    // corpus it is the ordinary price of exclude-own-filter faceting.
    const jobs = {
        results: sparql(searchQuery()),
        facets: sparql(facetQuery()),
        regionTop: sparql(levelQuery(FACETS[0].key, { region: true, country: true })),
        reviewerTop: sparql(levelQuery(REVIEW_DIM.key, { reviewer: true, stars: true })),
        matched: sparql(matchQuery()),
        // Counted with the date filter itself skipped, so narrowing the range never
        // flattens the bars you are dragging over. Same rule as the hierarchy levels.
        dateHist: dateAxis.years.length
            ? sparql(facetQuery({ date: true }, [{ field: F('publishedOn'), ranges: yearBoundaries() }]))
            : null,
        nested: reviewFilterActive ? sparql(nestedQuery()) : null,
        // One Turtle fetch serves both the right-hand panel and the per-card columns, and
        // never rejects: the explanation must not be able to take the results down.
        turtle: sparqlTurtle(constructQuery()).catch(err => ({ error: err.message })),
        records: sparqlTurtle(recordQuery()).catch(err => ({ error: err.message })),
        regionKids: Promise.all(openRegions.map(v =>
            sparql(levelQuery(FACETS[0].key, { country: true }, { region: v })).then(r => [v, r]))),
        reviewerKids: Promise.all(openReviewers.map(v =>
            sparql(levelQuery(REVIEW_DIM.key, { stars: true }, { reviewer: v })).then(r => [v, r]))),
    };

    let done;
    try {
        const keys = Object.keys(jobs);
        const settled = await Promise.all(keys.map(k => jobs[k]));
        done = Object.fromEntries(keys.map((k, i) => [k, settled[i]]));
    } catch (err) {
        if (mine !== seq) return;
        el('results').innerHTML = `<div class="error">${esc(err.message)}</div>`;
        el('summary').textContent = '';
        return;
    }
    if (mine !== seq) return;

    renderTurtlePanel(done.turtle);
    const blocks = turtleBlocks(done.records);
    await renderResults(done.results, done.nested, done.matched, blocks);
    await renderFacets(done);
    renderDateHistogram(done.dateHist);
    await renderChips();
}

/**
 * Split one Turtle document into its per-subject blocks, so each result card can show its
 * own. Jena writes a subject block starting at column 0 with its predicates indented, so
 * a split before every unindented line is exactly a split between subjects.
 */
function turtleBlocks(text) {
    const blocks = new Map();
    if (typeof text !== 'string') return blocks;
    for (const part of text.split(/\n(?=\S)/)) {
        const block = part.trim();
        if (!block || block.startsWith('PREFIX') || block.startsWith('@prefix')) continue;
        const subject = block.split(/\s/)[0];
        const iri = subject.startsWith('<') ? subject.slice(1, -1)
            : subject.startsWith('kt:') ? KT + subject.slice(3)
            : null;
        if (iri) blocks.set(iri, block);
    }
    return blocks;
}

/**
 * Fetched and rendered independently of the results: this panel is an explanation, and a
 * failure to produce it must not take the search view down with it.
 */
function renderTurtlePanel(text) {
    const node = el('turtle');
    if (!node) return;
    if (typeof text !== 'string') {
        node.textContent = '# could not build the index view\n# ' + (text?.error || 'unknown error');
        return;
    }
    // Strip the prefix block: it is the same seven lines on every query and it pushes the
    // part worth reading below the fold.
    const body = text.split(/\n(?=\S)/).filter(b => !b.startsWith('PREFIX')).join('\n');
    // Under a vector query luc:match projects nothing, and the absence is the point: a KNN
    // hit is near the query in embedding space, not a term match on some field. Say so,
    // rather than leaving an unexplained gap where the field: predicates usually are.
    const note = state.mode === 'semantic'
        ? '# No field: predicates below — a KNN hit matches a vector, not a term.\n'
          + '# Similarity is the whole explanation; luc:match has nothing to project.\n\n'
        : '';
    node.textContent = note + (body.trim() || '# no hits');
}

function bindings(res) { return res.results.bindings; }
const val = (b, k) => b[k]?.value;

async function renderResults(res, nested, matched, blocks) {
    const rows = bindings(res);
    const total = rows.length ? Number(val(rows[0], 'totalHits')) : 0;

    // Every IRI on screen gets a label, resolved through the browser-cached label endpoint.
    const iris = new Set();
    for (const b of rows) {
        ['course', 'country', 'region'].forEach(k => iris.add(val(b, k)));
        (val(b, 'diets') || '').split('|').filter(Boolean).forEach(v => iris.add(v));
        (val(b, 'people') || '').split('|').filter(Boolean).forEach(v => iris.add(v));
    }
    // Field IRIs get labels too, so "matched on" reads "Summary" rather than a CURIE.
    const byMatch = new Map();
    for (const b of bindings(matched || { results: { bindings: [] } })) {
        const e = val(b, 'entity');
        if (!byMatch.has(e)) byMatch.set(e, []);
        byMatch.get(e).push({ field: val(b, 'field'), value: val(b, 'value') });
        iris.add(val(b, 'field'));
    }

    const lab = await labels.resolveMany([...iris]);
    const L = iri => lab.get(iri) || shortIri(iri);

    // Group projected CSV review rows by their ?record grouping key.
    const byEntity = new Map();
    for (const b of bindings(nested || { results: { bindings: [] } })) {
        const e = val(b, 'entity'), r = val(b, 'record');
        if (!byEntity.has(e)) byEntity.set(e, new Map());
        const recs = byEntity.get(e);
        if (!recs.has(r)) recs.set(r, {});
        recs.get(r)[val(b, 'field').replace('urn:jena:lucene:field#', '')] = val(b, 'value');
    }

    const pages = Math.ceil(total / PAGE_SIZE);
    el('summary').textContent = total
        ? `${total} recipe${total === 1 ? '' : 's'}${pages > 1 ? ` · page ${state.page + 1} of ${pages}` : ''}`
        : '';

    if (!rows.length) {
        el('results').innerHTML = `<div class="empty">Nothing matched.</div>`;
        el('pager').innerHTML = '';
        return;
    }

    el('results').innerHTML = rows.map(b => {
        const entity = val(b, 'entity');
        const diets = (val(b, 'diets') || '').split('|').filter(Boolean);
        const people = (val(b, 'people') || '').split('|').filter(Boolean);
        const recs = [...(byEntity.get(entity)?.values() || [])];
        const hits = byMatch.get(entity) || [];
        // A vector hit has no matched field: proximity is the whole explanation.
        const why = state.mode === 'semantic'
            ? `<span class="why-badge vec">nearest neighbour · cosine ${Number(val(b, 'score')).toFixed(3)}</span>`
            : hits.length
                ? hits.map(h => `<span class="why-badge" title="${esc(h.value)}">${esc(L(h.field))}</span>`).join('')
                : `<span class="why-badge none">filter only — no text match</span>`;
        const ttl = blocks?.get(entity);
        return `<article class="card">
    <div class="card-main">
      <h2>${esc(val(b, 'label'))}</h2>
      <div class="code">${esc(val(b, 'code'))} · rank ${esc(val(b, 'rank'))} · score ${Number(val(b, 'score')).toFixed(4)}</div>
      <p class="desc">${esc(val(b, 'desc'))}</p>
      <div class="badges">
        <span class="badge k">${esc(L(val(b, 'course')))}</span>
        <span class="badge k">${esc(L(val(b, 'region')))} › ${esc(L(val(b, 'country')))}</span>
        ${diets.map(d => `<span class="badge">${esc(L(d))}</span>`).join('')}
        ${people.map(p => `<span class="badge people">${esc(L(p))}</span>`).join('')}
        <span class="badge num">${esc(val(b, 'prep'))} min</span>
        <span class="badge num">${esc(val(b, 'published'))}</span>
      </div>
      ${recs.length ? `<div class="reviews"><h4>Matching reviews (from CSV, via luc:nestedMatch)</h4>
        ${recs.map(r => `<div class="review"><span class="stars">${'★'.repeat(Number(r.stars) || 0)}</span>
          ${esc(r.reviewer || '')} · ${esc(r.reviewMonth || '')}</div>`).join('')}</div>` : ''}
      <div class="why"><span class="why-label">Matched on</span>${why}</div>
    </div>
    <div class="card-ttl">
      <h4>the record in the graph</h4>
      <pre class="ttl">${esc(ttl || '# no triples')}</pre>
    </div>
  </article>`;
    }).join('');

    el('pager').innerHTML = pages > 1
        ? `<button id="prev" ${state.page === 0 ? 'disabled' : ''}>← previous</button>
       <button id="next" ${state.page >= pages - 1 ? 'disabled' : ''}>next →</button>`
        : '';
    if (pages > 1) {
        el('prev').onclick = () => { state.page--; run(); };
        el('next').onclick = () => { state.page++; run(); };
    }
}

/** @param res the bundle from run(): base facets plus one response per hierarchy level. */
async function renderFacets(res) {
    const rows = bindings(res.facets);
    const groups = new Map();   // facet key -> [{value, count}]
    const ranges = [];
    for (const b of rows) {
        const field = val(b, 'field');
        const count = Number(val(b, 'count'));
        if (b.low || b.high) {
            ranges.push({ low: b.low ? Number(val(b, 'low')) : null, high: b.high ? Number(val(b, 'high')) : null, count });
            continue;
        }
        if (!groups.has(field)) groups.set(field, []);
        groups.get(field).push({ value: val(b, 'value'), count });
    }

    const valuesOf = (response, dim) => response
        ? bindings(response).filter(b => val(b, 'field') === dim)
            .map(b => ({ value: val(b, 'value'), count: Number(val(b, 'count')) }))
        : [];

    const kidsMap = (pairs, dim) => new Map((pairs || []).map(([v, r]) => [v, valuesOf(r, dim)]));

    const regionTop = valuesOf(res.regionTop, FACETS[0].key);
    const regionKids = kidsMap(res.regionKids, FACETS[0].key);
    const reviewerTop = valuesOf(res.reviewerTop, REVIEW_DIM.key);
    const reviewerKids = kidsMap(res.reviewerKids, REVIEW_DIM.key);

    const iris = [];
    for (const list of groups.values()) list.forEach(x => { if (isIri(x.value)) iris.push(x.value); });
    regionTop.forEach(x => { if (isIri(x.value)) iris.push(x.value); });
    regionKids.forEach(list => list.forEach(x => { if (isIri(x.value)) iris.push(x.value); }));
    const lab = await labels.resolveMany(iris);
    const L = v => lab.get(v) || shortIri(v);

    // Reviewer lives in its own group next to the stars selector, because the two combine
    // into a single-child filter and belong together in the UI.
    el('facet-reviewer').innerHTML = renderTree(reviewerTop, reviewerKids, {
        parentKind: 'reviewer', childKind: 'stars-exact', openKind: 'open-reviewer',
        open: state.open.reviewer,
        parentSel: state.reviewer,
        childSel: state.starsExact === null ? null : String(state.starsExact),
        label: v => v, childLabel: v => `<span class="stars">${'★'.repeat(Number(v) || 0)}</span>`,
    });

    let html = '';
    if (regionTop.length) {
        html += `<div class="facet-group"><h3>${esc(FACETS[0].title)}</h3>`
            + renderTree(regionTop, regionKids, {
                parentKind: 'region', childKind: 'country', openKind: 'open-region',
                open: state.open.region,
                parentSel: state.region, childSel: state.country,
                label: L, childLabel: v => esc(L(v)),
            })
            + `</div>`;
    }
    for (const f of FACETS) {
        if (f.dimension) continue;
        const list = groups.get(f.key) || [];
        if (!list.length) continue;
        html += `<div class="facet-group"><h3>${esc(f.title)}</h3>`;
        if (f.note) html += `<p class="hint">${esc(f.note)}</p>`;
        html += renderOptions(f.key, list, L, 'multi');
        html += `</div>`;
    }

    if (ranges.length) {
        html += `<div class="facet-group"><h3>Prep time</h3><ul>` + ranges.map(r => {
            const label = r.low === null ? `under ${r.high} min`
                : r.high === null ? `${r.low} min and over` : `${r.low}–${r.high} min`;
            const on = state.prepBucket && state.prepBucket[0] === r.low && state.prepBucket[1] === r.high;
            return `<li><label class="opt"><input type="checkbox" data-kind="prep"
              data-low="${r.low ?? ''}" data-high="${r.high ?? ''}" ${on ? 'checked' : ''}>
              <span class="name">${esc(label)}</span><span class="count">${r.count}</span></label></li>`;
        }).join('') + `</ul></div>`;
    }

    el('facets').innerHTML = html;
    wireFacetInputs();
}

/**
 * A hierarchy, drawn as a tree.
 *
 * Both levels are on screen at once: every parent, with the chosen one ticked, and that
 * parent's children nested beneath it with the chosen child ticked. Choosing a value must
 * never remove it from the list you chose it from, and must never hide its siblings — the
 * two levels come from separate requests precisely so that both survive selection.
 *
 * Unticking a parent clears the child with it; there is no separate "back" affordance,
 * because the tick is the state and the tick is how you undo it.
 */
function renderTree(parents, kids, o) {
    if (!parents.length) return '';
    return `<ul>` + parents.map(x => {
        const on = o.parentSel === x.value;
        const open = o.open.has(x.value);
        const mine = kids.get(x.value) || [];
        const child = open && mine.length
            ? `<ul class="hier-children">` + mine.map(k => {
                const kon = o.childSel === k.value;
                return `<li><label class="opt"><input type="checkbox" data-kind="${o.childKind}"
                  data-value="${esc(k.value)}" ${kon ? 'checked' : ''}>
                  <span class="name">${o.childLabel(k.value)}</span>
                  <span class="count">${k.count}</span></label></li>`;
            }).join('') + `</ul>`
            : '';
        // The twisty and the checkbox are deliberately separate controls: opening a node
        // asks luc:facet for its children and changes nothing about the result set, while
        // ticking it adds a filter. Neither implies the other, though ticking also opens,
        // since wanting a value's children is the usual next thing.
        return `<li><div class="hier-row">
              <button class="twisty" data-kind="${o.openKind}" data-value="${esc(x.value)}"
                      aria-expanded="${open}" title="${open ? 'collapse' : 'show what is inside'}">${open ? '▾' : '▸'}</button>
              <label class="opt"><input type="checkbox" data-kind="${o.parentKind}"
                data-value="${esc(x.value)}" ${on ? 'checked' : ''}>
                <span class="name">${esc(o.label(x.value))}</span>
                <span class="count">${x.count}</span></label>
            </div>${child}</li>`;
    }).join('') + `</ul>`;
}

function renderOptions(field, list, L, kind) {
    if (!list.length) return '';
    return `<ul>` + list.map(x => {
        const on = kind === 'reviewer' ? state.reviewer === x.value : state.sel[field]?.has(x.value);
        return `<li><label class="opt"><input type="checkbox" data-kind="${kind}" data-field="${esc(field)}"
      data-value="${esc(x.value)}" ${on ? 'checked' : ''}>
      <span class="name">${esc(L(x.value))}</span><span class="count">${x.count}</span></label></li>`;
    }).join('') + `</ul>`;
}

const toggle = (set, v) => { set.has(v) ? set.delete(v) : set.add(v); };

function wireFacetInputs() {
    document.querySelectorAll('#facets [data-kind], #facet-reviewer [data-kind]').forEach(node => {
        node.onclick = ev => {
            const d = ev.currentTarget.dataset;
            state.page = 0;
            switch (d.kind) {
                case 'multi': {
                    const set = state.sel[d.field] || (state.sel[d.field] = new Set());
                    set.has(d.value) ? set.delete(d.value) : set.add(d.value);
                    break;
                }
                case 'open-region':
                    toggle(state.open.region, d.value);
                    break;
                case 'open-reviewer':
                    toggle(state.open.reviewer, d.value);
                    break;
                case 'reviewer':
                    state.reviewer = state.reviewer === d.value ? null : d.value;
                    state.starsExact = null;
                    if (state.reviewer) state.open.reviewer.add(d.value);
                    break;
                case 'stars-exact': {
                    const n = Number(d.value);
                    state.starsExact = state.starsExact === n ? null : n;
                    break;
                }
                case 'region':
                    state.region = state.region === d.value ? null : d.value;
                    state.country = null;
                    if (state.region) state.open.region.add(d.value);
                    break;
                case 'country':
                    state.country = state.country === d.value ? null : d.value;
                    break;
                case 'prep': {
                    const lo = d.low === '' ? null : Number(d.low);
                    const hi = d.high === '' ? null : Number(d.high);
                    const same = state.prepBucket && state.prepBucket[0] === lo && state.prepBucket[1] === hi;
                    state.prepBucket = same ? null : [lo, hi];
                    break;
                }
            }
            run();
        };
    });
}

/**
 * Chips carry labels, not CURIEs — for the value AND for the field it belongs to. Field
 * IRIs have rdfs:label in the data exactly so this can go through the same label cache as
 * everything else, instead of title-casing idx:fieldName in JavaScript.
 */
async function renderChips() {
    const chips = [];
    const add = (fieldIri, value, text, clear) => chips.push({ fieldIri, value, text, clear });

    for (const [field, values] of Object.entries(state.sel)) {
        for (const v of values) add(field, v, null, () => state.sel[field].delete(v));
    }
    if (state.region) add(F('region'), state.region, null, () => { state.region = null; state.country = null; });
    if (state.country) add(F('country'), state.country, null, () => { state.country = null; });
    if (state.yearIdx) {
        const [lo, hi] = state.yearIdx;
        add(F('publishedOn'), null,
            lo === hi ? `${dateAxis.years[lo]}` : `${dateAxis.years[lo]}–${dateAxis.years[hi]}`,
            () => { state.yearIdx = null; });
    }
    if (state.reviewer) add(F('reviewer'), null, state.reviewer, () => { state.reviewer = null; state.starsExact = null; });
    if (state.starsExact !== null) add(F('stars'), null, '★'.repeat(state.starsExact), () => { state.starsExact = null; });
    else if (state.minStars) add(F('stars'), null, `${state.minStars} or more`, () => { state.minStars = ''; el('min-stars').value = ''; });
    if (state.prepBucket) {
        const [lo, hi] = state.prepBucket;
        add(F('prepMinutes'), null, lo === null ? `under ${hi} min` : hi === null ? `${lo} min and over` : `${lo}–${hi} min`,
            () => { state.prepBucket = null; });
    }

    const wanted = [];
    chips.forEach(c => { wanted.push(c.fieldIri); if (isIri(c.value)) wanted.push(c.value); });
    const lab = await labels.resolveMany(wanted);
    const L = v => lab.get(v) || shortIri(v);

    el('chips').innerHTML = chips.map((c, i) =>
        `<span class="chip"><span class="chip-field">${esc(L(c.fieldIri))}</span>${esc(c.text ?? L(c.value))}` +
        `<button data-i="${i}" title="remove">×</button></span>`).join('');
    el('chips').querySelectorAll('button').forEach(b => {
        b.onclick = () => { chips[Number(b.dataset.i)].clear(); state.page = 0; run(); };
    });
}

function renderModeNote() {
    const note = el('mode-note');
    if (state.mode === 'semantic') {
        note.className = 'note';
        note.innerHTML = '<strong>Semantic mode.</strong> The only change is the <code>fieldSpec</code> argument — '
            + 'naming the vector field makes the search box text to embed rather than a Lucene expression. '
            + 'Filters below are pushed <em>into</em> the KNN traversal, and facet counts become top-k scoped. '
            + 'Try <em>a coffee flavoured Italian pudding</em>, <em>a savoury broth with bean curd</em>, or '
            + '<em>meat cooked very slowly in wine until tender</em> — none share a word with the recipe they '
            + 'find. Switch back to Keyword to see BM25 miss them. '
            + 'Note that KNN always returns the k nearest neighbours, so with ten recipes everything comes '
            + 'back, ranked — there is no relevance cutoff.';
        note.classList.remove('hidden');
    } else if (state.mode === 'code') {
        note.className = 'note';
        note.textContent = 'Typeahead mode. The box drives a text_query on an edge-n-gram field — try "2019", "0042" or "RCP-2021".';
        note.classList.remove('hidden');
    } else {
        note.classList.add('hidden');
    }
}

/* ── the feature-test panel ────────────────────────────────────────────── */

function renderTestList() {
    el('test-list').innerHTML = TESTS.map(name => {
        const [num, ...rest] = name.split('-');
        return `<li><button data-file="${name}.rq"><span class="num">${num}</span>${esc(rest.join(' '))}</button></li>`;
    }).join('');
    el('test-list').querySelectorAll('button').forEach(b => { b.onclick = () => runTest(b.dataset.file); });
}

async function runTest(file) {
    const main = el('results');
    el('summary').textContent = '';
    el('pager').innerHTML = '';
    el('chips').innerHTML = '';
    main.innerHTML = `<div class="empty">running ${esc(file)}…</div>`;
    let text;
    try {
        const r = await fetch(`queries/${file}`);
        if (!r.ok) throw new Error(`could not read queries/${file} (HTTP ${r.status})`);
        text = await r.text();
    } catch (err) {
        main.innerHTML = `<div class="error">${esc(err.message)}</div>`;
        return;
    }
    let res;
    try {
        res = await sparql(text);
    } catch (err) {
        main.innerHTML = `<div class="testview"><pre class="rq">${esc(text)}</pre>
      <div class="error">${esc(err.message)}</div></div>`;
        return;
    }
    const vars = res.head.vars;
    const rows = res.results.bindings;
    main.innerHTML = `<div class="testview">
    <h2>${esc(file)}</h2>
    <pre class="rq">${esc(text)}</pre>
    ${rows.length ? `<table class="res"><thead><tr>${vars.map(v => `<th>${esc(v)}</th>`).join('')}</tr></thead>
      <tbody>${rows.map(b => `<tr>${vars.map(v => `<td>${esc(shortIri(b[v]?.value ?? ''))}</td>`).join('')}</tr>`).join('')}</tbody>
    </table><p class="hint">${rows.length} row${rows.length === 1 ? '' : 's'}</p>`
            : `<div class="empty">no rows</div>`}
  </div>`;
}

/* ── startup ───────────────────────────────────────────────────────────── */

async function loadConfig() {
    const badge = el('index-status');
    try {
        const r = await fetch(`${BASE}/$/config/effective`, { headers: { Accept: 'application/json' } });
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        const cfg = await r.json();
        state.config = cfg;
        const ds = (cfg.datasets || []).find(d => d.shaclIndex);
        if (!ds) { badge.textContent = 'no SHACL index'; badge.className = 'status bad'; return; }
        const status = ds.index?.status || 'UNKNOWN';
        const nFields = ds.profiles?.[0]?.fields?.length ?? 0;
        // Trust the server's names for the hierarchy dimensions rather than assuming
        // "region_country" / "reviewer_stars": both are derived by joining idx:fieldName
        // values, which this client does not get to decide.
        const hiers = ds.profiles?.[0]?.hierarchies || [];
        const byLevel = name => hiers.find(h => (h.levels || []).includes(name))?.dimension;
        const dim = byLevel('region');
        if (dim) FACETS[0].key = dim;
        const rdim = byLevel('reviewer');
        if (rdim) REVIEW_DIM.key = rdim;
        badge.textContent = `index ${status} · ${nFields} fields`;
        badge.className = 'status ' + (status === 'MATCH' ? 'ok' : status === 'MISMATCH' ? 'bad' : 'warn');
        badge.title = `GET /$/config/effective\nfingerprint ${status}\nbuilt ${ds.index?.builtAt || '?'}\nhierarchy: ${dim || 'none'}`;
    } catch (err) {
        badge.textContent = 'config endpoint unavailable';
        badge.className = 'status warn';
    }
}

function wireChrome() {
    document.querySelectorAll('.tab').forEach(t => {
        t.onclick = () => {
            document.querySelectorAll('.tab').forEach(x => x.classList.toggle('active', x === t));
            el('panel-facets').classList.toggle('hidden', t.dataset.tab !== 'facets');
            el('panel-tests').classList.toggle('hidden', t.dataset.tab !== 'tests');
        };
    });

    let timer;
    el('q').oninput = ev => {
        state.q = ev.target.value; state.page = 0;
        clearTimeout(timer);
        timer = setTimeout(run, 200);
    };
    el('mode').onchange = ev => { state.mode = ev.target.value; state.page = 0; run(); };
    el('sort').onchange = ev => { state.sort = ev.target.value; state.page = 0; run(); };
    el('min-stars').onchange = ev => { state.minStars = ev.target.value; state.page = 0; run(); };
    el('reset').onclick = () => {
        Object.assign(state, {
            q: '', mode: 'bm25', sort: '', page: 0, sel: {}, region: null, country: null,
            yearIdx: null, minStars: '', reviewer: null, starsExact: null, prepBucket: null,
            open: { region: new Set(), reviewer: new Set() },
        });
        el('q').value = ''; el('mode').value = 'bm25'; el('sort').value = '';
        el('min-stars').value = '';
        run();
    };
}

(async function main() {
    wireChrome();
    renderTestList();
    await Promise.all([loadConfig(), loadDateAxis()]);
    run();
})();
