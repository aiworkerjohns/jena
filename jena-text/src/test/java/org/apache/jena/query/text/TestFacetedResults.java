/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jena.query.text;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Tests for faceted search data structures
 */
public class TestFacetedResults {
    
    @Test
    public void testFacetValueBasics() {
        FacetValue fv = new FacetValue("electronics", 42);
        
        assertEquals("electronics", fv.getValue());
        assertEquals(42, fv.getCount());
        assertEquals("electronics (42)", fv.toString());
    }
    
    @Test
    public void testFacetValueEquality() {
        FacetValue fv1 = new FacetValue("books", 10);
        FacetValue fv2 = new FacetValue("books", 10);
        FacetValue fv3 = new FacetValue("books", 20);
        FacetValue fv4 = new FacetValue("electronics", 10);
        
        assertEquals(fv1, fv2);
        assertNotEquals(fv1, fv3);  // different count
        assertNotEquals(fv1, fv4);  // different value
        
        assertEquals(fv1.hashCode(), fv2.hashCode());
    }
    
    @Test
    public void testFacetedTextResultsBasics() {
        // Create some test hits
        Node subject1 = NodeFactory.createURI("http://example.org/doc1");
        Node subject2 = NodeFactory.createURI("http://example.org/doc2");
        Node literal = NodeFactory.createLiteralString("test");
        
        List<TextHit> hits = Arrays.asList(
            new TextHit(subject1, 1.0f, literal),
            new TextHit(subject2, 0.8f, literal)
        );
        
        // Create facets
        Map<String, List<FacetValue>> facets = new HashMap<>();
        facets.put("category", Arrays.asList(
            new FacetValue("electronics", 5),
            new FacetValue("books", 3)
        ));
        facets.put("author", Arrays.asList(
            new FacetValue("Smith", 4),
            new FacetValue("Jones", 4)
        ));
        
        FacetedTextResults results = new FacetedTextResults(hits, facets, 2);
        
        assertEquals(2, results.getHits().size());
        assertEquals(2, results.getReturnedHitCount());
        assertEquals(2, results.getTotalHits());
        assertEquals(2, results.getFacets().size());
    }
    
    @Test
    public void testFacetedTextResultsGetFacetsForField() {
        List<TextHit> hits = new ArrayList<>();
        
        Map<String, List<FacetValue>> facets = new HashMap<>();
        List<FacetValue> categoryFacets = Arrays.asList(
            new FacetValue("electronics", 5),
            new FacetValue("books", 3)
        );
        facets.put("category", categoryFacets);
        
        FacetedTextResults results = new FacetedTextResults(hits, facets, 0);
        
        List<FacetValue> retrieved = results.getFacetsForField("category");
        assertEquals(2, retrieved.size());
        assertEquals("electronics", retrieved.get(0).getValue());
        assertEquals(5, retrieved.get(0).getCount());
        
        // Non-existent field returns empty list
        List<FacetValue> notFound = results.getFacetsForField("nonexistent");
        assertTrue(notFound.isEmpty());
    }
    
    @Test
    public void testFacetedTextResultsImmutability() {
        List<TextHit> hits = new ArrayList<>();
        Map<String, List<FacetValue>> facets = new HashMap<>();
        facets.put("category", new ArrayList<>());
        
        FacetedTextResults results = new FacetedTextResults(hits, facets, 0);
        
        // Verify collections are unmodifiable
        try {
            results.getHits().add(null);
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
        
        try {
            results.getFacets().put("newkey", new ArrayList<>());
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
    }
    
    @Test
    public void testFacetedTextResultsToString() {
        List<TextHit> hits = new ArrayList<>();
        Map<String, List<FacetValue>> facets = new HashMap<>();
        facets.put("category", Arrays.asList(new FacetValue("test", 1)));
        
        FacetedTextResults results = new FacetedTextResults(hits, facets, 10);
        
        String str = results.toString();
        assertTrue(str.contains("hits=0"));
        assertTrue(str.contains("totalHits=10"));
        assertTrue(str.contains("category"));
    }
}
