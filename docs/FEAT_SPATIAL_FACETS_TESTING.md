# Testing the Spatial + Faceting Implementation

This guide describes how to build, deploy, and test the combined text + spatial + faceting functionality in Apache Jena's text index module.

---

## Prerequisites

- Java 21+ (Java 25 recommended)
- Maven 3.9+
- curl (for HTTP requests)

Verify your environment:
```bash
java -version    # Should show 21+
mvn --version    # Should show 3.9+
```

---

## Step 1: Build the Project

### Build jena-text with Dependencies

```bash
cd /Users/hjohns/workspace/kurrawong/fuseki/jena

# Build jena-text and all its dependencies
mvn clean install -pl jena-text -am -DskipTests

# Verify the build - run spatial and facet tests
mvn test -pl jena-text -Dtest="TestGeoSearch,TestWKTParser,TestTextSearchPF"
```

Expected output:
```
Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Build Fuseki Server

```bash
# Build the Fuseki server (executable jar with jena-text included)
mvn clean install -pl jena-fuseki2/jena-fuseki-server -am -DskipTests
```

This builds `jena-fuseki-server` which is an uber-jar containing all dependencies including `jena-text` with spatial support.

---

## Step 2: Create Test Configuration

### Create a Test Directory

```bash
mkdir -p ~/fuseki-spatial-test
cd ~/fuseki-spatial-test
```

### Create Fuseki Configuration File

Create `config.ttl`:

```turtle
PREFIX fuseki:  <http://jena.apache.org/fuseki#>
PREFIX rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs:    <http://www.w3.org/2000/01/rdf-schema#>
PREFIX ja:      <http://jena.hpl.hp.com/2005/11/Assembler#>
PREFIX text:    <http://jena.apache.org/text#>
PREFIX tdb2:    <http://jena.apache.org/2016/tdb#>

[] rdf:type fuseki:Server ;
   fuseki:services (
     <#service>
   ) .

<#service> rdf:type fuseki:Service ;
    fuseki:name "ds" ;
    fuseki:endpoint [ fuseki:operation fuseki:query ] ;
    fuseki:endpoint [ fuseki:operation fuseki:update ] ;
    fuseki:endpoint [ fuseki:operation fuseki:gsp-rw ] ;
    fuseki:dataset <#text_dataset> .

## Text-indexed dataset with spatial support
<#text_dataset> rdf:type text:TextDataset ;
    text:dataset <#base_dataset> ;
    text:index <#indexLucene> .

## Base dataset (in-memory for testing)
<#base_dataset> rdf:type ja:MemoryDataset .

## Lucene index configuration WITH SPATIAL AND FACETING
<#indexLucene> rdf:type text:TextIndexLucene ;
    text:directory "mem" ;           # In-memory index for testing
    text:storeValues true ;          # Store field values
    text:storeCoordinates true ;     # Store lat/lon for retrieval
    text:geoFields ("location") ;    # Enable geo indexing on location field
    text:geoFormat "WKT" ;           # WKT POINT format (lon lat)
    text:facetFields ("category" "type") ;  # Enable native faceting
    text:entityMap <#entMap> .

## Entity mapping - defines indexed fields
<#entMap> rdf:type text:EntityMap ;
    text:entityField "uri" ;
    text:defaultField "text" ;
    text:map (
        [ text:field "text" ;     text:predicate rdfs:label ]
        [ text:field "comment" ;  text:predicate rdfs:comment ]
        [ text:field "category" ; text:predicate <http://example.org/category> ]
        [ text:field "type" ;     text:predicate <http://example.org/type> ]
        [ text:field "location" ; text:predicate <http://example.org/location> ]
    ) .
```

**Important Configuration Options:**
- `text:geoFields` - Fields containing WKT POINT geometry
- `text:geoFormat "WKT"` - WKT format for coordinates
- `text:storeCoordinates true` - Store lat/lon for retrieval in results
- `text:facetFields` - Fields for native Lucene faceting

---

## Step 3: Start Fuseki Server

### Run the Fuseki Server

```bash
# Navigate to fuseki-server module
cd /Users/hjohns/workspace/kurrawong/fuseki/jena/jena-fuseki2/jena-fuseki-server

# Run Fuseki with the test configuration
java -jar target/jena-fuseki-server-6.0.0-SNAPSHOT.jar \
    --config ~/fuseki-spatial-test/config.ttl
```

The server will start on `http://localhost:3030/`

---

## Step 4: Load Test Data

### Create Test Data File

Create `test-data.ttl` with places in the San Francisco Bay Area:

```turtle
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix ex: <http://example.org/> .

# San Francisco locations
ex:place1 rdfs:label "Coffee Shop Downtown" ;
          ex:category "cafe" ;
          ex:type "business" ;
          ex:location "POINT(-122.4 37.78)" .  # Downtown SF

ex:place2 rdfs:label "Coffee Shop Marina" ;
          ex:category "cafe" ;
          ex:type "business" ;
          ex:location "POINT(-122.43 37.80)" .  # Marina District

ex:place3 rdfs:label "Restaurant Financial" ;
          ex:category "restaurant" ;
          ex:type "business" ;
          ex:location "POINT(-122.39 37.79)" .  # Financial District

ex:place4 rdfs:label "Restaurant Sunset" ;
          ex:category "restaurant" ;
          ex:type "business" ;
          ex:location "POINT(-122.49 37.76)" .  # Sunset District

ex:place5 rdfs:label "Coffee House Berkeley" ;
          ex:category "cafe" ;
          ex:type "business" ;
          ex:location "POINT(-122.27 37.87)" .  # Berkeley

ex:place6 rdfs:label "Restaurant Oakland" ;
          ex:category "restaurant" ;
          ex:type "business" ;
          ex:location "POINT(-122.27 37.80)" .  # Oakland

# Parks and landmarks
ex:place7 rdfs:label "Golden Gate Park" ;
          ex:category "park" ;
          ex:type "landmark" ;
          ex:location "POINT(-122.48 37.77)" .

ex:place8 rdfs:label "Muir Woods National Monument" ;
          ex:category "park" ;
          ex:type "landmark" ;
          ex:location "POINT(-122.58 37.89)" .  # Marin County
```

### Load Data via HTTP

```bash
# Load the test data (POST to default graph)
curl -X POST "http://localhost:3030/ds?default" \
    -H "Content-Type: text/turtle" \
    --data-binary @test-data.ttl

# Verify data loaded
curl -X POST "http://localhost:3030/ds" \
    -H "Content-Type: application/sparql-query" \
    -H "Accept: application/json" \
    -d "SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }"
```

---

## Step 5: Test Basic Text Search with text:search

### Test 1: Basic Text Search

```bash
curl -X POST "http://localhost:3030/ds" \
    -H "Content-Type: application/sparql-query" \
    -H "Accept: application/json" \
    -d '
PREFIX text: <http://jena.apache.org/text#>

SELECT ?s ?score
WHERE {
  (?s ?score) text:search ("Coffee")
}
ORDER BY DESC(?score)
'
```

Expected: Returns 3 coffee places with scores.

### Test 2: Text Search with Coordinates

```bash
curl -X POST "http://localhost:3030/ds" \
    -H "Content-Type: application/sparql-query" \
    -H "Accept: application/json" \
    -d '
PREFIX text: <http://jena.apache.org/text#>

SELECT ?s ?score ?lat ?lon
WHERE {
  (?s ?score ?lat ?lon) text:search ("Coffee")
}
ORDER BY DESC(?score)
'
```

Expected: Returns coffee places with their latitude and longitude.

---

## Step 6: Test Bounding Box Search (geo:bbox)

### Test 1: BBox Only - Find All Places in SF

```bash
curl -X POST "http://localhost:3030/ds" \
    -H "Content-Type: application/sparql-query" \
    -H "Accept: application/json" \
    -d '
PREFIX text: <http://jena.apache.org/text#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>

SELECT ?s ?score ?lat ?lon
WHERE {
  (?s ?score ?lat ?lon) text:search (geo:bbox -122.5 37.7 -122.35 37.85)
}
'
```

Expected: Returns places within the SF bounding box (minLon minLat maxLon maxLat).

### Test 2: Text + BBox - Coffee Shops in SF

```bash
curl -X POST "http://localhost:3030/ds" \
    -H "Content-Type: application/sparql-query" \
    -H "Accept: application/json" \
    -d '
PREFIX text: <http://jena.apache.org/text#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>

SELECT ?s ?score ?lat ?lon
WHERE {
  (?s ?score ?lat ?lon) text:search ("Coffee" geo:bbox -122.5 37.7 -122.35 37.85)
}
'
```

Expected: Returns only coffee places within SF (not Berkeley).

---

## Step 7: Test Distance Search (geo:distance)

### Test 1: Distance Only - Find Places within 5km of Downtown

```bash
curl -X POST "http://localhost:3030/ds" \
    -H "Content-Type: application/sparql-query" \
    -H "Accept: application/json" \
    -d '
PREFIX text: <http://jena.apache.org/text#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>

SELECT ?s ?score ?lat ?lon
WHERE {
  (?s ?score ?lat ?lon) text:search (geo:distance -122.4 37.78 5.0)
}
'
```

Expected: Returns places within 5km of downtown SF (centerLon centerLat radiusKm).

### Test 2: Text + Distance - Restaurants within 10km

```bash
curl -X POST "http://localhost:3030/ds" \
    -H "Content-Type: application/sparql-query" \
    -H "Accept: application/json" \
    -d '
PREFIX text: <http://jena.apache.org/text#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>

SELECT ?s ?score ?lat ?lon
WHERE {
  (?s ?score ?lat ?lon) text:search ("Restaurant" geo:distance -122.4 37.78 10.0)
}
'
```

Expected: Returns restaurants within 10km of downtown SF.

---

## Step 8: Test Polygon Search (geo:intersects)

### Test 1: Polygon Intersects - Downtown SF Area

```bash
curl -X POST "http://localhost:3030/ds" \
    -H "Content-Type: application/sparql-query" \
    -H "Accept: application/json" \
    -d '
PREFIX text: <http://jena.apache.org/text#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>

SELECT ?s ?score ?lat ?lon
WHERE {
  (?s ?score ?lat ?lon) text:search (
    geo:intersects "POLYGON((-122.42 37.77, -122.38 37.77, -122.38 37.80, -122.42 37.80, -122.42 37.77))"
  )
}
'
```

Expected: Returns places within the downtown polygon.

---

## Step 9: Test Combined Search with Facets

### Test 1: Text + Geo + Facets

```bash
curl -X POST "http://localhost:3030/ds" \
    -H "Content-Type: application/sparql-query" \
    -H "Accept: application/json" \
    -d '
PREFIX text: <http://jena.apache.org/text#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>
PREFIX facet: <http://jena.apache.org/text/facet#>

SELECT ?s ?score ?lat ?lon ?field ?value ?count
WHERE {
  (?s ?score ?lat ?lon ?field ?value ?count) text:search (
    "Coffee OR Restaurant"
    geo:bbox -122.5 37.7 -122.35 37.85
    facet:fields "category" 10
  )
}
'
```

Expected: Returns places in SF matching "Coffee OR Restaurant" with category facet counts.

---

## Step 10: Test via Java API

### Geo Search API

```java
import org.apache.jena.query.text.*;
import org.apache.jena.query.text.geo.*;
import java.util.*;

// Build geo search parameters
GeoSearchParams params = new GeoSearchParams.Builder()
    .textQuery("Coffee")
    .bbox(-122.5, 37.7, -122.35, 37.85)
    .geoField("location")
    .facetFields(Arrays.asList("category"))
    .maxFacetValues(10)
    .build();

// Execute search
TextIndexLucene index = ...;
GeoFacetedResults results = index.searchWithGeoAndFacets(params);

// Process results
for (GeoTextHit hit : results.getHits()) {
    System.out.printf("%s (%.4f, %.4f) score=%.2f%n",
        hit.getNode().getURI(),
        hit.getLatitude(),
        hit.getLongitude(),
        hit.getScore());
}

// Process facets
for (FacetValue fv : results.getFacetsForField("category")) {
    System.out.printf("  %s: %d%n", fv.getValue(), fv.getCount());
}
```

---

## Step 11: Verify with Unit Tests

### Run All Spatial + Faceting Tests

```bash
cd /Users/hjohns/workspace/kurrawong/fuseki/jena/jena-text

# Run all geo and faceting tests
mvn test -Dtest="TestGeoSearch,TestWKTParser,TestTextSearchPF"
```

Expected output:
```
Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Run Individual Test Classes

```bash
# WKT parsing tests
mvn test -Dtest=TestWKTParser

# Geo search API tests
mvn test -Dtest=TestGeoSearch

# SPARQL text:search property function tests
mvn test -Dtest=TestTextSearchPF
```

---

## Troubleshooting

### Server Won't Start

```bash
# Check if port 3030 is in use
lsof -i :3030

# Use alternative port
java -jar target/jena-fuseki-server-6.0.0-SNAPSHOT.jar \
    --port 3031 \
    --config ~/fuseki-spatial-test/config.ttl
```

### Geo Search Not Working

1. **Check `text:geoFields` is configured:**
```turtle
<#indexLucene> rdf:type text:TextIndexLucene ;
    text:geoFields ("location") ;  # Required!
    ...
```

2. **Verify location field is mapped in entity map:**
```turtle
text:map (
    [ text:field "location" ; text:predicate <http://example.org/location> ]
)
```

3. **Ensure location values are WKT POINT format:**
```turtle
ex:place1 ex:location "POINT(-122.4 37.78)" .  # lon lat order
```

### No Coordinates in Results

Ensure `text:storeCoordinates true` is set in the index configuration.

### Entity Mode vs Triple Mode

The current implementation requires entity mode for combined geo+text+facets to work properly. In triple mode, each predicate creates a separate Lucene document, so geo and text fields won't be in the same document.

For production use with the SPARQL data loading path, implement `TextDocProducerEntities` to aggregate all predicates for a subject into a single document.

---

## Property Function Reference

### text:search

Combined text + spatial + facets search.

**Subject Variables:**
```sparql
(?s ?score ?lat ?lon ?facetField ?facetValue ?facetCount) text:search (...)
```
- `?s` - Subject URI
- `?score` - Lucene relevance score
- `?lat`, `?lon` - Coordinates (if storeCoordinates=true)
- `?facetField`, `?facetValue`, `?facetCount` - Facet data (if facets requested)

**Object Parameters:**
```sparql
text:search ("query" geo:bbox|distance|intersects|within ... facet:fields ...)
```

**Geo Operators:**
- `geo:bbox minLon minLat maxLon maxLat` - Bounding box filter
- `geo:distance centerLon centerLat radiusKm` - Distance filter (km)
- `geo:intersects "POLYGON(...)"` - Polygon intersection
- `geo:within "POLYGON(...)"` - Within polygon

**Facet Parameters:**
- `facet:fields field1 field2 ... maxValues` - Request facet counts

**Examples:**
```sparql
# Text only
(?s ?score) text:search ("Coffee")

# Text with coordinates
(?s ?score ?lat ?lon) text:search ("Coffee")

# Bbox only
(?s ?score ?lat ?lon) text:search (geo:bbox -122.5 37.7 -122.35 37.85)

# Text + bbox
(?s ?score ?lat ?lon) text:search ("Coffee" geo:bbox -122.5 37.7 -122.35 37.85)

# Text + distance
(?s ?score) text:search ("Restaurant" geo:distance -122.4 37.78 10.0)

# Text + bbox + facets
(?s ?score ?lat ?lon ?field ?value ?count) text:search (
  "Coffee OR Restaurant"
  geo:bbox -122.5 37.7 -122.35 37.85
  facet:fields "category" 10
)
```

---

## Expected Test Results Summary

| Test | Expected Result |
|------|-----------------|
| text:search (text only) | Documents + scores |
| text:search (with coords) | Documents + scores + lat/lon |
| geo:bbox | Documents in bounding box |
| geo:distance | Documents within radius (km) |
| geo:intersects | Documents intersecting polygon |
| geo:within | Documents within polygon |
| text + geo:bbox | Combined text and spatial filter |
| text + geo + facets | Text + spatial + facet counts |
| Empty bbox | No results |
| Invalid WKT | Error message |

---

## Clean Up

```bash
# Stop Fuseki server (Ctrl+C)

# Remove test directory
rm -rf ~/fuseki-spatial-test
```

---

**Last Updated:** 2026-01-23
