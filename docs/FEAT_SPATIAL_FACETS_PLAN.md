# Spatial + Facets Implementation Plan

This document tracks the implementation plan for combined spatial search and faceting in jena-text.

---

## Phase 1: Configuration & Infrastructure

### 1.1 Extend TextIndexConfig
- [ ] Add `geoFields` - List of geo field names
- [ ] Add `geoFormat` - WKT format specifier
- [ ] Add `storeCoordinates` - Store lat/lon for retrieval
- [ ] Add `docProducerMode` - "entity" or "triple"

### 1.2 Extend TextVocab
- [ ] Add `pGeoFields` vocabulary term
- [ ] Add `pGeoFormat` vocabulary term
- [ ] Add `pStoreCoordinates` vocabulary term
- [ ] Add `pDocProducerMode` vocabulary term

### 1.3 Update TextIndexLuceneAssembler
- [ ] Parse `text:geoFields` RDF list
- [ ] Parse `text:geoFormat` literal
- [ ] Parse `text:storeCoordinates` boolean
- [ ] Parse `text:docProducerMode` literal

### 1.4 Add Dependencies
- [ ] Add `lucene-spatial3d` to jena-text pom.xml (for LatLonPoint, LatLonShape)

---

## Phase 2: WKT Parsing

### 2.1 Create WKTParser Utility
- [ ] Parse WKT POINT to lat/lon doubles
- [ ] Parse WKT POLYGON to Lucene Polygon
- [ ] Validate coordinate bounds
- [ ] Handle coordinate order (lon lat)

---

## Phase 3: Geo Indexing

### 3.1 Update TextIndexLucene.doc()
- [ ] Detect geo fields from config
- [ ] Parse WKT value
- [ ] Add LatLonPoint for point queries (bbox, distance)
- [ ] Add LatLonShape for polygon queries (intersects, within)
- [ ] Add StoredField for lat/lon retrieval
- [ ] Add SortedSetDocValuesFacetField (existing facet support)

### 3.2 Entity-Mode Document Producer
- [ ] Complete TextDocProducerEntities implementation
- [ ] Aggregate all fields for entity into single doc
- [ ] Wire via assembler `text:docProducerMode`
- [ ] Support both entity and triple modes

---

## Phase 4: Query Execution

### 4.1 Create GeoSearchParams
- [ ] Builder pattern for query parameters
- [ ] Support textQuery, bbox, distance, polygon, facetFields
- [ ] Validation methods

### 4.2 Create GeoTextHit
- [ ] Extend TextHit with lat/lon fields
- [ ] Factory method from Lucene document

### 4.3 Create GeoFacetedResults
- [ ] Container for hits + facets + total count
- [ ] Methods: getHits(), getFacets(), getTotalHits()

### 4.4 Implement searchWithGeoAndFacets()
- [ ] Build text query from query string
- [ ] Build geo query (bbox, distance, or polygon)
- [ ] Combine with BooleanQuery (MUST text, FILTER geo)
- [ ] Execute with FacetsCollector
- [ ] Extract hits with coordinates
- [ ] Compute facet counts

---

## Phase 5: SPARQL Property Function

### 5.1 Create TextSearchPF
- [ ] Parse subject variables (doc, score, lat, lon, facetField, facetValue, facetCount)
- [ ] Parse object list (query, geo operator, params, facet fields)
- [ ] Call TextIndexLucene.searchWithGeoAndFacets()
- [ ] Return QueryIterator with bindings

### 5.2 Register Property Function
- [ ] Add to TextQuery.init()
- [ ] Register as `text:search`

---

## Phase 6: Testing

### 6.1 Unit Tests
- [ ] TestWKTParser - WKT parsing
- [ ] TestGeoIndexing - Geo field indexing
- [ ] TestGeoSearch - BBox, distance, polygon queries
- [ ] TestGeoFacets - Combined geo + facets

### 6.2 Integration Tests
- [ ] TestTextSearchPF - SPARQL property function
- [ ] TestEntityMode - Entity-mode document producer

### 6.3 Performance Tests
- [ ] TestGeoSearchPerformance - Large dataset benchmarks

---

## Phase 7: Documentation

### 7.1 Feature Docs
- [x] FEAT_SPATIAL_FACETS_SPEC.md - Specification
- [x] FEAT_SPATIAL_FACETS_PLAN.md - This file
- [ ] FEAT_SPATIAL_FACETS_STATUS.md - Current status
- [ ] FEAT_SPATIAL_FACETS_TESTING.md - Testing guide
- [ ] FEAT_SPATIAL_FACETS_OUTPUT.md - Test outputs
- [ ] FEAT_SPATIAL_FACETS_SUMMARY.md - Summary
- [ ] FEAT_SPATIAL_FACETS_ASSESSMENT.md - Assessment

---

## Files to Create/Modify

### New Files
| File | Description |
|------|-------------|
| `WKTParser.java` | Parse WKT POINT/POLYGON |
| `GeoTextHit.java` | Search hit with coordinates |
| `GeoFacetedResults.java` | Combined results container |
| `GeoSearchParams.java` | Query parameters |
| `TextSearchPF.java` | SPARQL property function |
| `TestWKTParser.java` | WKT parsing tests |
| `TestGeoIndexing.java` | Indexing tests |
| `TestGeoSearch.java` | Query tests |
| `TestTextSearchPF.java` | Property function tests |

### Modified Files
| File | Changes |
|------|---------|
| `TextIndexConfig.java` | Add geo config properties |
| `TextIndexLucene.java` | Add geo indexing and query methods |
| `TextVocab.java` | Add geo vocabulary terms |
| `TextIndexLuceneAssembler.java` | Parse geo config |
| `TextDocProducerEntities.java` | Complete entity-mode implementation |
| `TextQuery.java` | Register text:search PF |
| `pom.xml` | Add lucene-spatial3d dependency |

---

## Implementation Order

1. Dependencies (pom.xml)
2. Config (TextIndexConfig, TextVocab, Assembler)
3. WKT Parser
4. Geo Indexing (TextIndexLucene.doc)
5. Entity Mode (TextDocProducerEntities)
6. Query Classes (GeoSearchParams, GeoTextHit, GeoFacetedResults)
7. Query Execution (searchWithGeoAndFacets)
8. SPARQL PF (TextSearchPF)
9. Tests
10. Documentation

---

**Started:** 2026-01-23
**Status:** In Progress
