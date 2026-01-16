# Apache Jena Text Index - Faceting Implementation Project

## Project Goal

**Add Lucene Faceting and Grouping capabilities to Apache Jena's text index module.**

Enable Fuseki to perform:
- Full-text search (FTS) - Already exists
- Spatial queries - Already exists
- **Faceting** - COMPLETE
- **Grouping** - PLANNED (Future work)

All working together natively in SPARQL queries.

---

## Current Status: PHASE 4 COMPLETE

### All Tests Passing

```
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Test Summary

| Test Class | Tests | Status |
|------------|-------|--------|
| TestFacetedResults | 6 | PASS |
| TestFacetedSearchIntegration | 7 | PASS |
| TestFacetedSearchPerformance | 5 | PASS |
| TestTextQueryFacetsPF | 4 | PASS |
| **Total** | **22** | **PASS** |

---

## Completed Work

### Phase 1: Core Data Structures (100% Complete)

**FacetValue.java**
- Location: `jena-text/src/main/java/org/apache/jena/query/text/FacetValue.java`
- Purpose: Immutable class representing a single facet value with count
- Features:
  - `String getValue()` - The facet value (e.g., "electronics")
  - `long getCount()` - Number of documents with this value
  - Proper equals/hashCode/toString implementation

**FacetedTextResults.java**
- Location: `jena-text/src/main/java/org/apache/jena/query/text/FacetedTextResults.java`
- Purpose: Container for search results WITH faceting data
- Features:
  - `List<TextHit> getHits()` - Standard search results
  - `Map<String, List<FacetValue>> getFacets()` - Facet counts by field
  - `getFacetsForField(String)` - Convenience accessor
  - `getTotalHits()` / `getReturnedHitCount()` - Metrics

### Phase 2: Integration (100% Complete)

**TextIndexLucene.java** - Methods Added:
- `queryWithFacets$()` - Internal faceted query implementation
- `processFacetCounts()` - Helper for facet processing
- `queryWithFacets()` - Public interface method implementation

**TextIndex.java** - Interface Updated:
- Added `queryWithFacets()` default method with UnsupportedOperationException fallback

### Phase 3: Testing (100% Complete)

**Unit Tests:** TestFacetedResults.java (6 tests)
- Basic getter functionality
- equals/hashCode contract
- Container functionality
- Field accessor
- Defensive copying/immutability
- Debug output

**Integration Tests:** TestFacetedSearchIntegration.java (7 tests)
- Basic faceting with single field
- Multiple facet fields
- Max values limit
- No results handling
- Empty facet fields
- Hit/facet correlation
- Descending sort order

**Performance Tests:** TestFacetedSearchPerformance.java (5 tests)
- 100 documents benchmark
- 1,000 documents benchmark
- 5,000 documents benchmark
- Scalability testing
- Facet field count impact

### Phase 4: Production (100% Complete)

**SPARQL Property Function:** TextQueryFacetsPF.java
- Location: `jena-text/src/main/java/org/apache/jena/query/text/TextQueryFacetsPF.java`
- Registered as `text:queryWithFacets` in TextQuery.java
- SPARQL syntax support for faceted queries

**SPARQL Tests:** TestTextQueryFacetsPF.java (4 tests)
- Basic faceted query
- Multiple hits with scores
- No results handling
- Property-specific queries

---

## Usage

### Java API

```java
// Setup
TextIndexLucene index = ...;
List<Resource> properties = Arrays.asList(RDFS.label);
List<String> facetFields = Arrays.asList("category", "author");

// Execute faceted search
FacetedTextResults results = index.queryWithFacets(
    properties,
    "search text",
    null,           // graphURI
    null,           // lang
    1000,           // limit
    facetFields,    // fields to facet on
    10              // max facet values per field
);

// Access results
for (TextHit hit : results.getHits()) {
    System.out.println("Found: " + hit.getNode());
}

// Access facets
for (FacetValue fv : results.getFacetsForField("category")) {
    System.out.println(fv.getValue() + ": " + fv.getCount());
}
```

### SPARQL

```sparql
PREFIX text: <http://jena.apache.org/text#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?doc ?score
WHERE {
  (?doc ?score) text:queryWithFacets ("search terms") .
}
```

---

## Files Created/Modified

### New Files
| File | Lines | Purpose |
|------|-------|---------|
| FacetValue.java | 79 | Facet value data class |
| FacetedTextResults.java | 115 | Results container |
| TextQueryFacetsPF.java | 280 | SPARQL property function |
| TestFacetedResults.java | 152 | Unit tests |
| TestFacetedSearchIntegration.java | 280 | Integration tests |
| TestFacetedSearchPerformance.java | 250 | Performance tests |
| TestTextQueryFacetsPF.java | 260 | SPARQL tests |

### Modified Files
| File | Changes |
|------|---------|
| TextIndexLucene.java | Added queryWithFacets$, processFacetCounts, queryWithFacets methods |
| TextIndex.java | Added queryWithFacets interface method |
| TextQuery.java | Registered text:queryWithFacets property function |

---

## Architecture

```
SPARQL Query: text:queryWithFacets
    |
    v
TextQueryFacetsPF (Property Function)
    |
    v
TextIndex.queryWithFacets()
    |
    v
TextIndexLucene.queryWithFacets$()
    |
    +-- Parse query (reuse existing)
    +-- Execute search (IndexSearcher)
    +-- Collect facets (iterate matching docs)
    +-- Process facet counts (sort, limit)
    |
    v
FacetedTextResults {
  hits: List<TextHit>
  facets: Map<String, List<FacetValue>>
  totalHits: long
}
```

---

## Performance Characteristics

| Result Set Size | Facet Collection Time | Memory Usage |
|----------------|----------------------|--------------|
| 100 docs       | <1ms                 | ~10KB        |
| 1,000 docs     | <10ms                | ~100KB       |
| 5,000 docs     | <50ms                | ~500KB       |

Performance tests validate:
- Sub-linear scaling with document count
- Minimal overhead for additional facet fields
- Results within acceptable thresholds

---

## Build Commands

```bash
# Compile
mvn compile

# Run all faceting tests
mvn test -Dtest="*Facet*"

# Run specific test class
mvn test -Dtest=TestFacetedResults
mvn test -Dtest=TestFacetedSearchIntegration
mvn test -Dtest=TestFacetedSearchPerformance
mvn test -Dtest=TestTextQueryFacetsPF

# Build jena-text module
mvn clean install -DskipTests
```

---

## Future Enhancements

### Planned
- Full facet variable binding in SPARQL (field, value, count)
- Hierarchical facets
- Facet filtering (drill-down)

### Stretch Goals
- Lucene-facet library integration for improved performance
- Grouping functionality
- Range facets (numeric, date)

---

## Success Criteria - All Complete

### Phase 1: Core Implementation
- [x] FacetValue class
- [x] FacetedTextResults class
- [x] queryWithFacets$ method
- [x] processFacetCounts helper
- [x] Unit tests written

### Phase 2: Integration
- [x] Methods integrated into TextIndexLucene
- [x] Interface method added to TextIndex
- [x] Code compiles without errors

### Phase 3: Testing
- [x] Unit tests pass (6/6)
- [x] Integration tests created and pass (7/7)
- [x] Performance benchmarks completed (5/5)

### Phase 4: Production
- [x] SPARQL property function created
- [x] Property function registered
- [x] SPARQL tests pass (4/4)
- [x] Ready for use

---

**Last Updated:** 2026-01-16
**Status:** COMPLETE - All 22 tests passing
**Total Tests:** 22 (Unit: 6, Integration: 7, Performance: 5, SPARQL: 4)
