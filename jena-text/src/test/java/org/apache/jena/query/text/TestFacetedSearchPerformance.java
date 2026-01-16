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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performance benchmarks for faceted search functionality.
 * These tests measure the overhead of faceting compared to standard queries.
 */
public class TestFacetedSearchPerformance {

    private static final Logger log = LoggerFactory.getLogger(TestFacetedSearchPerformance.class);

    private static final String CATEGORY_FIELD = "category";
    private static final String AUTHOR_FIELD = "author";
    private static final String YEAR_FIELD = "year";
    private static final String TEXT_FIELD = "text";

    private static final String[] CATEGORIES = {"technology", "science", "business", "health", "sports", "entertainment", "politics", "education"};
    private static final String[] AUTHORS = {"Smith", "Jones", "Brown", "Wilson", "Taylor", "Anderson", "Thomas", "Jackson", "White", "Harris"};
    private static final String[] YEARS = {"2020", "2021", "2022", "2023", "2024"};

    private Directory directory;
    private TextIndexLucene textIndex;
    private EntityDefinition entityDef;
    private Random random;

    @Before
    public void setUp() {
        directory = new ByteBuffersDirectory();

        entityDef = new EntityDefinition("uri", TEXT_FIELD);
        entityDef.setPrimaryPredicate(RDFS.label);
        entityDef.set(CATEGORY_FIELD, ResourceFactory.createProperty("http://example.org/category").asNode());
        entityDef.set(AUTHOR_FIELD, ResourceFactory.createProperty("http://example.org/author").asNode());
        entityDef.set(YEAR_FIELD, ResourceFactory.createProperty("http://example.org/year").asNode());
        entityDef.setNoIndex(CATEGORY_FIELD, false);
        entityDef.setNoIndex(AUTHOR_FIELD, false);
        entityDef.setNoIndex(YEAR_FIELD, false);

        TextIndexConfig config = new TextIndexConfig(entityDef);
        config.setValueStored(true);

        textIndex = new TextIndexLucene(directory, config);
        random = new Random(42); // Fixed seed for reproducibility
    }

    @After
    public void tearDown() {
        if (textIndex != null) {
            textIndex.close();
        }
    }

    private void addDocument(String uri, String text, String category, String author, String year) {
        Entity entity = new Entity(uri, null);
        entity.put(TEXT_FIELD, text);
        entity.put(CATEGORY_FIELD, category);
        entity.put(AUTHOR_FIELD, author);
        entity.put(YEAR_FIELD, year);
        textIndex.addEntity(entity);
    }

    private void indexDocuments(int count) {
        for (int i = 0; i < count; i++) {
            String category = CATEGORIES[random.nextInt(CATEGORIES.length)];
            String author = AUTHORS[random.nextInt(AUTHORS.length)];
            String year = YEARS[random.nextInt(YEARS.length)];
            String text = "document about learning topic number " + i + " with various keywords";
            addDocument("http://example.org/doc" + i, text, category, author, year);
        }
        textIndex.commit();
    }

    /**
     * Test faceting performance with 100 documents.
     */
    @Test
    public void testPerformance100Docs() throws ParseException, IOException {
        runPerformanceTest(100, "100 docs");
    }

    /**
     * Test faceting performance with 1,000 documents.
     */
    @Test
    public void testPerformance1000Docs() throws ParseException, IOException {
        runPerformanceTest(1000, "1,000 docs");
    }

    /**
     * Test faceting performance with 5,000 documents.
     */
    @Test
    public void testPerformance5000Docs() throws ParseException, IOException {
        runPerformanceTest(5000, "5,000 docs");
    }

    private void runPerformanceTest(int docCount, String label) throws ParseException, IOException {
        // Index documents
        long indexStart = System.nanoTime();
        indexDocuments(docCount);
        long indexTime = (System.nanoTime() - indexStart) / 1_000_000;

        try (IndexReader indexReader = DirectoryReader.open(directory)) {
            List<Resource> props = Collections.singletonList(
                ResourceFactory.createProperty(RDFS.label.getURI())
            );

            // Warm-up queries
            for (int i = 0; i < 3; i++) {
                textIndex.queryWithFacets$(
                    indexReader, props, "learning",
                    UnaryOperator.identity(), null,
                    Arrays.asList(CATEGORY_FIELD, AUTHOR_FIELD, YEAR_FIELD), 10
                );
            }

            // Benchmark faceted query (multiple runs for accuracy)
            int runs = 10;
            long facetedTotalTime = 0;
            FacetedTextResults lastResults = null;

            for (int i = 0; i < runs; i++) {
                long start = System.nanoTime();
                lastResults = textIndex.queryWithFacets$(
                    indexReader, props, "learning",
                    UnaryOperator.identity(), null,
                    Arrays.asList(CATEGORY_FIELD, AUTHOR_FIELD, YEAR_FIELD), 10
                );
                facetedTotalTime += System.nanoTime() - start;
            }

            long avgFacetedTimeMs = facetedTotalTime / runs / 1_000_000;
            double avgFacetedTimeUs = (double) facetedTotalTime / runs / 1_000;

            // Verify results are valid
            assertNotNull(lastResults);
            assertTrue("Should have hits", lastResults.getTotalHits() > 0);
            assertEquals("Should have 3 facet fields", 3, lastResults.getFacets().size());

            // Log performance metrics
            log.info("=== Performance Test: {} ===", label);
            log.info("Index time: {} ms", indexTime);
            log.info("Total hits: {}", lastResults.getTotalHits());
            log.info("Avg faceted query time: {} ms ({} us)", avgFacetedTimeMs, String.format("%.2f", avgFacetedTimeUs));
            log.info("Facets collected: category={}, author={}, year={}",
                lastResults.getFacetsForField(CATEGORY_FIELD).size(),
                lastResults.getFacetsForField(AUTHOR_FIELD).size(),
                lastResults.getFacetsForField(YEAR_FIELD).size()
            );

            // Performance assertions (generous thresholds)
            // 100 docs should complete in < 100ms
            // 1000 docs should complete in < 200ms
            // 5000 docs should complete in < 500ms
            int expectedMaxMs = docCount <= 100 ? 100 : (docCount <= 1000 ? 200 : 500);
            assertTrue(
                String.format("Faceted query should complete within %dms for %s (actual: %dms)",
                    expectedMaxMs, label, avgFacetedTimeMs),
                avgFacetedTimeMs < expectedMaxMs
            );
        }
    }

    /**
     * Test that faceting overhead is reasonable compared to document count.
     */
    @Test
    public void testFacetingScalability() throws ParseException, IOException {
        int[] sizes = {100, 500, 1000, 2000};
        long[] times = new long[sizes.length];

        for (int i = 0; i < sizes.length; i++) {
            // Reset index for each test
            tearDown();
            setUp();

            indexDocuments(sizes[i]);

            try (IndexReader indexReader = DirectoryReader.open(directory)) {
                List<Resource> props = Collections.singletonList(
                    ResourceFactory.createProperty(RDFS.label.getURI())
                );

                // Warm-up
                textIndex.queryWithFacets$(
                    indexReader, props, "learning",
                    UnaryOperator.identity(), null,
                    Arrays.asList(CATEGORY_FIELD), 10
                );

                // Measure
                long start = System.nanoTime();
                FacetedTextResults results = textIndex.queryWithFacets$(
                    indexReader, props, "learning",
                    UnaryOperator.identity(), null,
                    Arrays.asList(CATEGORY_FIELD), 10
                );
                times[i] = System.nanoTime() - start;

                assertNotNull(results);
                assertTrue(results.getTotalHits() > 0);
            }
        }

        // Log scalability metrics
        log.info("=== Scalability Test ===");
        for (int i = 0; i < sizes.length; i++) {
            double timeMs = times[i] / 1_000_000.0;
            double timePerDoc = (double) times[i] / sizes[i] / 1_000; // microseconds per doc
            log.info("{} docs: {} ms ({} us/doc)", sizes[i], String.format("%.2f", timeMs), String.format("%.3f", timePerDoc));
        }

        // Verify sub-linear scaling (time shouldn't grow faster than O(n))
        // Allow 3x time for 20x documents (100 -> 2000)
        double ratio = (double) times[sizes.length - 1] / times[0];
        double docRatio = (double) sizes[sizes.length - 1] / sizes[0];
        assertTrue(
            String.format("Time should scale sub-linearly: %.1fx docs should not cause more than %.1fx time increase (actual: %.1fx)",
                docRatio, docRatio, ratio),
            ratio < docRatio * 1.5  // Allow 50% overhead margin
        );
    }

    /**
     * Test performance with varying number of facet fields.
     */
    @Test
    public void testFacetFieldCountImpact() throws ParseException, IOException {
        indexDocuments(1000);

        try (IndexReader indexReader = DirectoryReader.open(directory)) {
            List<Resource> props = Collections.singletonList(
                ResourceFactory.createProperty(RDFS.label.getURI())
            );

            // Test with 1, 2, and 3 facet fields
            List<List<String>> facetFieldConfigs = Arrays.asList(
                Arrays.asList(CATEGORY_FIELD),
                Arrays.asList(CATEGORY_FIELD, AUTHOR_FIELD),
                Arrays.asList(CATEGORY_FIELD, AUTHOR_FIELD, YEAR_FIELD)
            );

            long[] times = new long[facetFieldConfigs.size()];

            for (int i = 0; i < facetFieldConfigs.size(); i++) {
                List<String> facetFields = facetFieldConfigs.get(i);

                // Warm-up
                textIndex.queryWithFacets$(
                    indexReader, props, "learning",
                    UnaryOperator.identity(), null,
                    facetFields, 10
                );

                // Measure (average of 5 runs)
                long total = 0;
                for (int run = 0; run < 5; run++) {
                    long start = System.nanoTime();
                    textIndex.queryWithFacets$(
                        indexReader, props, "learning",
                        UnaryOperator.identity(), null,
                        facetFields, 10
                    );
                    total += System.nanoTime() - start;
                }
                times[i] = total / 5;
            }

            // Log impact metrics
            log.info("=== Facet Field Count Impact (1000 docs) ===");
            for (int i = 0; i < times.length; i++) {
                double timeMs = times[i] / 1_000_000.0;
                log.info("{} facet field(s): {} ms", i + 1, String.format("%.2f", timeMs));
            }

            // Verify adding fields doesn't cause excessive slowdown
            // 3 fields should take no more than 3x the time of 1 field
            double ratio = (double) times[2] / times[0];
            assertTrue(
                String.format("3 facet fields should not be more than 3x slower than 1 field (actual: %.2fx)", ratio),
                ratio < 3.0
            );
        }
    }
}
