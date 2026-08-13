# demo-mini

Ten made-up recipes, one SHACL shape, and every feature of this fork's entity-per-document
index demonstrated exactly once. Small enough that you can check any facet count by eye.

It is deliberately separate from `demo/`, which has grown to three data files, 25 queries
and a 3,000-line app. Nothing here is shared with it.

```bash
task build     # once — builds the Fuseki server jar
task up        # load, index, serve, and open the app on :8070
```

Without `go-task`, the same steps are:

```bash
# from the repo root — the server jar, plus the optional embeddings module and its classpath
mvn install -pl jena-fuseki2/jena-fuseki-server -am -DskipTests -Drat.skip
mvn -q -pl jena-text-embeddings -am -Pdev -DskipTests -Drat.skip install
mvn -q -pl jena-text-embeddings dependency:build-classpath \
    -Dmdep.outputFile=$(pwd)/demo-mini/.embeddings-cp

cd demo-mini
JAR=../jena-fuseki2/jena-fuseki-server/target/jena-fuseki-server-6.2.0-SNAPSHOT.jar
CP="$JAR:$(cat .embeddings-cp):../jena-text-embeddings/target/classes"

rm -rf DB Lucene Taxonomy
java -cp $JAR tdb2.tdbloader --loc=DB data/kitchen.ttl
java -cp "$CP" org.apache.jena.query.text.cmd.shacltextindexer --desc=config.ttl
java -cp "$CP" org.apache.jena.fuseki.main.cmds.FusekiServerCmd --port 3040 --config config.ttl &
python3 serve_app.py --port 8070 --directory app --backend http://localhost:3040
```

`jena-text-embeddings` is deliberately **not** a dependency of the Fuseki server jar — an ML
runtime should not ship by default with the text index — so both the indexer and the server
assemble a classpath instead of running the shaded jar. Unlike the Jlama path, no
`--add-modules jdk.incubator.vector` is needed: ONNX Runtime does not use the Vector API.

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
| 2 | Date range slicing | `idx:TemporalField` | `06`, `07` | "Published" histogram slider |
| 3a | TEXT / BM25 | `idx:TextField`, no analyzer | `01` | Keyword mode |
| 3b | KEYWORD on IRIs | `idx:KeywordField` | `10` | every facet checkbox |
| 3c | Edge n-gram | `text:EdgeNGramAnalyzer` + `text:tokenized` | `08` | Code typeahead mode |
| 3d | INT / range facets | `idx:IntField` | `07` | "Prep time" buckets |
| 4 | Hierarchical faceting | `idx:facetHierarchy` (root **and** nested) | `04`, `05`, `17` | Region › country, Reviewer › stars |
| 5 | One field, many paths | two `sh:property` occurrences | `09` | "People (author or tester)" |
| 6 | Match projection | `idx:stored` | `11`, `12` | Index view panel, and matched reviews on each card |
| 7 | External CSV | `idx:externalSource` | `12`, `13`, `17` | Reviews panel |
| 8 | Vector search | `idx:VectorField` | `15`, `16` | Semantic mode |

## The date histogram

"Published" is a two-handle slider over a bar per year, and the bars are a **range facet on
the `idx:TemporalField`** — the same `luc:facet` call as the "Prep time" buckets, with date
boundaries instead of integers:

```json
[{"field":"urn:jena:lucene:field#publishedOn",
  "ranges":["2019-01-01","2020-01-01","2021-01-01","2022-01-01","2023-01-01","2024-01-01","2025-01-01"]}]
```

The point is that you can see where the data is before choosing a window; a pair of date
inputs lets you pick an empty range and gives no clue why it was empty. Dragging compiles to
one `between` on the same field that query `06` uses by hand.

Three details that matter:

- **The bars are counted with the date filter excluded**, so dragging never flattens the
  thing you are dragging over. Same rule as the hierarchy levels.
- **They do still reflect every other filter.** Search for "chilli" and the histogram drops
  to 2020 and 2022, which is where those two recipes are — so it answers "when is what I am
  looking at from", not just "when is everything from".
- **The axis is the data's**, discovered at startup from `MIN`/`MAX` of `kt:publishedOn`
  rather than hardcoded, and a year with no data keeps its slot as a sliver so a gap reads
  as a gap.

## Opening a facet is not the same as filtering by it

Each drillable value has a **twisty** beside its checkbox, and the two are independent:
the twisty shows what is inside a value, the checkbox narrows the results by it. Opening
"Asia" leaves all ten recipes on screen.

This needs no client-side trickery, because `luc:facet` takes its **own** `cqlFilter`,
separate from the one `luc:query` gets. Asking for Asia's countries is an `=` on
`field:region` sent only to the facet call:

```sparql
# facet call — "=" on region, so the dimension returns Asia's CHILDREN
(?f ?value ?lo ?hi ?count) luc:facet ("default" "default" "*" '["region_country"]'
    '{"op":"=","args":[{"property":"urn:jena:lucene:field#region"},"...region-asia"]}' 50 0)

# results call — no region clause at all, so nothing is narrowed
(?hit ?entity) luc:query ("default" "default" "*" "" "" 10 0)
```

Ticking a value adds the clause to *both*. One open node costs one extra facet request.

## Everything on screen is a label, never a CURIE

The chips, the facet lists, the badges and the "matched on" line all show `rdfs:label`,
resolved through the browser-cached label endpoint one IRI at a time. That includes the
**index fields themselves**: `data/kitchen.ttl` gives `field:region` and friends an
`rdfs:label`, so a chip reads "COUNTRY France" through exactly the same mechanism as the
value beside it, rather than by title-casing `idx:fieldName` in JavaScript. Those triples
describe the index vocabulary rather than a recipe, and no shape targets them, so they add
nothing to the index.

Two views are deliberately exempt, because their whole point is to show what the server
actually returned: the **Feature tests** table and the **Turtle** panels. Turtle without
CURIEs would not be Turtle.

## Why a result matched

Each card carries a **Matched on** line naming the indexed field that produced the hit,
from `luc:match` — `Summary` when the text query hit the summary, and the value itself on
hover. Three cases are worth knowing, and all three appear in the demo:

| Line | Means |
|---|---|
| `Summary`, `Title`… | the text query matched that field |
| `nearest neighbour · cosine 0.820` | a vector hit; `luc:match` projects nothing because proximity is the whole explanation |
| `filter only — no text match` | the CQL filter selected it and the query string was a match-all |

## The Index view panel

The right-hand column is a `CONSTRUCT` over the same search, fetched as `text/turtle` and
shown verbatim. It answers "what did the index actually hit on":

```turtle
kt:recipe-r06  rdfs:label     "Tiramisu" ;
        field:summary  "Savoiardi soaked in espresso, layered with mascarpone and dusted with cocoa." ;
        luc:rank       0 ;
        luc:score      "1.0000615"^^xsd:float .
```

Field IRIs are used **as predicates**, which is what makes it readable: `field:summary` is
the indexed field `luc:match` says matched, and the object is the stored value it matched
on. A filter that selected CSV children adds them as a nested record, so you see the row
itself:

```turtle
kt:recipe-r10  rdfs:label  "Miso Soup" ;
        luc:matchedRecord  [ field:reviewMonth "2025-11" ; field:reviewer "Priya" ; field:stars 5 ] ;
        luc:rank 2 .
```

Two things it makes visible that are otherwise invisible:

- **A vector hit has no `field:` predicate at all.** `luc:match` has nothing to project,
  because a KNN hit is near the query in embedding space rather than matching a term. The
  panel says so explicitly in Semantic mode.
- **`luc:rank` has to be carried in the payload.** A graph is unordered, so the ranking
  cannot survive as row order — which is precisely why `?rank` exists (see
  [02-sparql-api.md](../docs/02-sparql-api.md#use-rank-for-order-not-score)).

Its `luc:query` arguments are identical to the results query, so it shares one Lucene
execution with the results and the facets rather than searching again. It is hidden below
1180px of viewport width.

## The record beside each card

Each result also carries a column showing **the record itself, as it sits in the graph** —
its own triples and nothing else:

```turtle
kt:recipe-r06  rdf:type      kt:Recipe ;
        rdfs:label           "Tiramisu" ;
        kt:author            kt:person-bruno ;
        kt:code              "RCP-2021-0154" ;
        kt:country           kt:country-italy ;
        kt:course            kt:course-dessert ;
        kt:prepMinutes       30 ;
        kt:publishedOn       "2021-09-30"^^xsd:date ;
        dcterms:description  "Savoiardi soaked in espresso, …" .
```

This is a separate `CONSTRUCT { ?entity ?p ?o }` over the same hits — the RDF the index was
built **from**, as against the index view in the right-hand panel. Both are one request for
the whole page, split on their subject blocks client-side, which is reliable because Jena
writes each subject at column 0 with its predicates indented.

Putting the two side by side is the clearest thing in the demo:

| | Card column | Right-hand panel |
|---|---|---|
| What it is | the record in the graph | what Lucene holds and matched |
| `kt:author`, `kt:course`, … | ✅ | ✗ (not what the index stores) |
| `luc:rank`, `luc:score` | ✗ | ✅ |
| `field:summary "…"` — the field that matched | ✗ | ✅ |
| The review rows | **✗ — they are not in the graph at all** | ✅ as `luc:matchedRecord` |

That last row is the whole external-CSV story in one line. The reviews exist only in the
index, so they can be filtered, correlated and counted, while the graph stays the record of
what a recipe *is*.

Two extras fall out of the above: **sort pushdown and paging** (`14`, the Sort dropdown),
and the **config endpoint** — the badge top-right is `GET /$/config/effective`, reporting
the index-shape fingerprint status and field count.

## Vector search: what actually works

Semantic search runs on a real model, through the **`onnx`** provider added for this.
Try these in the app's Semantic mode — none shares a word with the recipe it finds:

| Query | Returns | Because the recipe says |
|---|---|---|
| a coffee flavoured Italian pudding | Tiramisu | espresso, mascarpone, savoiardi |
| a savoury broth with bean curd | Miso Soup | dashi, fermented soybean paste, tofu |
| meat cooked very slowly in wine until tender | Slow-Braised Beef Shin | braised four hours in red wine |
| pasta with a sharp cheese and lots of pepper | Cacio e Pepe | spaghetti, pecorino, black pepper |
| raw seafood marinated in citrus | Ceviche | raw white fish cured in lime juice |

The same strings in Keyword mode do not find those recipes at all. That contrast is the
demo.

### Two honest limits

**There is no relevance cutoff.** A KNN search returns the k nearest neighbours, full stop
— so with ten recipes and `idx:knnTopK` at its default of 100, *everything* comes back on
every query, ranked. `?totalHits` is "neighbours returned, bounded by k", not a corpus
count. Set `idx:knnTopK` low (3, say) and reindex if you want to watch the cut-off bite;
the top-k-scoped facet counts shrink with it.

**It matches topic, not inference.** These queries work because they paraphrase what the
text says. Queries needing a leap — "something warm and comforting for a cold night", which
requires knowing braised beef is comfort food — do *not* reliably return the stew. That is
the model, not the plumbing: an independent numpy forward pass over the same weights ranks
it the same way. Small sentence-embedding models are paraphrase engines.

### The jlama provider is broken — do not use it

It loads `BAAI/bge-small-en-v1.5` without error and returns 384-dimensional vectors that
are **not the model's output**. Measured against a reference BERT forward pass written in
numpy, reading the same `model.safetensors` and the same token ids from Jlama's own
(correct) tokenizer:

| Comparison | Reference | ONNX provider | Jlama 0.8.4 |
|---|---|---|---|
| beef stew vs "a slow-cooked meat dish in wine" | 0.6534 | 0.6534 | 0.77 |
| beef stew vs "quantum chromodynamics lattice gauge theory" | **0.2919** | **0.2919** | **0.71** |
| tiramisu vs "quantum chromodynamics lattice gauge theory" | 0.4367 | 0.4367 | 0.78 |
| vector vs the reference vector, same text | 1.00 | ~1.00 | **-0.06** |

The last row is the finding: under Jlama's default `MODEL` pooling the embedding is
orthogonal to the correct one, and the best of its four pooling modes reaches 0.27. Every
recipe ends up closer to a particle-physics phrase than to another recipe.

Ruled out: not our configuration, not the model files. The tokenizer emits correct
WordPiece ids with `[CLS]`/`[SEP]`; `F32`/`F32` and `F32`/`I8` working dtypes are
bit-identical; all four `PoolingType` values are wrong; the same weights behave correctly
under both the numpy reference and ONNX Runtime. 0.8.4 is the latest release. Nothing
throws — it is the "confident garbage" failure `docs/03-configuration.md` warns about for a
model mismatch, arriving through the engine instead.

`TestOnnxEmbeddingProvider.embeddingsMatchTheReferenceForwardPass` pins the numbers above.

### How the onnx provider is put together

ONNX Runtime does the forward pass; **Jlama's `BertTokenizer` still does the tokenisation**,
because it was verified correct and is pure Java, so reusing it avoids a second native
dependency. Pooling is per-model and silently wrong if guessed — BGE pools `[CLS]`, E5 and
the MiniLM family average — so it is inferred from the model name and overridable with the
`pooling` option, as are the query/document prefixes BGE and E5 require.

It also removes the constraint that motivated the design note's fallback: Jlama loads only
encoder-only BERT, whereas anything with an ONNX export now works. `idx:dimension` must
match what the model produces — 384 here — and Lucene fixes it at index time, so changing
model means a reindex.

Model files land in `./models` on first run (~130MB from HuggingFace) and are reused after
that. For a fully offline demo, switch `config.ttl` to the `hashing` provider and set
`idx:dimension 64` on `field:embedding`; it compares words rather than meaning, and
demonstrates the plumbing only.

## Two behaviours worth knowing

**Hierarchical facets are addressed by dimension name, not field IRI.** Everything else in
the public API uses field IRIs; a hierarchy uses `"region_country"`, derived by joining the
levels' `idx:fieldName` values with `_`. Faceting on `field:region` instead silently gives
you region's *flat* counts — which look identical at the top level and stop being
equivalent the moment you drill in. The app reads the real names from
`/$/config/effective` rather than hardcoding them.

**A hierarchy level must not be counted under its own filter.** The server returns one
level at a time — no drill filter means the top level, an `=` on a level means that level's
children — so a two-level tree is two requests, each omitting the filter belonging to the
level it is asking about. Without that, choosing "Europe" removes Europe and its siblings
from the very list you chose it from, and choosing "France" empties the list entirely,
because the drill path is then complete and the server is being asked for a third level
that does not exist. Both levels stay on screen with the chosen values ticked, and sibling
counts stay correct because they are computed under the *other* active filters. Solr and
Elasticsearch spell the same idea as excluding a tagged filter.

**A nested hierarchy's levels are correlated per child record, not cross-produced.** This
is the guarantee that makes drilling into `Reviewer › stars` mean anything, and query `17`
is built to falsify it. Tonkotsu Ramen has exactly two reviews — Noor gave 5, Priya gave 2
— and drilling each reviewer on that one recipe returns exactly the rating they gave:

```
?reviewer  ?stars  ?recipes
"Noor"     "5"     1
"Priya"    "2"     1
```

Cross-produced levels would offer *both* ratings under *both* reviewers, promising a
five-star Priya review that is not in the data and letting the children out-count the
parent. Declaring `reviewer` and `stars` as **root** occurrences rather than inside the
`idx:nested` block would do exactly that — see `03-configuration.md`, Pattern 4. The same
guarantee on the filtering side is query `13`.

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
