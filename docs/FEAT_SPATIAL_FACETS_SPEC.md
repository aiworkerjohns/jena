# Apache Jena Text Index - Spatial + Facets Feature Specification

This document specifies the combined spatial search and faceting functionality in Apache Jena's text index module.

---

## Overview

The jena-text module extends native Lucene faceting with spatial search capabilities, allowing:

- **Text Search**: Full-text search with relevance scoring
- **Spatial Search**: Point, bbox, distance, and polygon queries
- **Faceted Results**: O(1) facet counting using SortedSetDocValues
- **Coordinate Retrieval**: Return lat/lon for each hit
- **Combined Queries**: Single Lucene query for text + spatial + facets

### SPARQL Property Functions

| Function | Purpose |
|----------|---------|
| `text:search` | Combined text + spatial search with facets and coordinates |
| `text:facetCounts` | Get facet counts (existing, now supports spatial filter) |
| `text:queryWithFacets` | Text search with facets (existing) |

### Spatial Operators

| Operator | Description |
|----------|-------------|
| `bbox` | Bounding box query (minLon, minLat, maxLon, maxLat) |
| `distance` | Distance/radius query (centerLon, centerLat, radiusKm) |
| `intersects` | Polygon intersection query (WKT POLYGON) |
| `within` | Within polygon query (WKT POLYGON) |

---

## Index Configuration

### Enabling Spatial + Facets

Configure geo fields along with facet fields in your text index definition.

### Assembler Configuration (TTL)

```turtle
PREFIX fuseki:  <http://jena.apache.org/fuseki#>
PREFIX rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs:    <http://www.w3.org/2000/01/rdf-schema#>
PREFIX ja:      <http://jena.hpl.hp.com/2005/11/Assembler#>
PREFIX text:    <http://jena.apache.org/text#>
PREFIX geo:     <http://www.opengis.net/ont/geosparql#>

## Text-indexed dataset
<#text_dataset> rdf:type text:TextDataset ;
    text:dataset <#base_dataset> ;
    text:index <#indexLucene> .

## Base dataset
<#base_dataset> rdf:type ja:MemoryDataset .

## Lucene index configuration with spatial and faceting
<#indexLucene> rdf:type text:TextIndexLucene ;
    text:directory "mem" ;
    text:storeValues true ;
    text:facetFields ("category" "author" "year") ;
    text:geoFields ("location") ;                    # Geo fields to index
    text:geoFormat "WKT" ;                           # WKT POINT (lon lat) format
    text:storeCoordinates true ;                     # Store lat/lon for retrieval
    text:docProducerMode "entity" ;                  # "entity" or "triple" (default: triple)
    text:entityMap <#entMap> .

## Entity mapping
<#entMap> rdf:type text:EntityMap ;
    text:entityField "uri" ;
    text:defaultField "text" ;
    text:map (
        [ text:field "text" ;     text:predicate rdfs:label ]
        [ text:field "category" ; text:predicate <http://example.org/category> ]
        [ text:field "author" ;   text:predicate <http://example.org/author> ]
        [ text:field "year" ;     text:predicate <http://example.org/year> ]
        [ text:field "location" ; text:predicate geo:hasGeometry/geo:asWKT ]
    ) .
```

### Configuration Properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `text:geoFields` | Yes (for spatial) | none | RDF list of field names for geo indexing |
| `text:geoFormat` | No | "WKT" | Input format: "WKT" for WKT POINT/POLYGON |
| `text:storeCoordinates` | No | true | Store lat/lon for retrieval |
| `text:docProducerMode` | No | "triple" | "entity" for one-doc-per-entity, "triple" for one-doc-per-triple |
| `text:facetFields` | Yes (for facets) | none | Fields for facet counts |

### Document Producer Modes

**Triple Mode (default)**: One Lucene document per RDF triple. Simpler but requires join for multi-field queries.

**Entity Mode**: One Lucene document per entity (subject). All text, geo, and facet fields in single doc. Better performance for combined queries at scale.

---

## SPARQL Usage

### text:search - Combined Text + Spatial Search

**Syntax:**
```sparql
# Basic text search with coordinates
(?doc ?score ?lat ?lon) text:search ("query string")

# Text search with spatial filter
(?doc ?score ?lat ?lon) text:search ("query string" geo:bbox minLon minLat maxLon maxLat)
(?doc ?score ?lat ?lon) text:search ("query string" geo:distance centerLon centerLat radiusKm)
(?doc ?score ?lat ?lon) text:search ("query string" geo:intersects "POLYGON((...))")
(?doc ?score ?lat ?lon) text:search ("query string" geo:within "POLYGON((...))")

# With facets
(?doc ?score ?lat ?lon ?facetField ?facetValue ?facetCount) text:search
    ("query string" geo:bbox -180 -90 180 90 facet:fields "category" "author")

# Spatial only (no text query)
(?doc ?score ?lat ?lon) text:search (geo:bbox minLon minLat maxLon maxLat)
```

**Parameters:**
- `"query string"` - Optional Lucene query string
- `geo:bbox minLon minLat maxLon maxLat` - Bounding box filter
- `geo:distance centerLon centerLat radiusKm` - Distance filter
- `geo:intersects "WKT"` - Polygon intersection filter
- `geo:within "WKT"` - Within polygon filter
- `facet:fields field1 field2 ...` - Request facet counts

**Returns:**
- `?doc` - Entity URI
- `?score` - Relevance score (xsd:float)
- `?lat` - Latitude (xsd:double)
- `?lon` - Longitude (xsd:double)
- `?facetField` - Facet field name (if facets requested)
- `?facetValue` - Facet value
- `?facetCount` - Count for facet value

### Example: Text + BBox + Facets

```sparql
PREFIX text: <http://jena.apache.org/text#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?doc ?label ?score ?lat ?lon ?facetField ?facetValue ?facetCount
WHERE {
    # Search for "coffee shop" within a bounding box, get category facets
    (?doc ?score ?lat ?lon ?facetField ?facetValue ?facetCount) text:search (
        "coffee shop"
        geo:bbox -122.5 37.7 -122.3 37.9
        facet:fields "category"
        10  # max facet values
    ) .
    ?doc rdfs:label ?label .
}
ORDER BY DESC(?score)
```

### Example: Distance Query with Facets

```sparql
PREFIX text: <http://jena.apache.org/text#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>

SELECT ?doc ?score ?lat ?lon ?category (COUNT(*) AS ?count)
WHERE {
    # Find "restaurant" within 5km of a point
    (?doc ?score ?lat ?lon) text:search (
        "restaurant"
        geo:distance -122.4 37.8 5.0
    ) .
    ?doc <http://example.org/category> ?category .
}
GROUP BY ?category
ORDER BY DESC(?count)
```

### Example: Polygon Intersection

```sparql
PREFIX text: <http://jena.apache.org/text#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>

SELECT ?doc ?score ?lat ?lon
WHERE {
    # Find documents intersecting a polygon
    (?doc ?score ?lat ?lon) text:search (
        "park"
        geo:intersects "POLYGON((-122.5 37.7, -122.3 37.7, -122.3 37.9, -122.5 37.9, -122.5 37.7))"
    ) .
}
```

### Example: Spatial-Only Query (No Text)

```sparql
PREFIX text: <http://jena.apache.org/text#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>

SELECT ?doc ?lat ?lon
WHERE {
    # Get all points in bounding box (no text filter)
    (?doc ?score ?lat ?lon) text:search (
        geo:bbox -122.5 37.7 -122.3 37.9
    ) .
}
```

---

## Java API

### GeoSearchParams

```java
import org.apache.jena.query.text.*;
import org.apache.jena.query.text.geo.*;

// Create search parameters
GeoSearchParams params = new GeoSearchParams.Builder()
    .textQuery("coffee shop")
    .bbox(-122.5, 37.7, -122.3, 37.9)
    .facetFields(Arrays.asList("category"))
    .maxFacetValues(10)
    .maxResults(100)
    .build();

// Or with distance
GeoSearchParams distanceParams = new GeoSearchParams.Builder()
    .textQuery("restaurant")
    .distance(-122.4, 37.8, 5.0)  // lon, lat, radiusKm
    .build();

// Or with polygon
GeoSearchParams polygonParams = new GeoSearchParams.Builder()
    .textQuery("park")
    .intersects("POLYGON((-122.5 37.7, -122.3 37.7, -122.3 37.9, -122.5 37.9, -122.5 37.7))")
    .build();
```

### TextIndexLucene Methods

```java
// Combined search with geo and facets
GeoFacetedResults results = index.searchWithGeoAndFacets(params);

// Access results
for (GeoTextHit hit : results.getHits()) {
    System.out.printf("URI: %s, Score: %.2f, Lat: %.6f, Lon: %.6f%n",
        hit.getNode(), hit.getScore(), hit.getLatitude(), hit.getLongitude());
}

// Access facets
for (FacetValue fv : results.getFacetsForField("category")) {
    System.out.printf("  %s: %d%n", fv.getValue(), fv.getCount());
}
```

---

## Indexing

### Geo Field Types

For point data:
- `LatLonPoint` - For spatial queries (bbox, distance)
- `LatLonShape` - For polygon queries (intersects, within)
- `StoredField` - For coordinate retrieval

### WKT Input Format

Supports standard WKT format with longitude first:
- `POINT(-122.4 37.8)` - Point at lon=-122.4, lat=37.8
- `POLYGON((...))` - Polygon for shape queries

### Document Structure

**Entity Mode** (recommended for performance):
```
Lucene Document {
  uri: "http://example.org/entity1"
  text: "Coffee Shop Downtown"
  category: "cafe"
  author: "Smith"
  location: LatLonPoint(-122.4, 37.8)
  location: LatLonShape(point)
  location_lat: 37.8 (StoredField)
  location_lon: -122.4 (StoredField)
  $facets: SortedSetDocValuesFacetField(category, "cafe")
}
```

**Triple Mode** (one document per triple, join by entity URI):
```
Lucene Document { uri: "entity1", text: "Coffee Shop Downtown" }
Lucene Document { uri: "entity1", category: "cafe" }
Lucene Document { uri: "entity1", location: LatLonPoint(-122.4, 37.8) }
```

---

## Performance Considerations

### Entity Mode vs Triple Mode

| Aspect | Entity Mode | Triple Mode |
|--------|-------------|-------------|
| Query performance | Better - single doc lookup | Slower - requires join |
| Index size | Smaller - no URI duplication | Larger |
| Update granularity | Must update entire entity | Can update single triple |
| Recommended for | Large datasets with combined queries | Small datasets, simple queries |

### Spatial Query Performance

| Query Type | Typical Performance |
|------------|---------------------|
| BBox | Very fast (B-tree index) |
| Distance | Fast (uses bbox pre-filter) |
| Polygon | Moderate (shape intersection) |

### Best Practices

1. **Use entity mode** for combined text+geo+facet queries at scale
2. **Limit result set** - spatial queries can match many documents
3. **Use bbox pre-filter** for polygon queries when possible
4. **Index rebuild required** when adding geo fields to existing data

---

## Architecture

### Query Flow

```
SPARQL: (?doc ?score ?lat ?lon) text:search ("query" geo:bbox ...)
                │
                ▼
        TextSearchPF (Property Function)
                │
                ▼
        GeoSearchParams (parsed parameters)
                │
                ▼
        TextIndexLucene.searchWithGeoAndFacets()
                │
                ├─► Build Lucene Query:
                │     BooleanQuery {
                │       MUST: textQuery (if provided)
                │       FILTER: geoQuery (bbox/distance/polygon)
                │     }
                │
                ├─► Execute search with FacetsCollector
                │
                └─► Return GeoFacetedResults:
                      - List<GeoTextHit> (uri, score, lat, lon)
                      - Map<field, List<FacetValue>>
```

### Key Classes

| Class | Purpose |
|-------|---------|
| `GeoTextHit` | Search hit with coordinates |
| `GeoFacetedResults` | Combined hits + facets + coordinates |
| `GeoSearchParams` | Query parameters container |
| `TextSearchPF` | SPARQL property function for text:search |
| `WKTParser` | Parse WKT POINT/POLYGON to Lucene geo types |
| `TextDocProducerEntities` | Entity-mode document producer |

---

## Migration / Operational Notes

### Enabling Spatial on Existing Index

1. **Full reindex required** - geo fields need LatLonPoint/LatLonShape data
2. Recommended: Build new index in parallel, then cutover

### Configuration Migration

```turtle
# Old config (facets only)
<#indexLucene> rdf:type text:TextIndexLucene ;
    text:facetFields ("category") ;
    ...

# New config (facets + spatial)
<#indexLucene> rdf:type text:TextIndexLucene ;
    text:facetFields ("category") ;
    text:geoFields ("location") ;
    text:docProducerMode "entity" ;  # Recommended for combined queries
    ...
```

---

## Limitations

1. **WKT format only** - GeoJSON/GML not yet supported
2. **Single geo field per document** in MVP - multi-geo planned
3. **Polygon complexity** - Very complex polygons may be slow
4. **CRS assumption** - WGS84 (EPSG:4326) assumed

---

**Last Updated:** 2026-01-23
