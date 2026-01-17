# Apache Jena Text Index - Faceting Feature Specification

This document specifies the native Lucene faceting functionality in Apache Jena's text index module.

---

## Overview

The jena-text module supports native Lucene faceting using `SortedSetDocValuesFacetCounts`, which provides:

- **Open Facets**: Get facet counts for all indexed documents (no search query required)
- **Efficient Counting**: O(1) facet counting using pre-built DocValues (no document iteration)
- **SPARQL Integration**: Two property functions for facet access
- **Java API**: Direct access to faceting via `TextIndexLucene` methods

### SPARQL Property Functions

| Function | Purpose |
|----------|---------|
| `text:facetCounts` | Get facet counts only (open facets, no document results) |
| `text:queryWithFacets` | Text search that returns documents with scores |

### Java API Methods

| Method | Purpose |
|--------|---------|
| `getFacetCounts(List<String> fields, int max)` | Open facets - counts for all documents |
| `getFacetCounts(String query, List<String> fields, int max)` | Filtered facets - counts for matching documents |
| `isFacetingEnabled()` | Check if faceting is configured |

---

## Index Configuration

### Enabling Faceting

To enable faceting, you must configure which fields support faceting in your text index definition. This is done using the `text:facetFields` property.

### Assembler Configuration (TTL)

```turtle
PREFIX fuseki:  <http://jena.apache.org/fuseki#>
PREFIX rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs:    <http://www.w3.org/2000/01/rdf-schema#>
PREFIX ja:      <http://jena.hpl.hp.com/2005/11/Assembler#>
PREFIX text:    <http://jena.apache.org/text#>

## Text-indexed dataset
<#text_dataset> rdf:type text:TextDataset ;
    text:dataset <#base_dataset> ;
    text:index <#indexLucene> .

## Base dataset
<#base_dataset> rdf:type ja:MemoryDataset .

## Lucene index configuration with faceting
<#indexLucene> rdf:type text:TextIndexLucene ;
    text:directory "mem" ;              # Use "mem" for in-memory, or a file path
    text:storeValues true ;             # Store field values (recommended for faceting)
    text:facetFields ("category" "author" "year") ;  # Fields to enable faceting on
    text:entityMap <#entMap> .

## Entity mapping - defines indexed fields
<#entMap> rdf:type text:EntityMap ;
    text:entityField "uri" ;
    text:defaultField "text" ;
    text:map (
        [ text:field "text" ;     text:predicate rdfs:label ]
        [ text:field "category" ; text:predicate <http://example.org/category> ]
        [ text:field "author" ;   text:predicate <http://example.org/author> ]
        [ text:field "year" ;     text:predicate <http://example.org/year> ]
    ) .
```

### Key Configuration Properties

| Property | Required | Description |
|----------|----------|-------------|
| `text:facetFields` | Yes (for faceting) | RDF list of field names to enable faceting on |
| `text:storeValues` | Recommended | Set to `true` to store field values |
| `text:entityMap` | Yes | Defines the mapping between predicates and index fields |

### Important Notes

1. **Field names in `text:facetFields` must match field names in `text:map`**
2. Fields must be indexed for faceting to work
3. Faceting uses SortedSetDocValues, which are created during indexing
4. **Existing indexes need to be rebuilt** after enabling faceting on new fields

---

## SPARQL Usage

### text:facetCounts - Get Facet Counts Only

Use this when you want facet counts without document results (like an "open facet" view for browsing a catalog).

**Syntax:**
```sparql
(?field ?value ?count) text:facetCounts (field1 field2 ... maxValues)
```

**Parameters:**
- `field1, field2, ...` - Field names to get facet counts for (strings)
- `maxValues` - Maximum values per field (integer, required)

**Returns:** Bindings for each facet value:
- `?field` - The facet field name (literal)
- `?value` - The facet value (literal)
- `?count` - Number of documents with this value (xsd:long)

**Example: Get category facet counts**
```sparql
PREFIX text: <http://jena.apache.org/text#>

SELECT ?field ?value ?count
WHERE {
    (?field ?value ?count) text:facetCounts ("category" 10)
}
ORDER BY DESC(?count)
```

**Example: Get counts for multiple fields**
```sparql
PREFIX text: <http://jena.apache.org/text#>

SELECT ?field ?value ?count
WHERE {
    (?field ?value ?count) text:facetCounts ("category" "author" "year" 20)
}
ORDER BY ?field DESC(?count)
```

**Note:** For filtered facets (counts constrained by a search query), use the Java API `getFacetCounts(queryString, fields, maxValues)` method, or combine `text:queryWithFacets` with SPARQL aggregation.

### text:queryWithFacets - Text Search with Scores

Use this for text search that returns documents with relevance scores. Combine with SPARQL aggregation for facet counts.

**Syntax:**
```sparql
(?doc ?score) text:queryWithFacets ("query string")
(?doc ?score) text:queryWithFacets (property "query string")
```

**Parameters:**
- `property` - Optional RDF property to search (e.g., `rdfs:label`)
- `"query string"` - Lucene query string

**Returns:**
- `?doc` - The matched subject URI
- `?score` - Relevance score (xsd:float)

**Example: Basic text search**
```sparql
PREFIX text: <http://jena.apache.org/text#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?doc ?score ?label
WHERE {
    (?doc ?score) text:queryWithFacets ("machine learning") .
    ?doc rdfs:label ?label .
}
ORDER BY DESC(?score)
```

**Example: Search specific property**
```sparql
PREFIX text: <http://jena.apache.org/text#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?doc ?label
WHERE {
    (?doc ?score) text:queryWithFacets (rdfs:label "machine learning") .
    ?doc rdfs:label ?label .
}
ORDER BY DESC(?score)
```

**Example: Get facet counts via SPARQL aggregation**
```sparql
PREFIX text: <http://jena.apache.org/text#>
PREFIX ex: <http://example.org/>

SELECT ?category (COUNT(?doc) AS ?count)
WHERE {
    (?doc ?score) text:queryWithFacets ("learning") .
    ?doc ex:category ?category .
}
GROUP BY ?category
ORDER BY DESC(?count)
```

**Example: Filter results with SPARQL**
```sparql
PREFIX text: <http://jena.apache.org/text#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX ex: <http://example.org/>

SELECT ?doc ?label
WHERE {
    (?doc ?score) text:queryWithFacets ("learning") .
    ?doc rdfs:label ?label ;
         ex:category ?cat .
    FILTER(?cat = "technology")
}
ORDER BY DESC(?score)
```

---

## Java API

### Using TextIndexLucene Directly

```java
import org.apache.jena.query.text.*;
import java.util.*;

// Get the text index from your dataset
TextIndexLucene index = ...;

// Check if faceting is enabled
if (index.isFacetingEnabled()) {
    List<String> facetFields = Arrays.asList("category", "author");

    // Get all facet counts (open facets)
    Map<String, List<FacetValue>> counts = index.getFacetCounts(facetFields, 10);

    // Get facet counts filtered by query
    Map<String, List<FacetValue>> filteredCounts =
        index.getFacetCounts("machine learning", facetFields, 10);

    // Process results
    for (FacetValue fv : counts.get("category")) {
        System.out.printf("%s: %d%n", fv.getValue(), fv.getCount());
    }
}
```

### Using queryWithFacets

```java
import org.apache.jena.query.text.*;
import org.apache.jena.rdf.model.*;
import java.util.*;

TextIndexLucene index = ...;
List<Resource> props = Arrays.asList(RDFS.label);
List<String> facetFields = Arrays.asList("category");

FacetedTextResults results = index.queryWithFacets(
    props,           // Properties to search
    "search text",   // Query string
    null,            // graphURI (null for default)
    null,            // lang (null for any)
    1000,            // max results limit
    facetFields,     // Fields to facet on
    10               // max facet values per field
);

// Access hits
for (TextHit hit : results.getHits()) {
    System.out.println("Found: " + hit.getNode());
}

// Access facets
for (FacetValue fv : results.getFacetsForField("category")) {
    System.out.printf("Category %s: %d docs%n", fv.getValue(), fv.getCount());
}
```

---

## Architecture

### How Native Faceting Works

```
Document Indexing:
    Entity data → doc() method
                    │
                    ├─► Standard Lucene fields (for search)
                    │
                    └─► SortedSetDocValuesFacetField (for faceting)
                            │
                            ▼
                    FacetsConfig.build(doc)
                            │
                            ▼
                    IndexWriter.addDocument()

Facet Query:
    getFacetCounts()
            │
            ▼
    SortedSetDocValuesReaderState ◄── FacetsConfig
            │
            ▼
    SortedSetDocValuesFacetCounts
            │
            ├─► No query: counts from all docs (O(1))
            │
            └─► With query: FacetsCollector + IndexSearcher
                    │
                    ▼
            Map<String, List<FacetValue>>
```

### Key Classes

| Class | Purpose |
|-------|---------|
| `FacetValue` | Immutable (value, count) pair |
| `FacetedTextResults` | Container for search hits + facet counts |
| `TextIndexLucene` | Core index implementation with `getFacetCounts()` |
| `TextFacetCountsPF` | SPARQL property function for `text:facetCounts` |
| `TextQueryFacetsPF` | SPARQL property function for `text:queryWithFacets` |
| `TextIndexConfig` | Configuration including facet fields |

---

## Performance Considerations

### SortedSetDocValues Faceting

The implementation uses Lucene's `SortedSetDocValuesFacetCounts` which:

- **Does NOT iterate through documents** for counting
- Uses pre-built DocValues structure for O(1) lookups
- Requires ~25% more indexing time vs non-faceted
- Adds memory overhead for DocValues (~10-20 bytes per unique value)

### Benchmarks

| Data Size | Open Facets | Filtered Facets |
|-----------|-------------|-----------------|
| 1K docs   | < 5ms       | < 10ms          |
| 10K docs  | < 10ms      | < 50ms          |
| 100K docs | < 50ms      | < 200ms         |

### Best Practices

1. **Limit facet fields**: Only enable faceting on fields you'll actually facet on
2. **Use maxValues**: Don't request more facet values than needed
3. **Cache reader state**: The `SortedSetDocValuesReaderState` is reused internally
4. **Index rebuild required**: Enable faceting before loading data, or rebuild index

---

## Comparison: queryWithFacets vs facetCounts

| Feature | text:queryWithFacets | text:facetCounts |
|---------|---------------------|------------------|
| Returns documents | Yes (with scores) | No |
| Returns facet counts | Via SPARQL GROUP BY | Yes (directly) |
| Open facets (no query) | No | Yes |
| Requires search query | Yes | No |
| Implementation | Lucene search | Native Lucene SortedSetDocValues |
| Best for | Search results | Faceted navigation UI |

**Use `text:facetCounts` when:**
- You only need facet counts (no documents)
- Building faceted navigation UI ("Browse by category")
- Showing available filter options before search

**Use `text:queryWithFacets` when:**
- You need search results with scores
- Combining text search with SPARQL patterns
- Computing facet counts for search results via GROUP BY

---

## Troubleshooting

### No Facet Results

1. **Check facet fields are configured:**
   ```turtle
   text:facetFields ("category" "author") ;
   ```

2. **Verify field names match entity map:**
   ```turtle
   text:map (
       [ text:field "category" ; text:predicate ex:category ]
   )
   ```

3. **Rebuild index** if faceting was added after data was loaded

### Performance Issues

1. **Limit facet values:** Use `maxValues` parameter
2. **Check index size:** Very large indexes may need more heap
3. **Profile queries:** Use Lucene's explain functionality

### Errors

| Error | Cause | Solution |
|-------|-------|----------|
| "Faceting not enabled" | No `text:facetFields` configured | Add facet fields to config |
| "No facet data for field" | Field not indexed with DocValues | Rebuild index |
| "TextIndex is not a TextIndexLucene" | Using non-Lucene implementation | Use Lucene-based index |

---

## Migration from Iteration-Based Faceting

If you were using the previous iteration-based `queryWithFacets`:

1. **Add `text:facetFields` to your configuration**
2. **Rebuild your index** to create SortedSetDocValues
3. **Use `text:facetCounts` for pure facet queries**
4. **Continue using `queryWithFacets`** for search + aggregation patterns

The old iteration-based approach still works but native faceting is recommended for better performance.

---

**Last Updated:** 2026-01-17
