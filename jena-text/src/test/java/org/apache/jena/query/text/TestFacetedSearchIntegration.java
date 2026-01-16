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

import java.io.IOException;
import java.util.*;
import java.util.function.UnaryOperator;

import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDFS;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for faceted search functionality.
 * Tests the queryWithFacets$ method in TextIndexLucene with a real Lucene index.
 */
public class TestFacetedSearchIntegration {

    private static final String CATEGORY_FIELD = "category";
    private static final String AUTHOR_FIELD = "author";
    private static final String TEXT_FIELD = "text";

    private Directory directory;
    private TextIndexLucene textIndex;
    private EntityDefinition entityDef;

    @Before
    public void setUp() {
        // Create in-memory Lucene directory
        directory = new ByteBuffersDirectory();

        // Define entity mapping with facetable fields
        entityDef = new EntityDefinition("uri", TEXT_FIELD);
        entityDef.setPrimaryPredicate(RDFS.label);

        // Add category and author as indexed fields (for faceting)
        entityDef.set(CATEGORY_FIELD, ResourceFactory.createProperty("http://example.org/category").asNode());
        entityDef.set(AUTHOR_FIELD, ResourceFactory.createProperty("http://example.org/author").asNode());

        // Mark category and author fields to be stored but not analyzed (for faceting)
        entityDef.setNoIndex(CATEGORY_FIELD, false);
        entityDef.setNoIndex(AUTHOR_FIELD, false);

        // Create text index config with stored values (needed for faceting)
        TextIndexConfig config = new TextIndexConfig(entityDef);
        config.setValueStored(true);

        // Create the text index
        textIndex = new TextIndexLucene(directory, config);
    }

    @After
    public void tearDown() {
        if (textIndex != null) {
            textIndex.close();
        }
    }

    /**
     * Helper to add a document to the index.
     */
    private void addDocument(String uri, String text, String category, String author) {
        Entity entity = new Entity(uri, null);
        entity.put(TEXT_FIELD, text);
        if (category != null) {
            entity.put(CATEGORY_FIELD, category);
        }
        if (author != null) {
            entity.put(AUTHOR_FIELD, author);
        }
        textIndex.addEntity(entity);
    }

    @Test
    public void testBasicFacetingWithSingleField() throws ParseException, IOException {
        // Index test documents with categories
        addDocument("http://example.org/doc1", "machine learning algorithms", "technology", "Smith");
        addDocument("http://example.org/doc2", "deep learning neural networks", "technology", "Jones");
        addDocument("http://example.org/doc3", "learning to cook pasta", "cooking", "Smith");
        addDocument("http://example.org/doc4", "learning music theory", "music", "Brown");
        addDocument("http://example.org/doc5", "machine learning for beginners", "technology", "Smith");

        textIndex.commit();

        // Execute faceted query
        try (IndexReader indexReader = DirectoryReader.open(directory)) {
            List<Resource> props = Collections.singletonList(
                ResourceFactory.createProperty(RDFS.label.getURI())
            );
            List<String> facetFields = Collections.singletonList(CATEGORY_FIELD);

            FacetedTextResults results = textIndex.queryWithFacets$(
                indexReader,
                props,
                "learning",
                UnaryOperator.identity(),
                null,
                facetFields,
                10
            );

            // Verify hits
            assertNotNull(results);
            assertEquals(5, results.getTotalHits());
            assertEquals(5, results.getReturnedHitCount());

            // Verify facets
            Map<String, List<FacetValue>> facets = results.getFacets();
            assertEquals(1, facets.size());
            assertTrue(facets.containsKey(CATEGORY_FIELD));

            List<FacetValue> categoryFacets = facets.get(CATEGORY_FIELD);
            assertNotNull(categoryFacets);

            // Should have 3 categories: technology(3), cooking(1), music(1)
            assertEquals(3, categoryFacets.size());

            // Verify sorted by count (descending)
            assertEquals("technology", categoryFacets.get(0).getValue());
            assertEquals(3, categoryFacets.get(0).getCount());

            // The order of cooking and music may vary (both have count 1)
            Set<String> remainingCategories = new HashSet<>();
            remainingCategories.add(categoryFacets.get(1).getValue());
            remainingCategories.add(categoryFacets.get(2).getValue());
            assertTrue(remainingCategories.contains("cooking"));
            assertTrue(remainingCategories.contains("music"));
        }
    }

    @Test
    public void testFacetingWithMultipleFields() throws ParseException, IOException {
        // Index test documents
        addDocument("http://example.org/doc1", "machine learning algorithms", "technology", "Smith");
        addDocument("http://example.org/doc2", "deep learning neural networks", "technology", "Jones");
        addDocument("http://example.org/doc3", "learning to cook pasta", "cooking", "Smith");
        addDocument("http://example.org/doc4", "learning music theory", "music", "Brown");

        textIndex.commit();

        // Execute faceted query with multiple facet fields
        try (IndexReader indexReader = DirectoryReader.open(directory)) {
            List<Resource> props = Collections.singletonList(
                ResourceFactory.createProperty(RDFS.label.getURI())
            );
            List<String> facetFields = Arrays.asList(CATEGORY_FIELD, AUTHOR_FIELD);

            FacetedTextResults results = textIndex.queryWithFacets$(
                indexReader,
                props,
                "learning",
                UnaryOperator.identity(),
                null,
                facetFields,
                10
            );

            // Verify both facet fields are present
            Map<String, List<FacetValue>> facets = results.getFacets();
            assertEquals(2, facets.size());
            assertTrue(facets.containsKey(CATEGORY_FIELD));
            assertTrue(facets.containsKey(AUTHOR_FIELD));

            // Verify category facets
            List<FacetValue> categoryFacets = facets.get(CATEGORY_FIELD);
            assertEquals(3, categoryFacets.size());

            // Verify author facets: Smith(2), Jones(1), Brown(1)
            List<FacetValue> authorFacets = facets.get(AUTHOR_FIELD);
            assertEquals(3, authorFacets.size());
            assertEquals("Smith", authorFacets.get(0).getValue());
            assertEquals(2, authorFacets.get(0).getCount());
        }
    }

    @Test
    public void testFacetingWithMaxValuesLimit() throws ParseException, IOException {
        // Index documents with many different categories
        addDocument("http://example.org/doc1", "learning topic A", "catA", "Author1");
        addDocument("http://example.org/doc2", "learning topic B", "catB", "Author1");
        addDocument("http://example.org/doc3", "learning topic C", "catC", "Author1");
        addDocument("http://example.org/doc4", "learning topic D", "catD", "Author1");
        addDocument("http://example.org/doc5", "learning topic E", "catE", "Author1");
        addDocument("http://example.org/doc6", "learning topic A again", "catA", "Author1");
        addDocument("http://example.org/doc7", "learning topic A more", "catA", "Author1");

        textIndex.commit();

        // Execute faceted query with maxFacetValues = 2
        try (IndexReader indexReader = DirectoryReader.open(directory)) {
            List<Resource> props = Collections.singletonList(
                ResourceFactory.createProperty(RDFS.label.getURI())
            );
            List<String> facetFields = Collections.singletonList(CATEGORY_FIELD);

            FacetedTextResults results = textIndex.queryWithFacets$(
                indexReader,
                props,
                "learning",
                UnaryOperator.identity(),
                null,
                facetFields,
                2  // Only return top 2 facet values
            );

            // Verify we only get 2 facet values
            List<FacetValue> categoryFacets = results.getFacetsForField(CATEGORY_FIELD);
            assertEquals(2, categoryFacets.size());

            // First should be catA with count 3
            assertEquals("catA", categoryFacets.get(0).getValue());
            assertEquals(3, categoryFacets.get(0).getCount());
        }
    }

    @Test
    public void testFacetingWithNoResults() throws ParseException, IOException {
        // Index some documents
        addDocument("http://example.org/doc1", "machine learning algorithms", "technology", "Smith");

        textIndex.commit();

        // Search for something that doesn't exist
        try (IndexReader indexReader = DirectoryReader.open(directory)) {
            List<Resource> props = Collections.singletonList(
                ResourceFactory.createProperty(RDFS.label.getURI())
            );
            List<String> facetFields = Collections.singletonList(CATEGORY_FIELD);

            FacetedTextResults results = textIndex.queryWithFacets$(
                indexReader,
                props,
                "nonexistent",
                UnaryOperator.identity(),
                null,
                facetFields,
                10
            );

            // Should have no hits
            assertEquals(0, results.getTotalHits());
            assertEquals(0, results.getReturnedHitCount());
            assertTrue(results.getHits().isEmpty());

            // Facets should be empty
            assertTrue(results.getFacets().isEmpty() ||
                       results.getFacetsForField(CATEGORY_FIELD).isEmpty());
        }
    }

    @Test
    public void testFacetingWithEmptyFacetFields() throws ParseException, IOException {
        // Index documents
        addDocument("http://example.org/doc1", "machine learning algorithms", "technology", "Smith");
        addDocument("http://example.org/doc2", "deep learning neural networks", "technology", "Jones");

        textIndex.commit();

        // Execute query with no facet fields requested
        try (IndexReader indexReader = DirectoryReader.open(directory)) {
            List<Resource> props = Collections.singletonList(
                ResourceFactory.createProperty(RDFS.label.getURI())
            );
            List<String> facetFields = Collections.emptyList();

            FacetedTextResults results = textIndex.queryWithFacets$(
                indexReader,
                props,
                "learning",
                UnaryOperator.identity(),
                null,
                facetFields,
                10
            );

            // Should still get hits
            assertEquals(2, results.getTotalHits());

            // But no facets
            assertTrue(results.getFacets().isEmpty());
        }
    }

    @Test
    public void testFacetedTextResultsContainsCorrectHits() throws ParseException, IOException {
        // Index documents
        addDocument("http://example.org/doc1", "machine learning", "tech", "Smith");
        addDocument("http://example.org/doc2", "deep learning", "tech", "Jones");
        addDocument("http://example.org/doc3", "cooking recipes", "food", "Brown");

        textIndex.commit();

        // Execute query
        try (IndexReader indexReader = DirectoryReader.open(directory)) {
            List<Resource> props = Collections.singletonList(
                ResourceFactory.createProperty(RDFS.label.getURI())
            );
            List<String> facetFields = Collections.singletonList(CATEGORY_FIELD);

            FacetedTextResults results = textIndex.queryWithFacets$(
                indexReader,
                props,
                "learning",
                UnaryOperator.identity(),
                null,
                facetFields,
                10
            );

            // Should have 2 hits (doc1 and doc2 match "learning")
            assertEquals(2, results.getHits().size());

            // Verify hit URIs
            Set<String> hitUris = new HashSet<>();
            for (TextHit hit : results.getHits()) {
                hitUris.add(hit.getNode().getURI());
            }
            assertTrue(hitUris.contains("http://example.org/doc1"));
            assertTrue(hitUris.contains("http://example.org/doc2"));
            assertFalse(hitUris.contains("http://example.org/doc3"));

            // Verify facets only reflect matched documents
            List<FacetValue> categoryFacets = results.getFacetsForField(CATEGORY_FIELD);
            assertEquals(1, categoryFacets.size());
            assertEquals("tech", categoryFacets.get(0).getValue());
            assertEquals(2, categoryFacets.get(0).getCount());
        }
    }

    @Test
    public void testFacetValuesSortedByCountDescending() throws ParseException, IOException {
        // Index documents with varying category counts
        addDocument("http://example.org/doc1", "learning A", "rare", "Author");
        addDocument("http://example.org/doc2", "learning B", "common", "Author");
        addDocument("http://example.org/doc3", "learning C", "common", "Author");
        addDocument("http://example.org/doc4", "learning D", "common", "Author");
        addDocument("http://example.org/doc5", "learning E", "medium", "Author");
        addDocument("http://example.org/doc6", "learning F", "medium", "Author");

        textIndex.commit();

        try (IndexReader indexReader = DirectoryReader.open(directory)) {
            List<Resource> props = Collections.singletonList(
                ResourceFactory.createProperty(RDFS.label.getURI())
            );
            List<String> facetFields = Collections.singletonList(CATEGORY_FIELD);

            FacetedTextResults results = textIndex.queryWithFacets$(
                indexReader,
                props,
                "learning",
                UnaryOperator.identity(),
                null,
                facetFields,
                10
            );

            List<FacetValue> facets = results.getFacetsForField(CATEGORY_FIELD);
            assertEquals(3, facets.size());

            // Verify descending order by count
            assertEquals("common", facets.get(0).getValue());
            assertEquals(3, facets.get(0).getCount());

            assertEquals("medium", facets.get(1).getValue());
            assertEquals(2, facets.get(1).getCount());

            assertEquals("rare", facets.get(2).getValue());
            assertEquals(1, facets.get(2).getCount());
        }
    }
}
