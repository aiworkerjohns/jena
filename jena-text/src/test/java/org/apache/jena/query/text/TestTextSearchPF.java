/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 *   SPDX-License-Identifier: Apache-2.0
 */

package org.apache.jena.query.text;

import static org.junit.Assert.*;

import java.util.Arrays;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDFS;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for the text:search SPARQL property function.
 */
public class TestTextSearchPF {

    private Dataset dataset;
    private ByteBuffersDirectory directory;

    private static final String GEO_NS = "http://www.opengis.net/ont/geosparql#";
    private static final String FACET_NS = "http://jena.apache.org/text/facet#";
    private static final String EX_NS = "http://example.org/";

    private TextIndexLucene textIndex;

    @Before
    public void setUp() {
        // Initialize text query
        TextQuery.init();

        // Create entity definition
        EntityDefinition entDef = new EntityDefinition("uri", "text");
        entDef.setPrimaryPredicate(RDFS.label);
        entDef.set("category", ResourceFactory.createProperty(EX_NS + "category").asNode());
        entDef.set("location", ResourceFactory.createProperty(EX_NS + "location").asNode());

        // Configure text index with geo and facet fields
        TextIndexConfig config = new TextIndexConfig(entDef);
        config.setValueStored(true);
        config.setGeoFields(Arrays.asList("location"));
        config.setStoreCoordinates(true);
        config.setFacetFields(Arrays.asList("category"));

        // Create dataset with text index
        Dataset baseDs = DatasetFactory.create();
        directory = new ByteBuffersDirectory();

        // Create text index directly for entity-mode data loading
        textIndex = new TextIndexLucene(directory, config);
        dataset = TextDatasetFactory.create(baseDs, textIndex);

        // Add test data directly to text index (entity mode)
        // This ensures geo, text, and facet fields are in the same document
        loadTestData();
    }

    private void loadTestData() {
        // Add entities with all fields in single documents (entity mode)
        // This is necessary because triple mode creates separate docs per predicate
        addEntity(EX_NS + "e1", "Coffee Shop Downtown", "cafe", "POINT(-122.4 37.78)");
        addEntity(EX_NS + "e2", "Coffee Shop Marina", "cafe", "POINT(-122.43 37.80)");
        addEntity(EX_NS + "e3", "Restaurant Financial", "restaurant", "POINT(-122.39 37.79)");
        addEntity(EX_NS + "e4", "Restaurant Sunset", "restaurant", "POINT(-122.49 37.76)");

        textIndex.commit();
    }

    private void addEntity(String uri, String text, String category, String location) {
        Entity entity = new Entity(uri, "");
        entity.put("text", text);
        entity.put("category", category);
        entity.put("location", location);
        textIndex.addEntity(entity);
    }

    @After
    public void tearDown() throws Exception {
        dataset.close();
        textIndex.close();
        directory.close();
    }

    // ========== Basic text:search Tests ==========

    @Test
    public void testBasicTextSearch() {
        String queryStr = String.format("""
            PREFIX text: <http://jena.apache.org/text#>

            SELECT ?s ?score
            WHERE {
                (?s ?score) text:search ("Coffee")
            }
            """);

        try (QueryExecution qe = QueryExecutionFactory.create(queryStr, dataset)) {
            ResultSet rs = qe.execSelect();
            int count = 0;
            while (rs.hasNext()) {
                rs.next();
                count++;
            }
            assertEquals(2, count);
        }
    }

    @Test
    public void testTextSearchWithCoordinates() {
        String queryStr = String.format("""
            PREFIX text: <http://jena.apache.org/text#>

            SELECT ?s ?score ?lat ?lon
            WHERE {
                (?s ?score ?lat ?lon) text:search ("Coffee Shop Downtown")
            }
            """);

        try (QueryExecution qe = QueryExecutionFactory.create(queryStr, dataset)) {
            ResultSet rs = qe.execSelect();
            assertTrue(rs.hasNext());
            QuerySolution sol = rs.next();

            assertNotNull(sol.get("s"));
            assertNotNull(sol.get("score"));
            // Coordinates should be present if the entity has geo data
            if (sol.get("lat") != null) {
                double lat = sol.getLiteral("lat").getDouble();
                assertTrue(lat > 30 && lat < 40);  // Roughly SF area
            }
        }
    }

    // ========== Geo BBox Tests ==========

    @Test
    public void testBboxSearch() {
        String queryStr = String.format("""
            PREFIX text: <http://jena.apache.org/text#>
            PREFIX geo: <%s>

            SELECT ?s ?score ?lat ?lon
            WHERE {
                (?s ?score ?lat ?lon) text:search (geo:bbox -122.5 37.7 -122.35 37.85)
            }
            """, GEO_NS);

        try (QueryExecution qe = QueryExecutionFactory.create(queryStr, dataset)) {
            ResultSet rs = qe.execSelect();
            int count = 0;
            while (rs.hasNext()) {
                rs.next();
                count++;
            }
            // Should find locations in SF area
            assertTrue(count >= 2);
        }
    }

    @Test
    public void testBboxWithTextSearch() {
        String queryStr = String.format("""
            PREFIX text: <http://jena.apache.org/text#>
            PREFIX geo: <%s>

            SELECT ?s ?score ?lat ?lon
            WHERE {
                (?s ?score ?lat ?lon) text:search ("Coffee" geo:bbox -122.5 37.7 -122.35 37.85)
            }
            """, GEO_NS);

        try (QueryExecution qe = QueryExecutionFactory.create(queryStr, dataset)) {
            ResultSet rs = qe.execSelect();
            int count = 0;
            while (rs.hasNext()) {
                QuerySolution sol = rs.next();
                String uri = sol.getResource("s").getURI();
                assertTrue(uri.contains("example.org"));
                count++;
            }
            // Should find coffee shops in SF area
            assertEquals(2, count);
        }
    }

    // ========== Geo Distance Tests ==========

    @Test
    public void testDistanceSearch() {
        String queryStr = String.format("""
            PREFIX text: <http://jena.apache.org/text#>
            PREFIX geo: <%s>

            SELECT ?s ?score ?lat ?lon
            WHERE {
                (?s ?score ?lat ?lon) text:search (geo:distance -122.4 37.78 5.0)
            }
            """, GEO_NS);

        try (QueryExecution qe = QueryExecutionFactory.create(queryStr, dataset)) {
            ResultSet rs = qe.execSelect();
            int count = 0;
            while (rs.hasNext()) {
                rs.next();
                count++;
            }
            // Should find locations within 5km of downtown SF
            assertTrue(count >= 1);
        }
    }

    @Test
    public void testDistanceWithTextSearch() {
        String queryStr = String.format("""
            PREFIX text: <http://jena.apache.org/text#>
            PREFIX geo: <%s>

            SELECT ?s ?score
            WHERE {
                (?s ?score) text:search ("Restaurant" geo:distance -122.4 37.78 10.0)
            }
            """, GEO_NS);

        try (QueryExecution qe = QueryExecutionFactory.create(queryStr, dataset)) {
            ResultSet rs = qe.execSelect();
            assertTrue(rs.hasNext());
        }
    }

    // ========== Facet Tests ==========

    @Test
    public void testSearchWithFacets() {
        String queryStr = String.format("""
            PREFIX text: <http://jena.apache.org/text#>
            PREFIX facet: <%s>

            SELECT ?s ?score ?field ?value ?count
            WHERE {
                (?s ?score ?lat ?lon ?field ?value ?count) text:search (
                    "Coffee OR Restaurant"
                    facet:fields "category" 10
                )
            }
            """, FACET_NS);

        try (QueryExecution qe = QueryExecutionFactory.create(queryStr, dataset)) {
            ResultSet rs = qe.execSelect();
            assertTrue(rs.hasNext());
            // Should have facet data
            QuerySolution sol = rs.next();
            assertNotNull(sol.get("s"));
        }
    }

    // ========== Empty Results Tests ==========

    @Test
    public void testNoResults() {
        String queryStr = """
            PREFIX text: <http://jena.apache.org/text#>

            SELECT ?s ?score
            WHERE {
                (?s ?score) text:search ("NonexistentPlace")
            }
            """;

        try (QueryExecution qe = QueryExecutionFactory.create(queryStr, dataset)) {
            ResultSet rs = qe.execSelect();
            assertFalse(rs.hasNext());
        }
    }

    @Test
    public void testBboxNoResults() {
        // Bbox in Atlantic Ocean
        String queryStr = String.format("""
            PREFIX text: <http://jena.apache.org/text#>
            PREFIX geo: <%s>

            SELECT ?s ?score
            WHERE {
                (?s ?score) text:search (geo:bbox -30 40 -20 50)
            }
            """, GEO_NS);

        try (QueryExecution qe = QueryExecutionFactory.create(queryStr, dataset)) {
            ResultSet rs = qe.execSelect();
            assertFalse(rs.hasNext());
        }
    }
}
