# demo-mini

Ten made-up recipes, one SHACL shape, and every feature of this fork's entity-per-document
index demonstrated exactly once. Small enough that you can check any facet count by eye.

It is deliberately separate from `demo/`, which has grown to three data files, 25 queries
and a 3,000-line app. Nothing here is shared with it.

```bash
task build     # once — builds the Fuseki server jar
task up        # load, index, serve, and open the app on :8070
```

Without `go-task`, the same four steps are:

```bash
mvn install -pl jena-fuseki2/jena-fuseki-server -am -DskipTests -Drat.skip   # from repo root
JAR=../jena-fuseki2/jena-fuseki-server/target/jena-fuseki-server-6.2.0-SNAPSHOT.jar
rm -rf DB Lucene Taxonomy
java -cp $JAR tdb2.tdbloader --loc=DB data/kitchen.ttl
java -cp $JAR org.apache.jena.query.text.cmd.shacltextindexer --desc=config.ttl
java -jar $JAR --port 3040 --config config.ttl &
python3 serve_app.py --port 8070 --directory app --backend http://localhost:3040
```

App on <http://localhost:8070>, Fuseki on :3040. `task query` runs every file in
`queries/` from the shell; the app's **Feature tests** tab runs the same files in the browser.

**Load and index with the server stopped.** The review children come from a CSV via
`idx:externalSource`, which makes the shape rebuild-only: a live graph update cannot
re-derive CSV rows, so the change listener refuses to touch those documents rather than
silently dropping them. Only the bulk indexer builds them, and it needs exclusive TDB2
access.

## What each feature looks like, and where

| # | Feature | Config | Query | In the app |
|---|---|---|---|---|
| 1 | Faceting | `idx:facetable` | `02`, `03` | left panel, counts update per filter |
| 2 | Date range slicing | `idx:TemporalField` | `06` | "Published" from/to |
| 3a | TEXT / BM25 | `idx:TextField`, no analyzer | `01` | Keyword mode |
| 3b | KEYWORD on IRIs | `idx:KeywordField` | `10` | every facet checkbox |
| 3c | Edge n-gram | `text:EdgeNGramAnalyzer` + `text:tokenized` | `08` | Code typeahead mode |
| 3d | INT / range facets | `idx:IntField` | `07` | "Prep time" buckets |
| 4 | Hierarchical faceting | `idx:facetHierarchy` (root **and** nested) | `04`, `05` | Region › country, Reviewer › stars |
| 5 | One field, many paths | two `sh:property` occurrences | `09` | "People (author or tester)" |
| 6 | Match projection | `idx:stored` | `11`, `12` | matched reviews on each card |
| 7 | External CSV | `idx:externalSource` | `12`, `13` | Reviews panel |
| 8 | Vector search | `idx:VectorField` | `15`, `16` | Semantic mode |

Two extras fall out of the above: **sort pushdown and paging** (`14`, the Sort dropdown),
and the **config endpoint** — the badge top-right is `GET /$/config/effective`, reporting
the index-shape fingerprint status and field count.

## Vector search: what actually works

**The plumbing works. The only real embedding engine does not.**

What is verified working: naming a vector field in `fieldSpec` switches `luc:query` to
KNN with no other change to the call; the `cqlFilter` is pushed *into* the HNSW traversal
rather than applied to its result; facets and `?totalHits` come back top-k scoped. Query
`16` shows a filtered similarity search returning only main courses.

What is not: the `jlama` provider produces vectors that are **not the model's output**.

Measured against a reference BERT forward pass written in numpy, reading the same
`model.safetensors` and the same token ids that Jlama's own (correct) tokenizer produces:

| Comparison | Reference | Jlama 0.8.4 |
|---|---|---|
| beef stew ↔ "a slow-cooked meat dish in wine" | 0.65 | 0.77 |
| beef stew ↔ "quantum chromodynamics lattice gauge theory" | **0.29** | **0.71** |
| tiramisu ↔ "quantum chromodynamics lattice gauge theory" | 0.44 | 0.78 |
| **Jlama's vector vs the reference vector, same text** | 1.00 by definition | **−0.06** |

That last row is the finding. Under the provider's default `MODEL` pooling, Jlama's
embedding is orthogonal to the correct one — cosine −0.06 where a working implementation
gives ~0.99. The best of its four pooling modes reaches 0.27. Every recipe ends up closer
to a particle-physics phrase than to another recipe, so ranking is noise.

Ruled out: it is not our configuration, and not the model files. The tokenizer emits
correct WordPiece ids with `[CLS]`/`[SEP]`; `F32`/`F32` and `F32`/`I8` working dtypes give
bit-identical results; all four `PoolingType` values are wrong; and the same weights driven
by the numpy reference behave sensibly. 0.8.4 is the latest Jlama release.

Nothing errors. The model loads, reports 384 dimensions, embeds without complaint, and
returns confident garbage — the exact failure mode `docs/03-configuration.md` warns about
for model mismatch, arriving instead through the engine.

So this demo ships with the `hashing` provider, which works offline and compares **words,
not meaning**. Semantic mode therefore demonstrates the query shape and the filtered-KNN
behaviour, not retrieval quality. The dataset is nonetheless written for a real model —
summaries deliberately avoid the words a searcher would type, so "something warm for a
cold night" shares no vocabulary with the beef stew. Point a working engine at it and that
query should return the stew and the ramen.

The design note already flags ONNX Runtime / LangChain4j as the fallback engine. That now
looks necessary rather than optional. `jena-text` only knows the `EmbeddingProvider`
interface and a `ServiceLoader` name, so a second engine is a new module and a config
string — no change to index or query code.

## Two behaviours worth knowing

**Hierarchical facets are addressed by dimension name, not field IRI.** Everything else in
the public API uses field IRIs; a hierarchy uses `"region_country"`, derived by joining the
levels' `idx:fieldName` values with `_`. Faceting on `field:region` instead silently gives
you region's *flat* counts — which look identical at the top level and stop being
equivalent the moment you drill in. The app reads the real names from
`/$/config/effective` rather than hardcoding them.

**A child-scoped field has no entity-level flat facet.** `field:reviewer` is declared
`idx:facetable true` and the index log lists it as facetable, but `luc:facet` on it returns
zero rows, and `"*"` does not expand to it. This is documented (`03-configuration.md`,
Pattern 4) and it is why the reviews panel uses a nested `idx:facetHierarchy` — which is
the better demo anyway, since its levels correlate per review row.

## The data

`data/kitchen.ttl` — 10 recipes, 7 countries in 3 regions, 3 courses, 3 diets, 4 people.
Each recipe has exactly one country, so the root hierarchy has no cartesian product to get
wrong. Region is never stated on a recipe; it is reached by the sequence path
`( kt:country kt:inRegion )`.

`data/reviews.csv` — 25 review rows keyed by recipe IRI, **never loaded into the graph**.
The browser does not read this file and there is no DuckDB involved: the bulk indexer reads
it once and writes each row as a child document inside the recipe's Lucene block. Values
come back out through `luc:nestedMatch`.

The rows are arranged so same-child correlation is falsifiable. Tonkotsu Ramen has a Priya
review *and* a five-star review, but no five-star Priya review, so "Priya AND 5 stars" must
not return it. Expect exactly Slow-Braised Beef Shin, Cacio e Pepe and Miso Soup — that is
query `13`, and it is what the Reviews panel produces when you pick Priya then ★★★★★.

## The label cache

Labels are never joined into the search query. Each IRI is resolved by its own `GET`
through `app/labels.js`, so every IRI is its own HTTP cache key and a returning view costs
no network transfer. `serve_app.py` rewrites Fuseki's `no-store` header on those requests
to make that legal, and bounds what it will proxy under `/$/` — Fuseki's default
`LocalhostFilter` compares the *socket* address, which a proxy would otherwise launder.

Carried over from `../label-cdn`; the design point is the browser's own HTTP cache rather
than an in-page `Map`. Language negotiation is deliberately not carried over — the demo
data is untagged.

## Files

```
config.ttl        server, index, fields, one shape — read top to bottom
data/kitchen.ttl  10 recipes and the vocabulary
data/reviews.csv  external children, never in the graph
queries/*.rq      one file per feature; also the app's test panel
app/              index.html, app.js, app.css, labels.js
serve_app.py      static files + a Fuseki proxy on one origin
```
