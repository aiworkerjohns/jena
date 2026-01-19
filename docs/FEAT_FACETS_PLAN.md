# Faceting Feature - Implementation Plan and Change Log

This document tracks changes made to the faceting implementation in Apache Jena's text index module.

---

## Version History

### 2026-01-19 - Filtered Facets Support

**Status:** Complete

#### Changes Implemented

1. **Filtered Facets in SPARQL (`text:facetCounts`)**
   - Added support for optional search query parameter to filter facet counts
   - Syntax: `(?field ?value ?count) text:facetCounts ("search query" "field1" 10)`
   - Query detection: First argument is treated as search query if NOT a configured facet field and NOT a number

2. **URI-Based Join for Triples Model Compatibility**
   - Modified `TextIndexLucene.getFacetCounts()` to handle triples-based indexing
   - When filtering by query, extracts matched entity URIs and joins with facet documents
   - Ensures facet counts are correctly computed even when text and facet fields are in separate documents

3. **Test Suite Updates**
   - Added `testFilteredFacetCountsMultiWord` test for multi-word query filtering
   - Added `testFilteredFacetCountsSingleWord` test for single-word query filtering
   - Updated test isolation using temp directories
   - Total tests: 36 (was 34)

4. **Documentation Updates**
   - Updated FEAT_FACETS_SPEC.md with filtered facets syntax and examples
   - Updated FEAT_FACETS_TESTING.md with filtered facets test cases
   - Updated FEAT_FACETS_OUTPUT.md with new test results

#### Files Modified

| File | Change |
|------|--------|
| `TextFacetCountsPF.java` | Updated query detection to use configured facet fields list |
| `TextIndexLucene.java` | Implemented URI-based join for filtered facet counting |
| `TestTextFacetCountsPF.java` | Added filtered facets tests, improved test isolation |
| `docs/FEAT_FACETS_SPEC.md` | Documented filtered facets syntax |
| `docs/FEAT_FACETS_TESTING.md` | Added filtered facets test instructions |
| `docs/FEAT_FACETS_OUTPUT.md` | Updated test output with filtered facets results |

#### Technical Details

**Query Detection Logic:**
```java
// First argument detection: if not a configured facet field and not a number, treat as query
if (!list.isEmpty() && list.get(0).isLiteral()) {
    String firstArg = list.get(0).getLiteralLexicalForm();
    if (!isNumber && !configuredFacetFields.contains(firstArg)) {
        queryString = firstArg;
        idx++;
    }
}
```

**URI Join for Filtered Facets:**
```java
// 1. Execute search to find matching entity URIs
TopDocs topDocs = searcher.search(query, 10000);

// 2. Extract unique URIs from matched documents
Set<String> matchedUris = new HashSet<>();
for (ScoreDoc sd : topDocs.scoreDocs) {
    Document doc = storedFields.document(sd.doc);
    String uri = doc.get(entityField);
    if (uri != null) matchedUris.add(uri);
}

// 3. Build query matching all documents for those URIs (includes facet docs)
BooleanQuery.Builder uriQueryBuilder = new BooleanQuery.Builder();
for (String uri : matchedUris) {
    uriQueryBuilder.add(new TermQuery(new Term(entityField, uri)), BooleanClause.Occur.SHOULD);
}

// 4. Collect facets from all matching documents
FacetsCollector fc = new FacetsCollector();
searcher.search(uriQuery, fc);
facets = new SortedSetDocValuesFacetCounts(state, fc);
```

---

### 2026-01-17 - Initial Native Faceting Implementation

**Status:** Complete

#### Changes Implemented

1. **Native Lucene Faceting**
   - Implemented SortedSetDocValuesFacetCounts for O(1) facet counting
   - Added `text:facetFields` configuration property
   - Added FacetValue and FacetedTextResults classes

2. **SPARQL Property Functions**
   - `text:facetCounts` - Native facet counting (open facets only at this stage)
   - `text:queryWithFacets` - Text search with facet data

3. **Java API**
   - `getFacetCounts(List<String> fields, int max)` - Open facets
   - `getFacetCounts(String query, List<String> fields, int max)` - Filtered facets
   - `isFacetingEnabled()` - Check if faceting is configured

4. **Test Suite**
   - TestFacetedResults - 6 tests
   - TestFacetedSearchIntegration - 7 tests
   - TestFacetedSearchPerformance - 5 tests
   - TestTextQueryFacetsPF - 4 tests
   - TestNativeFacetCounts - 8 tests
   - TestTextFacetCountsPF - 4 tests

---

## Planned Enhancements

### Short-term

- [x] Add filtered facets to `text:facetCounts` SPARQL syntax
- [x] Document filtered facets in specification

### Medium-term

- [ ] Support comma-delimited field syntax in configuration for GraphDB alignment
- [ ] Consider per-field facet configuration (`text:facet true` on field definitions)
- [ ] Add facet caching similar to query caching

### Long-term

- [ ] Hierarchical facets for taxonomy use cases
- [ ] Range facets for numeric/date fields
- [ ] Performance testing with 100K+ documents

---

## Notes

### Triples-Based Indexing Model

The jena-text module uses a triples-based indexing model by default (TextDocProducerTriples), where each triple creates a separate Lucene document. This means:

- `ex:doc1 rdfs:label "Hello"` creates one document with the label field
- `ex:doc1 ex:category "tech"` creates another document with the category field

For filtered facets to work correctly, we need to join search results by entity URI with facet documents. This is handled automatically in `getFacetCounts()`.

### Query Syntax

The default Lucene query parser uses OR for multiple words:
- `"machine learning"` matches documents with "machine" OR "learning"
- `"machine AND learning"` matches documents with both words
- `"\"machine learning\""` matches the exact phrase

---

**Last Updated:** 2026-01-19
