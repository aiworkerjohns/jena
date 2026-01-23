---
name: OptionB_LuceneGeoFacets
overview: Implement high-performance “text + spatial filter + facets + coordinates” by indexing geo into the Lucene text index and executing one Lucene query (text MUST + geo FILTER) with DocValues faceting.
todos:
  - id: design-doc-producer-entity-mode
    content: Decide and implement entity-based Lucene document production so geo+text+facet fields co-reside (use `text:textDocProducer` hook).
    status: pending
  - id: geo-field-config
    content: Extend `TextIndexConfig` and assembler to declare geo fields and input format (start with WKT POINT).
    status: pending
  - id: index-geo-fields
    content: Update `TextIndexLucene.doc(Entity)` to add Lucene geo fields (LatLonPoint + StoredField lat/lon) for configured geo fields.
    status: pending
  - id: query-text-geo-facets
    content: "Add combined query execution path: MUST text query + FILTER geo query, then compute facets via FacetsCollector/SortedSetDocValuesFacetCounts."
    status: pending
  - id: sparql-surface
    content: Introduce or extend a SPARQL property function that supports geo constraints and returns coords + facets for the hit list.
    status: pending
  - id: tests-perf
    content: Add unit/integration tests and a benchmark plan for large hit sets and high-cardinality facets.
    status: pending
  - id: docs-rollout
    content: Document config, supported operators (bbox first), and reindex/rollout procedure.
    status: pending
  - id: docs-feat-set
    content: Create a full `jena/docs/FEAT_SPATIAL_FACETS_*` doc set (SPEC/PLAN/STATUS/TESTING/OUTPUT/SUMMARY/ASSESSMENT) mirroring the faceting feature docs.
    status: pending
---

## Goal

Support queries that return:

- **hits** (entity URIs)
- **scores**
- **stored coordinates** for each hit
- **facet counts** computed over the same filtered hit set
- optional **spatial filter** (bbox first; polygon next)

All in a way that scales to very large indexes (600GB+).

## Current baseline (what we’ll extend)

- Lucene docs are built in `TextIndexLucene.doc(Entity)`:
- Adds the entity URI field and then `Field(field, value, ftText)` for each mapped value.
- Adds **native facet DocValues** when `facetFields.contains(field)`:
- [`/Users/hjohns/workspace/kurrawong/fuseki/jena/jena-text/src/main/java/org/apache/jena/query/text/TextIndexLucene.java`](/Users/hjohns/workspace/kurrawong/fuseki/jena/jena-text/src/main/java/org/apache/jena/query/text/TextIndexLucene.java) (see `doc(...)` adding `new SortedSetDocValuesFacetField(field, value)`).
- Default indexing model is **one Lucene document per quad** via `TextDocProducerTriples`:
- [`/Users/hjohns/workspace/kurrawong/fuseki/jena/jena-text/src/main/java/org/apache/jena/query/text/TextDocProducerTriples.java`](/Users/hjohns/workspace/kurrawong/fuseki/jena/jena-text/src/main/java/org/apache/jena/query/text/TextDocProducerTriples.java)

## Key design decision (required for performance)

### Decision: move to “one Lucene document per entity” for geo+text+facets coherence

At 600GB+, keeping “one doc per triple” makes combined constraints expensive (you end up doing internal joins by entity URI across many Lucene docs).

Plan:

- Introduce (or complete) an **entity-mode doc producer** so each entity’s text + facet fields + geo live in one Lucene doc.
- There is already a scaffold: `TextDocProducerEntities` (currently marked unused) that shows entity-aggregation intent:
- [`/Users/hjohns/workspace/kurrawong/fuseki/jena/jena-text/src/main/java/org/apache/jena/query/text/TextDocProducerEntities.java`](/Users/hjohns/workspace/kurrawong/fuseki/jena/jena-text/src/main/java/org/apache/jena/query/text/TextDocProducerEntities.java)
- Wire this via assembler `text:textDocProducer` (already supported) so deployments can choose:
- [`/Users/hjohns/workspace/kurrawong/fuseki/jena/jena-text/src/main/java/org/apache/jena/query/text/assembler/TextDatasetAssembler.java`](/Users/hjohns/workspace/kurrawong/fuseki/jena/jena-text/src/main/java/org/apache/jena/query/text/assembler/TextDatasetAssembler.java)

If we can’t switch the producer immediately, we can implement a fallback “join-by-entityField” strategy, but that will be a second-best path for large spatial result sets.

## Phase 1: Geo indexing (points) + bbox filter (MVP)

### 1) Configuration surface

- Add config to declare one or more **geo fields** in the text index.
- Extend `TextIndexConfig` (similar to existing `facetFields`) to hold:
- geo field names (e.g., `"geo"`)
- geo input format (start with **WKT POINT** or explicit lat/lon literals)
- which stored output fields to expose (lat/lon)
- Base class: [`/Users/hjohns/workspace/kurrawong/fuseki/jena/jena-text/src/main/java/org/apache/jena/query/text/TextIndexConfig.java`](/Users/hjohns/workspace/kurrawong/fuseki/jena/jena-text/src/main/java/org/apache/jena/query/text/TextIndexConfig.java)

### 2) Index-time document building changes

- In `TextIndexLucene.doc(Entity)`, detect configured geo fields and add Lucene geo fields instead of plain text fields:
- For point indexing: add `LatLonPoint(geoField, lat, lon)`.
- For retrieval: store `StoredField(geoField+"_lat", lat)` and `StoredField(geoField+"_lon", lon)` (or equivalent).
- Input parsing strategy (MVP):
- Support WKT `POINT(lon lat)` or `POINT(lat lon)` as required by your dataset; document the expected order.
- Defer complex GeoSPARQL parsing; keep it narrow and explicit.

### 3) Query-time changes: combine text + bbox in one Lucene query

- Extend the faceted search path (used by `TextQueryFacetsPF`) to accept an optional geo constraint and translate it into a Lucene `Query`:
- `BooleanQuery(MUST textQuery, FILTER bboxQuery)`
- Run once; compute facets via `FacetsCollector` (same pattern you already use for filtered facets).
- Ensure `text:facetCounts` also optionally accepts geo constraints *if* we want “facets-only” endpoint with spatial filtering.

### 4) SPARQL/API surface for Fuseki

Choose one of these (both are viable):

- **A. Extend `text:queryWithFacets` subject arity** to optionally return `?lat ?lon`.
- Currently supports up to 5 vars (doc, score, facetField, facetValue, facetCount):
- [`/Users/hjohns/workspace/kurrawong/fuseki/jena/jena-text/src/main/java/org/apache/jena/query/text/TextQueryFacetsPF.java`](/Users/hjohns/workspace/kurrawong/fuseki/jena/jena-text/src/main/java/org/apache/jena/query/text/TextQueryFacetsPF.java)
- **B. Add a new PF** (cleaner long-term) e.g. `text:search` that returns:
- `(?doc ?score ?lat ?lon ?facetField ?facetValue ?facetCount)`
- and takes a structured object list including query string, geo constraint, facet fields.

For Option B planning, I’d do **B** to avoid backward-compat ambiguity.

## Phase 2: Polygon support (intersects/within)

- Add polygon query support using Lucene shape queries (e.g., `LatLonShape.newPolygonQuery(...)`).
- Expand the geo indexing:
- For entities with polygon geometry, either:
- index the polygon into Lucene shapes and query directly, or
- index a representative point (centroid) for MVP but document that this is not correct for intersects.
- Add query operators: bbox, distance, polygon-intersects.

## Phase 3: Facet correctness + performance hardening at scale

- Limitations to address explicitly:
- huge bbox/polygon may match many docs → facet counts still OK but query latency depends on hit count.
- high-cardinality facet fields increase memory/time for `getTopChildren(maxValues, field)`.
- Implement guardrails:
- enforce `maxFacetValues` bounds
- optional `topKHits` / early termination strategy for UI (return approximate facets if needed)
- caching of `SortedSetDocValuesReaderState` already implied by your current design

## Phase 4: Test + benchmark strategy

- Add unit tests for:
- WKT point parsing
- bbox query assembly
- "text+geo filter" produces the correct facet counts
- Add integration tests for Fuseki query path similar to existing faceting tests.
- Add perf tests:
- synthetic large-ish (10M docs) if possible in CI
- local benchmark harness for realistic data profiles

## Migration / operational plan

- This requires a **full reindex** to add geo fields.
- Provide documentation for configuration and a recommended rollout path:
- build new index in parallel
- cutover dataset configuration

## Deliverables

- Config: new geo field configuration in `TextIndexConfig` + assembler parsing.
- Indexing: geo fields added in `TextIndexLucene.doc(Entity)`.
- Querying: combined Lucene query execution path with facets.
- SPARQL: new/extended PF to expose geo filter + return coords + facets.
- Tests + docs.
- Documentation set (mirror existing faceting docs pattern under `jena/docs/`):
- `FEAT_SPATIAL_FACETS_SPEC.md` (feature spec + usage)
- `FEAT_SPATIAL_FACETS_PLAN.md` (implementation plan/change log)
- `FEAT_SPATIAL_FACETS_STATUS.md` (current state + checklist)
- `FEAT_SPATIAL_FACETS_TESTING.md` (how to build/run, how to query via HTTP)
- `FEAT_SPATIAL_FACETS_OUTPUT.md` (captured outputs from tests/queries)
- `FEAT_SPATIAL_FACETS_SUMMARY.md` (what shipped, constraints)
- `FEAT_SPATIAL_FACETS_ASSESSMENT.md` (post-implementation assessment/risks)