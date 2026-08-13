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
];

const state = {
    q: '', mode: 'bm25', sort: '', page: 0,
    sel: {},            // fieldIRI -> Set of values (multi-select)
    region: null,       // hierarchy drill-down: the selected parent level
    country: null,
    dateFrom: '', dateTo: '',
    minStars: '', reviewer: null, starsExact: null,
    prepBucket: null,   // [low, high]
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

function buildFilter() {
    const clauses = [];

    for (const [field, values] of Object.entries(state.sel)) {
        if (values.size) clauses.push(anyOf(field, [...values]));
    }

    // A "=" on a hierarchy level both narrows the results AND tells luc:facet to return
    // that level's children. One clause, both jobs — there is no drill-down argument.
    if (state.region) clauses.push(eq(F('region'), state.region));
    if (state.country) clauses.push(eq(F('country'), state.country));

    if (state.dateFrom && state.dateTo) {
        clauses.push({ op: 'between', args: [prop(F('publishedOn')), state.dateFrom, state.dateTo] });
    } else if (state.dateFrom) {
        clauses.push({ op: '>=', args: [prop(F('publishedOn')), state.dateFrom] });
    } else if (state.dateTo) {
        clauses.push({ op: '<=', args: [prop(F('publishedOn')), state.dateTo] });
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
    if (state.reviewer) clauses.push(eq(F('reviewer'), state.reviewer));
    if (state.starsExact !== null) {
        clauses.push(eq(F('stars'), state.starsExact));
    } else if (state.minStars) {
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
function argsFor(kind) {
    const fs = fieldSpec();
    const spec = fs === 'default' ? '"default"' : `'${fs}'`;
    const q = lit(queryString());
    const filter = buildFilter();
    const f = filter ? `'${filter}'` : '""';
    if (kind === 'facet') {
        const fields = JSON.stringify([
            ...FACETS.map(x => x.key),
            REVIEW_DIM.key,
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

function facetQuery() {
    return `PREFIX luc: <urn:jena:lucene:index#>

SELECT ?field ?value ?low ?high ?count
WHERE {
    (?field ?value ?low ?high ?count) luc:facet (${argsFor('facet')})
}`;
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

function nestedQuery() {
    return `PREFIX luc: <urn:jena:lucene:index#>

SELECT ?entity ?record ?field ?value
WHERE {
    (?hit ?entity) luc:query (${argsFor('query')}) .
    (?hit ?record ?field ?value) luc:nestedMatch () .
}`;
}

/* ── run and render ────────────────────────────────────────────────────── */

let seq = 0;

async function run() {
    const mine = ++seq;
    el('debug-sparql').textContent = searchQuery() + '\n\n/* facets */\n' + facetQuery();
    renderModeNote();
    renderChips();

    renderTurtle();

    const reviewFilterActive = !!(state.reviewer || state.minStars || state.starsExact !== null);
    const jobs = [sparql(searchQuery()), sparql(facetQuery())];
    if (reviewFilterActive) jobs.push(sparql(nestedQuery()));

    let results, facets, nested;
    try {
        [results, facets, nested] = await Promise.all(jobs);
    } catch (err) {
        if (mine !== seq) return;
        el('results').innerHTML = `<div class="error">${esc(err.message)}</div>`;
        el('summary').textContent = '';
        return;
    }
    if (mine !== seq) return;

    await renderResults(results, nested);
    await renderFacets(facets);
}

/**
 * Fetched and rendered independently of the results: this panel is an explanation, and a
 * failure to produce it must not take the search view down with it.
 */
async function renderTurtle() {
    const node = el('turtle');
    if (!node) return;
    const mine = seq;
    let text;
    try {
        text = await sparqlTurtle(constructQuery());
    } catch (err) {
        if (mine !== seq) return;
        node.textContent = '# could not build the index view\n# ' + err.message;
        return;
    }
    if (mine !== seq) return;
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

async function renderResults(res, nested) {
    const rows = bindings(res);
    const total = rows.length ? Number(val(rows[0], 'totalHits')) : 0;

    // Every IRI on screen gets a label, resolved through the browser-cached label endpoint.
    const iris = new Set();
    for (const b of rows) {
        ['course', 'country', 'region'].forEach(k => iris.add(val(b, k)));
        (val(b, 'diets') || '').split('|').filter(Boolean).forEach(v => iris.add(v));
        (val(b, 'people') || '').split('|').filter(Boolean).forEach(v => iris.add(v));
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
        return `<article class="card">
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

async function renderFacets(res) {
    const rows = bindings(res);
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

    const iris = [];
    for (const list of groups.values()) list.forEach(x => { if (isIri(x.value)) iris.push(x.value); });
    // The drilled-into parent is not in any returned list — the server has moved on to its
    // children — so it has to be added explicitly or the heading shows a raw IRI.
    [state.region, state.country].forEach(v => { if (isIri(v)) iris.push(v); });
    const lab = await labels.resolveMany(iris);
    const L = v => lab.get(v) || shortIri(v);

    // Reviewer lives in its own group next to the stars selector, because the two combine
    // into a single-child filter and belong together in the UI.
    el('facet-reviewer').innerHTML = renderReviewHierarchy(groups.get(REVIEW_DIM.key) || []);

    let html = '';
    for (const f of FACETS) {
        const list = groups.get(f.key) || [];
        if (!list.length) continue;
        html += `<div class="facet-group"><h3>${esc(f.title)}</h3>`;
        if (f.note) html += `<p class="hint">${esc(f.note)}</p>`;
        html += f.dimension ? renderHierarchy(list, L) : renderOptions(f.key, list, L, 'multi');
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
 * The hierarchy renders one level at a time, because that is what the server returns: no
 * drill-down filter means the top level, a "=" on the parent means that parent's children.
 */
function renderHierarchy(list, L) {
    if (state.region) {
        const back = `<button class="linkish" data-kind="hier-clear">← all regions</button>`;
        const head = `<div class="hier-parent"><strong>${esc(L(state.region))}</strong></div>`;
        const kids = `<ul class="hier-children">` + list.map(x => {
            const on = state.country === x.value;
            return `<li><label class="opt"><input type="checkbox" data-kind="country" data-value="${esc(x.value)}" ${on ? 'checked' : ''}>
        <span class="name">${esc(L(x.value))}</span><span class="count">${x.count}</span></label></li>`;
        }).join('') + `</ul>`;
        return back + head + kids;
    }
    return `<ul>` + list.map(x =>
        `<li><label class="opt"><input type="checkbox" data-kind="region" data-value="${esc(x.value)}">
      <span class="name">${esc(L(x.value))}</span><span class="count">${x.count}</span></label></li>`
    ).join('') + `</ul>`;
}

/**
 * The reviewer dimension, one level at a time. With no reviewer selected the server
 * returns reviewer names; the "=" on field:reviewer that selecting one adds turns the same
 * request into that reviewer's own star breakdown — correlated per review row, so Priya's
 * "5" counts only Priya's five-star reviews.
 */
function renderReviewHierarchy(list) {
    if (!list.length && !state.reviewer) return '';
    if (state.reviewer) {
        // Once a star value is picked the drill path is complete, so the server has no
        // deeper level to return and `list` comes back empty. Keep the chosen value on
        // screen anyway, or there would be no way to unpick it from this panel.
        if (!list.length && state.starsExact !== null) {
            list = [{ value: String(state.starsExact), count: '' }];
        }
        const stars = list.map(x => {
            const on = state.starsExact === Number(x.value);
            return `<li><label class="opt"><input type="checkbox" data-kind="stars-exact" data-value="${esc(x.value)}" ${on ? 'checked' : ''}>
        <span class="name stars">${'★'.repeat(Number(x.value) || 0)}</span><span class="count">${x.count}</span></label></li>`;
        }).join('');
        return `<button class="linkish" data-kind="reviewer-clear">← all reviewers</button>
      <div class="hier-parent"><strong>${esc(state.reviewer)}</strong></div>
      <ul class="hier-children">${stars}</ul>`;
    }
    return `<ul>` + list.map(x =>
        `<li><label class="opt"><input type="checkbox" data-kind="reviewer" data-value="${esc(x.value)}">
      <span class="name">${esc(x.value)}</span><span class="count">${x.count}</span></label></li>`
    ).join('') + `</ul>`;
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

function wireFacetInputs() {
    document.querySelectorAll('#facets input, #facet-reviewer input, #facets [data-kind], #facet-reviewer [data-kind]').forEach(node => {
        node.onclick = ev => {
            const d = ev.currentTarget.dataset;
            state.page = 0;
            switch (d.kind) {
                case 'multi': {
                    const set = state.sel[d.field] || (state.sel[d.field] = new Set());
                    set.has(d.value) ? set.delete(d.value) : set.add(d.value);
                    break;
                }
                case 'reviewer':
                    state.reviewer = state.reviewer === d.value ? null : d.value;
                    state.starsExact = null;
                    break;
                case 'reviewer-clear':
                    state.reviewer = null; state.starsExact = null;
                    break;
                case 'stars-exact': {
                    const n = Number(d.value);
                    state.starsExact = state.starsExact === n ? null : n;
                    break;
                }
                case 'region':
                    state.region = state.region === d.value ? null : d.value;
                    state.country = null;
                    break;
                case 'country':
                    state.country = state.country === d.value ? null : d.value;
                    break;
                case 'hier-clear':
                    state.region = null; state.country = null;
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

function renderChips() {
    const chips = [];
    const add = (text, clear) => chips.push({ text, clear });
    for (const [field, values] of Object.entries(state.sel)) {
        for (const v of values) add(`${field.split('#')[1]}: ${shortIri(v)}`, () => state.sel[field].delete(v));
    }
    if (state.region) add(`region: ${shortIri(state.region)}`, () => { state.region = null; state.country = null; });
    if (state.country) add(`country: ${shortIri(state.country)}`, () => { state.country = null; });
    if (state.dateFrom) add(`from ${state.dateFrom}`, () => { state.dateFrom = ''; el('date-from').value = ''; });
    if (state.dateTo) add(`to ${state.dateTo}`, () => { state.dateTo = ''; el('date-to').value = ''; });
    if (state.reviewer) add(`reviewer: ${state.reviewer}`, () => { state.reviewer = null; state.starsExact = null; });
    if (state.starsExact !== null) add(`stars = ${state.starsExact}`, () => { state.starsExact = null; });
    else if (state.minStars) add(`stars ≥ ${state.minStars}`, () => { state.minStars = ''; el('min-stars').value = ''; });
    if (state.prepBucket) add(`prep ${state.prepBucket[0] ?? ''}–${state.prepBucket[1] ?? ''} min`, () => { state.prepBucket = null; });

    el('chips').innerHTML = chips.map((c, i) =>
        `<span class="chip">${esc(c.text)}<button data-i="${i}" title="remove">×</button></span>`).join('');
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
    el('date-from').onchange = ev => { state.dateFrom = ev.target.value; state.page = 0; run(); };
    el('date-to').onchange = ev => { state.dateTo = ev.target.value; state.page = 0; run(); };
    el('date-clear').onclick = () => {
        state.dateFrom = state.dateTo = ''; el('date-from').value = ''; el('date-to').value = '';
        state.page = 0; run();
    };
    el('reset').onclick = () => {
        Object.assign(state, {
            q: '', mode: 'bm25', sort: '', page: 0, sel: {}, region: null, country: null,
            dateFrom: '', dateTo: '', minStars: '', reviewer: null, starsExact: null, prepBucket: null,
        });
        el('q').value = ''; el('mode').value = 'bm25'; el('sort').value = '';
        el('min-stars').value = ''; el('date-from').value = ''; el('date-to').value = '';
        run();
    };
}

(async function main() {
    wireChrome();
    renderTestList();
    await loadConfig();
    run();
})();
