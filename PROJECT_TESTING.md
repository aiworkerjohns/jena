# Testing the Faceting Implementation

This guide describes how to build, deploy, and test the new faceting functionality in Apache Jena's text index module.

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

# Verify the build
mvn test -pl jena-text -Dtest="*Facet*"
```

Expected output:
```
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Build Fuseki Server

```bash
# Build the Fuseki server (executable jar with jena-text included)
mvn clean install -pl jena-fuseki2/jena-fuseki-server -am -DskipTests
```

This builds `jena-fuseki-server` which is an uber-jar containing all dependencies including `jena-text`.

---

## Step 2: Create Test Configuration

### Create a Test Directory

```bash
mkdir -p ~/fuseki-facet-test
cd ~/fuseki-facet-test
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

## Text-indexed dataset
<#text_dataset> rdf:type text:TextDataset ;
    text:dataset <#base_dataset> ;
    text:index <#indexLucene> .

## Base dataset (in-memory for testing)
<#base_dataset> rdf:type ja:MemoryDataset .

## Lucene index configuration
<#indexLucene> rdf:type text:TextIndexLucene ;
    text:directory "mem" ;           # In-memory index for testing
    text:storeValues true ;          # Required for faceting
    text:entityMap <#entMap> .

## Entity mapping - defines indexed fields
<#entMap> rdf:type text:EntityMap ;
    text:entityField "uri" ;
    text:defaultField "text" ;
    text:map (
        [ text:field "text" ;     text:predicate rdfs:label ]
        [ text:field "comment" ;  text:predicate rdfs:comment ]
        [ text:field "category" ; text:predicate <http://example.org/category> ]
        [ text:field "author" ;   text:predicate <http://example.org/author> ]
        [ text:field "year" ;     text:predicate <http://example.org/year> ]
    ) .
```

---

## Step 3: Start Fuseki Server

### Run the Fuseki Server

```bash
# Navigate to fuseki-server module
cd /Users/hjohns/workspace/kurrawong/fuseki/jena/jena-fuseki2/jena-fuseki-server

# Run Fuseki with the test configuration
java -jar target/jena-fuseki-server-6.0.0-SNAPSHOT.jar \
    --config ~/fuseki-facet-test/config.ttl
```

### Alternative: Run from project root

```bash
cd /Users/hjohns/workspace/kurrawong/fuseki/jena

java -jar jena-fuseki2/jena-fuseki-server/target/jena-fuseki-server-6.0.0-SNAPSHOT.jar \
    --config ~/fuseki-facet-test/config.ttl
```

The server will start on `http://localhost:3030/`

---

## Step 4: Load Test Data

### Create Test Data File

Create `test-data.ttl`:

```turtle
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix ex: <http://example.org/> .

# Technology documents
ex:doc1 rdfs:label "Introduction to Machine Learning" ;
        ex:category "technology" ;
        ex:author "Smith" ;
        ex:year "2023" .

ex:doc2 rdfs:label "Deep Learning Neural Networks" ;
        ex:category "technology" ;
        ex:author "Jones" ;
        ex:year "2023" .

ex:doc3 rdfs:label "Machine Learning for Beginners" ;
        ex:category "technology" ;
        ex:author "Smith" ;
        ex:year "2024" .

ex:doc4 rdfs:label "Advanced Machine Learning Techniques" ;
        ex:category "technology" ;
        ex:author "Brown" ;
        ex:year "2024" .

# Science documents
ex:doc5 rdfs:label "Learning About Quantum Physics" ;
        ex:category "science" ;
        ex:author "Wilson" ;
        ex:year "2023" .

ex:doc6 rdfs:label "Machine Learning in Biology" ;
        ex:category "science" ;
        ex:author "Smith" ;
        ex:year "2024" .

# Cooking documents
ex:doc7 rdfs:label "Learning to Cook Italian" ;
        ex:category "cooking" ;
        ex:author "Garcia" ;
        ex:year "2022" .

ex:doc8 rdfs:label "Learning Baking Fundamentals" ;
        ex:category "cooking" ;
        ex:author "Taylor" ;
        ex:year "2023" .
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

**Note:** With unnamed endpoints in the config, all operations go to `/ds`:
- GSP (load data): `/ds?default` or `/ds?graph=<uri>`
- SPARQL Query: POST to `/ds` with `Content-Type: application/sparql-query`
- SPARQL Update: POST to `/ds` with `Content-Type: application/sparql-update`

---

## Step 5: Test Faceted Queries

### Test 1: Basic Text Search with Facets

```bash
curl -X POST "http://localhost:3030/ds" \
    -H "Content-Type: application/sparql-query" \
    -H "Accept: application/json" \
    -d '
PREFIX text: <http://jena.apache.org/text#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?doc ?score
WHERE {
  (?doc ?score) text:queryWithFacets ("learning") .
}
ORDER BY DESC(?score)
'
```

Expected: Returns all 8 documents containing "learning" with scores.

### Test 2: Search with Specific Property

```bash
curl -X POST "http://localhost:3030/ds" \
    -H "Content-Type: application/sparql-query" \
    -H "Accept: application/json" \
    -d '
PREFIX text: <http://jena.apache.org/text#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?doc ?score ?label
WHERE {
  (?doc ?score) text:queryWithFacets (rdfs:label "machine learning") .
  ?doc rdfs:label ?label .
}
ORDER BY DESC(?score)
'
```

Expected: Returns documents with "machine learning" in their label.

### Test 3: Combine Text Search with SPARQL Filtering

```bash
curl -X POST "http://localhost:3030/ds" \
    -H "Content-Type: application/sparql-query" \
    -H "Accept: application/json" \
    -d '
PREFIX text: <http://jena.apache.org/text#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX ex: <http://example.org/>

SELECT ?doc ?label ?category
WHERE {
  (?doc ?score) text:queryWithFacets ("learning") .
  ?doc rdfs:label ?label ;
       ex:category ?category .
  FILTER(?category = "technology")
}
ORDER BY DESC(?score)
'
```

Expected: Returns only technology documents containing "learning".

### Test 4: Aggregate Facet Counts with SPARQL

```bash
curl -X POST "http://localhost:3030/ds" \
    -H "Content-Type: application/sparql-query" \
    -H "Accept: application/json" \
    -d '
PREFIX text: <http://jena.apache.org/text#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX ex: <http://example.org/>

SELECT ?category (COUNT(?doc) AS ?count)
WHERE {
  (?doc ?score) text:queryWithFacets ("learning") .
  ?doc ex:category ?category .
}
GROUP BY ?category
ORDER BY DESC(?count)
'
```

Expected output:
```json
{
  "results": {
    "bindings": [
      { "category": {"value": "technology"}, "count": {"value": "4"} },
      { "category": {"value": "science"}, "count": {"value": "2"} },
      { "category": {"value": "cooking"}, "count": {"value": "2"} }
    ]
  }
}
```

### Test 5: Multiple Facet Dimensions

```bash
curl -X POST "http://localhost:3030/ds" \
    -H "Content-Type: application/sparql-query" \
    -H "Accept: application/json" \
    -d '
PREFIX text: <http://jena.apache.org/text#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX ex: <http://example.org/>

SELECT ?author (COUNT(?doc) AS ?count)
WHERE {
  (?doc ?score) text:queryWithFacets ("learning") .
  ?doc ex:author ?author .
}
GROUP BY ?author
ORDER BY DESC(?count)
'
```

Expected: Shows document counts by author.

### Test 6: Year Faceting

```bash
curl -X POST "http://localhost:3030/ds" \
    -H "Content-Type: application/sparql-query" \
    -H "Accept: application/json" \
    -d '
PREFIX text: <http://jena.apache.org/text#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX ex: <http://example.org/>

SELECT ?year (COUNT(?doc) AS ?count)
WHERE {
  (?doc ?score) text:queryWithFacets ("learning") .
  ?doc ex:year ?year .
}
GROUP BY ?year
ORDER BY ?year
'
```

---

## Step 6: Test via Java API

### Create a Test Class

Create `FacetingApiTest.java`:

```java
import org.apache.jena.query.*;
import org.apache.jena.query.text.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDFS;
import org.apache.lucene.store.ByteBuffersDirectory;
import java.util.*;

public class FacetingApiTest {
    public static void main(String[] args) {
        // Create in-memory dataset with text index
        Dataset baseDs = DatasetFactory.create();

        // Configure text index
        EntityDefinition entDef = new EntityDefinition("uri", "text");
        entDef.setPrimaryPredicate(RDFS.label);
        entDef.set("category",
            ResourceFactory.createProperty("http://example.org/category").asNode());

        TextIndexConfig config = new TextIndexConfig(entDef);
        config.setValueStored(true);

        // Create text-enabled dataset
        Dataset ds = TextDatasetFactory.createLucene(
            baseDs, new ByteBuffersDirectory(), config);

        // Load test data
        ds.begin(ReadWrite.WRITE);
        Model m = ds.getDefaultModel();
        m.read("test-data.ttl", "TURTLE");
        ds.commit();

        // Execute faceted query via SPARQL
        String queryStr = """
            PREFIX text: <http://jena.apache.org/text#>
            PREFIX ex: <http://example.org/>

            SELECT ?category (COUNT(?doc) AS ?count)
            WHERE {
              (?doc ?score) text:queryWithFacets ("learning") .
              ?doc ex:category ?category .
            }
            GROUP BY ?category
            ORDER BY DESC(?count)
            """;

        ds.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(queryStr, ds)) {
            ResultSet rs = qe.execSelect();
            System.out.println("=== Facet Results ===");
            while (rs.hasNext()) {
                QuerySolution sol = rs.next();
                System.out.printf("%s: %s%n",
                    sol.getLiteral("category").getString(),
                    sol.getLiteral("count").getInt());
            }
        }
        ds.end();

        // Direct API usage
        TextIndex textIndex = (TextIndex) ds.getContext().get(TextQuery.textIndex);
        if (textIndex instanceof TextIndexLucene luceneIndex) {
            List<Resource> props = List.of(RDFS.label);
            List<String> facetFields = List.of("category");

            FacetedTextResults results = luceneIndex.queryWithFacets(
                props, "learning", null, null, 100, facetFields, 10);

            System.out.println("\n=== Direct API Results ===");
            System.out.println("Total hits: " + results.getTotalHits());

            for (FacetValue fv : results.getFacetsForField("category")) {
                System.out.printf("%s: %d%n", fv.getValue(), fv.getCount());
            }
        }

        ds.close();
    }
}
```

### Compile and Run

```bash
# Add jena-text to classpath and compile
javac -cp "path/to/jena-text.jar:path/to/dependencies/*" FacetingApiTest.java

# Run
java -cp ".:path/to/jena-text.jar:path/to/dependencies/*" FacetingApiTest
```

---

## Step 7: Verify with Unit Tests

### Run All Faceting Tests

```bash
cd /Users/hjohns/workspace/kurrawong/fuseki/jena/jena-text

# Run all faceting-related tests
mvn test -Dtest="*Facet*"
```

### Run Individual Test Classes

```bash
# Unit tests for data structures
mvn test -Dtest=TestFacetedResults

# Integration tests with Lucene
mvn test -Dtest=TestFacetedSearchIntegration

# Performance benchmarks
mvn test -Dtest=TestFacetedSearchPerformance

# SPARQL property function tests
mvn test -Dtest=TestTextQueryFacetsPF
```

---

## Troubleshooting

### Server Won't Start

```bash
# Check if port 3030 is in use
lsof -i :3030

# Use alternative port
java -jar target/jena-fuseki-main-*-SNAPSHOT.jar \
    --port 3031 \
    --config ~/fuseki-facet-test/config.ttl
```

### Text Index Not Working

Verify configuration includes:
```turtle
text:storeValues true ;  # Required for faceting
```

### No Search Results

Check that data is loaded:
```bash
curl -X POST "http://localhost:3030/ds" \
    -d "SELECT * WHERE { ?s ?p ?o } LIMIT 10"
```

### Facet Counts Missing

Ensure fields are mapped in entityMap:
```turtle
text:map (
    [ text:field "category" ; text:predicate <http://example.org/category> ]
) .
```

---

## Performance Testing

### Load Large Dataset

```bash
# Generate test data (example with 10,000 documents)
for i in $(seq 1 10000); do
    cat << EOF
ex:doc$i rdfs:label "Document about learning topic $i" ;
         ex:category "cat$((i % 10))" ;
         ex:author "author$((i % 50))" .
EOF
done > large-test-data.ttl

# Load into Fuseki
curl -X POST "http://localhost:3030/ds?default" \
    -H "Content-Type: text/turtle" \
    --data-binary @large-test-data.ttl
```

### Benchmark Queries

```bash
# Time a faceted query
time curl -s -X POST "http://localhost:3030/ds" \
    -H "Content-Type: application/sparql-query" \
    -d 'PREFIX text: <http://jena.apache.org/text#>
        SELECT ?doc WHERE { (?doc) text:queryWithFacets ("learning") }' \
    > /dev/null
```

---

## Expected Test Results Summary

| Test | Expected Result |
|------|-----------------|
| Basic search | All documents with "learning" returned |
| Property search | Only matching property values |
| Category faceting | Correct counts per category |
| Author faceting | Correct counts per author |
| Year faceting | Correct counts per year |
| Empty results | No matches, empty result set |
| Performance (1K docs) | < 200ms |
| Performance (5K docs) | < 500ms |

---

## Clean Up

```bash
# Stop Fuseki server (Ctrl+C)

# Remove test directory
rm -rf ~/fuseki-facet-test
```

---

**Last Updated:** 2026-01-16
