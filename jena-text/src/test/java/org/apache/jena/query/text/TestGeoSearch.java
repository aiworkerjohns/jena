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
import java.util.List;

import org.apache.jena.query.text.geo.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for geo search functionality.
 */
public class TestGeoSearch {

    private TextIndexLucene textIndex;
    private ByteBuffersDirectory directory;

    @Before
    public void setUp() {
        // Create entity definition with geo field
        EntityDefinition entDef = new EntityDefinition("uri", "text");
        entDef.set("category", org.apache.jena.graph.NodeFactory.createURI("http://example.org/category"));
        entDef.set("location", org.apache.jena.graph.NodeFactory.createURI("http://example.org/location"));

        // Configure text index with geo and facet fields
        TextIndexConfig config = new TextIndexConfig(entDef);
        config.setValueStored(true);
        config.setGeoFields(Arrays.asList("location"));
        config.setStoreCoordinates(true);
        config.setFacetFields(Arrays.asList("category"));

        // Create in-memory index
        directory = new ByteBuffersDirectory();
        textIndex = new TextIndexLucene(directory, config);

        // Add test data
        addTestData();
    }

    private void addTestData() {
        // San Francisco area locations
        addEntity("http://example.org/e1", "Coffee Shop Downtown", "cafe",
            "POINT(-122.4 37.78)");  // Downtown SF
        addEntity("http://example.org/e2", "Coffee Shop Marina", "cafe",
            "POINT(-122.43 37.80)");  // Marina
        addEntity("http://example.org/e3", "Restaurant Financial", "restaurant",
            "POINT(-122.39 37.79)");  // Financial District
        addEntity("http://example.org/e4", "Restaurant Sunset", "restaurant",
            "POINT(-122.49 37.76)");  // Sunset

        // Outside SF - Berkeley
        addEntity("http://example.org/e5", "Coffee Shop Berkeley", "cafe",
            "POINT(-122.27 37.87)");

        // Outside SF - Oakland
        addEntity("http://example.org/e6", "Restaurant Oakland", "restaurant",
            "POINT(-122.27 37.80)");

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
        textIndex.close();
        directory.close();
    }

    // ========== Basic Search Tests ==========

    @Test
    public void testTextOnlySearch() {
        GeoSearchParams params = new GeoSearchParams.Builder()
            .textQuery("Coffee")
            .build();

        GeoFacetedResults results = textIndex.searchWithGeoAndFacets(params);

        assertEquals(3, results.getTotalHits());
        assertEquals(3, results.getReturnedHitCount());
    }

    @Test
    public void testTextSearchWithCoordinates() {
        GeoSearchParams params = new GeoSearchParams.Builder()
            .textQuery("Coffee Shop Downtown")
            .geoField("location")
            .build();

        GeoFacetedResults results = textIndex.searchWithGeoAndFacets(params);

        assertTrue(results.hasHits());
        GeoTextHit hit = results.getHits().get(0);
        assertTrue(hit.hasCoordinates());
        assertEquals(37.78, hit.getLatitude(), 0.01);
        assertEquals(-122.4, hit.getLongitude(), 0.01);
    }

    // ========== BBox Search Tests ==========

    @Test
    public void testBboxSearchSF() {
        // Bounding box covering SF
        GeoSearchParams params = new GeoSearchParams.Builder()
            .bbox(-122.5, 37.7, -122.35, 37.85)
            .geoField("location")
            .build();

        GeoFacetedResults results = textIndex.searchWithGeoAndFacets(params);

        // Should find 4 SF locations
        assertEquals(4, results.getTotalHits());
    }

    @Test
    public void testBboxSearchSmallArea() {
        // Small bbox - just Financial District
        GeoSearchParams params = new GeoSearchParams.Builder()
            .bbox(-122.41, 37.78, -122.38, 37.80)
            .geoField("location")
            .build();

        GeoFacetedResults results = textIndex.searchWithGeoAndFacets(params);

        // Should find just 1-2 locations
        assertTrue(results.getTotalHits() <= 2);
    }

    @Test
    public void testBboxWithTextQuery() {
        GeoSearchParams params = new GeoSearchParams.Builder()
            .textQuery("Coffee")
            .bbox(-122.5, 37.7, -122.35, 37.85)
            .geoField("location")
            .build();

        GeoFacetedResults results = textIndex.searchWithGeoAndFacets(params);

        // Should find only coffee shops in SF (2)
        assertEquals(2, results.getTotalHits());
    }

    // ========== Distance Search Tests ==========

    @Test
    public void testDistanceSearch() {
        // Search within 5km of downtown SF
        GeoSearchParams params = new GeoSearchParams.Builder()
            .distance(-122.4, 37.78, 5.0)
            .geoField("location")
            .build();

        GeoFacetedResults results = textIndex.searchWithGeoAndFacets(params);

        // Should find several SF locations
        assertTrue(results.getTotalHits() >= 2);
    }

    @Test
    public void testDistanceWithTextQuery() {
        GeoSearchParams params = new GeoSearchParams.Builder()
            .textQuery("Restaurant")
            .distance(-122.4, 37.78, 10.0)  // 10km radius from downtown SF
            .geoField("location")
            .build();

        GeoFacetedResults results = textIndex.searchWithGeoAndFacets(params);

        // Should find SF restaurants
        assertTrue(results.hasHits());
        for (GeoTextHit hit : results.getHits()) {
            assertTrue(hit.getNode().getURI().contains("example.org"));
        }
    }

    // ========== Facet Tests ==========

    @Test
    public void testSearchWithFacets() {
        GeoSearchParams params = new GeoSearchParams.Builder()
            .bbox(-122.5, 37.7, -122.35, 37.85)
            .geoField("location")
            .facetFields(Arrays.asList("category"))
            .maxFacetValues(10)
            .build();

        GeoFacetedResults results = textIndex.searchWithGeoAndFacets(params);

        assertTrue(results.hasFacets());
        List<FacetValue> categoryFacets = results.getFacetsForField("category");
        assertFalse(categoryFacets.isEmpty());

        // Check that we have both cafe and restaurant categories
        boolean hasCafe = categoryFacets.stream().anyMatch(f -> f.getValue().equals("cafe"));
        boolean hasRestaurant = categoryFacets.stream().anyMatch(f -> f.getValue().equals("restaurant"));
        assertTrue(hasCafe || hasRestaurant);
    }

    @Test
    public void testSearchWithTextAndFacets() {
        GeoSearchParams params = new GeoSearchParams.Builder()
            .textQuery("Coffee")
            .facetFields(Arrays.asList("category"))
            .maxFacetValues(10)
            .build();

        GeoFacetedResults results = textIndex.searchWithGeoAndFacets(params);

        assertTrue(results.hasFacets());
        List<FacetValue> categoryFacets = results.getFacetsForField("category");

        // All coffee shops should be in "cafe" category
        boolean hasCafe = categoryFacets.stream().anyMatch(f -> f.getValue().equals("cafe"));
        assertTrue(hasCafe);
    }

    // ========== Polygon Search Tests ==========

    @Test
    public void testPolygonIntersects() {
        // Polygon covering downtown SF
        String polygon = "POLYGON((-122.42 37.77, -122.38 37.77, -122.38 37.80, -122.42 37.80, -122.42 37.77))";

        GeoSearchParams params = new GeoSearchParams.Builder()
            .intersects(polygon)
            .geoField("location")
            .build();

        GeoFacetedResults results = textIndex.searchWithGeoAndFacets(params);

        // Should find some locations in downtown
        assertTrue(results.hasHits());
    }

    // ========== Edge Cases ==========

    @Test
    public void testEmptyResults() {
        GeoSearchParams params = new GeoSearchParams.Builder()
            .textQuery("NonexistentPlace")
            .build();

        GeoFacetedResults results = textIndex.searchWithGeoAndFacets(params);

        assertEquals(0, results.getTotalHits());
        assertFalse(results.hasHits());
    }

    @Test
    public void testBboxOutsideData() {
        // Bbox in Atlantic Ocean
        GeoSearchParams params = new GeoSearchParams.Builder()
            .bbox(-30, 40, -20, 50)
            .geoField("location")
            .build();

        GeoFacetedResults results = textIndex.searchWithGeoAndFacets(params);

        assertEquals(0, results.getTotalHits());
    }

    // ========== Configuration Tests ==========

    @Test
    public void testGeoEnabled() {
        assertTrue(textIndex.isGeoEnabled());
        assertEquals(1, textIndex.getGeoFields().size());
        assertEquals("location", textIndex.getGeoFields().get(0));
    }
}
