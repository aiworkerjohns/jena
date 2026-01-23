# Spatial + Facets Feature - Test Output

This document records the test outputs from the spatial + faceting implementation.

---

## Automated Test Results

### Build Results

```
$ mvn install -DskipTests -pl jena-fuseki2/jena-fuseki-server -am

[INFO] Apache Jena - SPARQL Text Search ................... SUCCESS
[INFO] Apache Jena - Fuseki Server Jar .................... SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

### Unit Test Results

```
$ mvn test -pl jena-text -Dtest="TestGeoSearch,TestWKTParser,TestTextSearchPF"

[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0 -- TestWKTParser
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- TestGeoSearch
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- TestTextSearchPF
[INFO]
[INFO] Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Full jena-text Test Suite

```
$ mvn test -pl jena-text

[INFO] Tests run: 303, Failures: 0, Errors: 0, Skipped: 8
[INFO] BUILD SUCCESS
```

---

## Test Coverage Summary

### TestWKTParser (19 tests)

| Test | Result | Description |
|------|--------|-------------|
| testParsePointBasic | PASS | Parse "POINT(-122.4 37.8)" |
| testParsePointWithSpaces | PASS | Parse with extra whitespace |
| testParsePointCaseInsensitive | PASS | Parse "point(-122.4 37.8)" |
| testParsePointNegativeCoordinates | PASS | Parse "POINT(-180.0 -90.0)" |
| testParsePointPositiveCoordinates | PASS | Parse "POINT(180.0 90.0)" |
| testParsePointInvalidFormat | PASS | Reject "POINT(-122.4)" |
| testParsePointInvalidLatitude | PASS | Reject lat > 90 |
| testParsePointInvalidLongitude | PASS | Reject lon > 180 |
| testParsePointNull | PASS | Reject null |
| testParsePointEmpty | PASS | Reject empty string |
| testParsePolygonBasic | PASS | Parse 5-point polygon |
| testParsePolygonWithSpaces | PASS | Parse with extra whitespace |
| testParsePolygonCaseInsensitive | PASS | Parse "polygon(...)" |
| testParsePolygonNotClosed | PASS | Reject unclosed polygon |
| testParsePolygonTooFewPoints | PASS | Reject < 4 points |
| testParsePolygonNull | PASS | Reject null |
| testIsPoint | PASS | Detect POINT vs POLYGON |
| testIsPolygon | PASS | Detect POLYGON vs POINT |
| testPointToString | PASS | String representation |

### TestGeoSearch (13 tests)

| Test | Result | Description |
|------|--------|-------------|
| testTextOnlySearch | PASS | Text search for "Coffee" returns 3 hits |
| testTextSearchWithCoordinates | PASS | Search returns lat/lon for "Coffee Shop Downtown" |
| testBboxSearchSF | PASS | Bbox -122.5,37.7,-122.35,37.85 returns 4 SF locations |
| testBboxSearchSmallArea | PASS | Small bbox returns 1-2 locations |
| testBboxWithTextQuery | PASS | "Coffee" + SF bbox returns 2 hits |
| testDistanceSearch | PASS | 5km from downtown SF returns 2+ hits |
| testDistanceWithTextQuery | PASS | "Restaurant" + 10km radius returns hits |
| testSearchWithFacets | PASS | SF bbox returns category facets |
| testSearchWithTextAndFacets | PASS | "Coffee" returns cafe facet |
| testPolygonIntersects | PASS | Downtown polygon returns hits |
| testEmptyResults | PASS | "NonexistentPlace" returns 0 hits |
| testBboxOutsideData | PASS | Atlantic Ocean bbox returns 0 hits |
| testGeoEnabled | PASS | isGeoEnabled() returns true |

### TestTextSearchPF (9 tests)

| Test | Result | Description |
|------|--------|-------------|
| testBasicTextSearch | PASS | SPARQL "Coffee" returns 2 hits |
| testTextSearchWithCoordinates | PASS | Returns lat/lon in bindings |
| testBboxSearch | PASS | geo:bbox returns 2+ hits |
| testBboxWithTextSearch | PASS | "Coffee" + bbox returns 2 hits |
| testDistanceSearch | PASS | geo:distance returns 1+ hits |
| testDistanceWithTextSearch | PASS | "Restaurant" + distance returns hits |
| testSearchWithFacets | PASS | facet:fields returns facet data |
| testNoResults | PASS | "NonexistentPlace" returns empty |
| testBboxNoResults | PASS | Atlantic bbox returns empty |

---

## Expected SPARQL Query Results

### Test 1: Basic Text Search

**Query:**
```sparql
PREFIX text: <http://jena.apache.org/text#>

SELECT ?s ?score
WHERE {
  (?s ?score) text:search ("Coffee")
}
ORDER BY DESC(?score)
```

**Expected Result:**
```json
{
  "results": {
    "bindings": [
      { "s": {"value": "http://example.org/e1"}, "score": {"value": "1.0"} },
      { "s": {"value": "http://example.org/e2"}, "score": {"value": "1.0"} }
    ]
  }
}
```

### Test 2: Text Search with Coordinates

**Query:**
```sparql
PREFIX text: <http://jena.apache.org/text#>

SELECT ?s ?score ?lat ?lon
WHERE {
  (?s ?score ?lat ?lon) text:search ("Coffee Shop Downtown")
}
```

**Expected Result:**
```json
{
  "results": {
    "bindings": [
      {
        "s": {"value": "http://example.org/e1"},
        "score": {"value": "2.5"},
        "lat": {"value": "37.78", "datatype": "xsd:double"},
        "lon": {"value": "-122.4", "datatype": "xsd:double"}
      }
    ]
  }
}
```

### Test 3: Bounding Box Search

**Query:**
```sparql
PREFIX text: <http://jena.apache.org/text#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>

SELECT ?s ?score ?lat ?lon
WHERE {
  (?s ?score ?lat ?lon) text:search (geo:bbox -122.5 37.7 -122.35 37.85)
}
```

**Expected Result:** Returns 4 SF locations (e1, e2, e3, e4 - excludes Berkeley/Oakland)

### Test 4: Text + BBox Combined

**Query:**
```sparql
PREFIX text: <http://jena.apache.org/text#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>

SELECT ?s ?score ?lat ?lon
WHERE {
  (?s ?score ?lat ?lon) text:search ("Coffee" geo:bbox -122.5 37.7 -122.35 37.85)
}
```

**Expected Result:** Returns 2 coffee shops in SF (e1, e2)

### Test 5: Distance Search

**Query:**
```sparql
PREFIX text: <http://jena.apache.org/text#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>

SELECT ?s ?score ?lat ?lon
WHERE {
  (?s ?score ?lat ?lon) text:search (geo:distance -122.4 37.78 5.0)
}
```

**Expected Result:** Returns locations within 5km of downtown SF

### Test 6: Search with Facets

**Query:**
```sparql
PREFIX text: <http://jena.apache.org/text#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>
PREFIX facet: <http://jena.apache.org/text/facet#>

SELECT ?s ?score ?lat ?lon ?field ?value ?count
WHERE {
  (?s ?score ?lat ?lon ?field ?value ?count) text:search (
    "Coffee OR Restaurant"
    facet:fields "category" 10
  )
}
```

**Expected Result:** Returns hits with category facet counts (cafe: 2, restaurant: 2)

---

## Performance Notes

Based on unit test execution times:

| Test Class | Time |
|------------|------|
| TestWKTParser | 0.027s |
| TestGeoSearch | 0.432s |
| TestTextSearchPF | 0.110s |

The geo search operations are efficient, with:
- In-memory index creation + 6 entities + queries in < 0.5s
- WKT parsing is very fast (< 30ms for 19 tests)
- SPARQL property function overhead is minimal

---

## Known Issues

### Entity Mode Requirement
The current implementation requires entity-mode indexing for combined text+geo+facets queries. When using the standard RDF loading path:

1. Triple mode creates separate Lucene documents per predicate
2. Geo field and text field end up in different documents
3. Combined queries fail because fields aren't co-located

**Workaround:** Use the Java API to add entities directly:
```java
Entity entity = new Entity(uri, "");
entity.put("text", "Coffee Shop Downtown");
entity.put("category", "cafe");
entity.put("location", "POINT(-122.4 37.78)");
textIndex.addEntity(entity);
```

### Future Work
- Implement TextDocProducerEntities for full RDF integration
- Add performance benchmarks for large datasets
- Consider geo:within support for more complex spatial queries

---

**Last Updated:** 2026-01-23
