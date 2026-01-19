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

import java.io.File;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.atlas.lib.StrUtils;
import org.apache.jena.query.*;
import org.apache.jena.query.text.assembler.TextAssembler;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the text:facetCounts SPARQL property function.
 */
public class TestTextFacetCountsPF {

    private static final String SPEC_BASE = "http://example.org/spec#";

    // Use unique temp directory per test for complete isolation
    private Path tempDir;
    private String specRootLocal;
    private String specRootUri;

    private String createSpec() {
        return StrUtils.strjoinNL(
            "prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> ",
            "prefix ja:   <http://jena.hpl.hp.com/2005/11/Assembler#> ",
            "prefix text: <http://jena.apache.org/text#>",
            "prefix :     <" + SPEC_BASE + ">",
            "",
            "[] ja:loadClass \"org.apache.jena.query.text.TextQuery\" .",
            "text:TextDataset      rdfs:subClassOf ja:RDFDataset .",
            "text:TextIndexLucene  rdfs:subClassOf text:TextIndex .",
            "",
            ":" + specRootLocal,
            "    a              text:TextDataset ;",
            "    text:dataset   :dataset_" + specRootLocal + " ;",
            "    text:index     :indexLucene_" + specRootLocal + " ;",
            "    .",
            "",
            ":dataset_" + specRootLocal,
            "    a               ja:RDFDataset ;",
            "    ja:defaultGraph :graph_" + specRootLocal + " ;",
            ".",
            ":graph_" + specRootLocal,
            "    a               ja:MemoryModel ;",
            ".",
            "",
            ":indexLucene_" + specRootLocal,
            "    a text:TextIndexLucene ;",
            "    text:directory \"" + tempDir.toAbsolutePath().toString().replace("\\", "/") + "\" ;",
            "    text:storeValues true ;",
            "    text:facetFields (\"category\" \"author\") ;",
            "    text:entityMap :entMap_" + specRootLocal + " ;",
            "    .",
            "",
            ":entMap_" + specRootLocal,
            "    a text:EntityMap ;",
            "    text:entityField      \"uri\" ;",
            "    text:defaultField     \"label\" ;",
            "    text:map (",
            "         [ text:field \"label\" ; text:predicate rdfs:label ]",
            "         [ text:field \"category\" ; text:predicate <http://example.org/category> ]",
            "         [ text:field \"author\" ; text:predicate <http://example.org/author> ]",
            "         ) ."
        );
    }

    private static final String QUERY_PROLOG = StrUtils.strjoinNL(
        "PREFIX text: <http://jena.apache.org/text#>",
        "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>",
        "PREFIX ex: <http://example.org/>"
    );

    private static final String TURTLE_PROLOG = StrUtils.strjoinNL(
        "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .",
        "@prefix ex: <http://example.org/> ."
    );

    private Dataset dataset;

    @Before
    public void before() throws Exception {
        // Create unique temp directory for complete test isolation
        tempDir = Files.createTempDirectory("jena-text-facet-test-");
        specRootLocal = "facet_text_dataset_" + System.nanoTime();
        specRootUri = SPEC_BASE + specRootLocal;

        String spec = createSpec();
        Reader reader = new StringReader(spec);
        Model specModel = ModelFactory.createDefaultModel();
        specModel.read(reader, "", "TURTLE");
        TextAssembler.init();
        Resource root = specModel.getResource(specRootUri);
        dataset = (Dataset) Assembler.general().open(root);
    }

    @After
    public void after() throws Exception {
        if (dataset != null) {
            dataset.close();
        }
        // Clean up temp directory
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }

    private void loadData(String turtle) {
        Model model = dataset.getDefaultModel();
        Reader reader = new StringReader(TURTLE_PROLOG + "\n" + turtle);
        dataset.begin(ReadWrite.WRITE);
        model.read(reader, "", "TURTLE");
        dataset.commit();
    }

    @Test
    public void testBasicFacetCounts() {
        String turtle = StrUtils.strjoinNL(
            "ex:doc1 rdfs:label \"Machine Learning Intro\" ; ex:category \"technology\" ; ex:author \"Smith\" .",
            "ex:doc2 rdfs:label \"Deep Learning\" ; ex:category \"technology\" ; ex:author \"Jones\" .",
            "ex:doc3 rdfs:label \"Learning to Cook\" ; ex:category \"cooking\" ; ex:author \"Smith\" ."
        );
        loadData(turtle);

        String queryString = StrUtils.strjoinNL(
            QUERY_PROLOG,
            "SELECT ?field ?value ?count",
            "WHERE {",
            "    (?field ?value ?count) text:facetCounts (\"category\" 10) .",
            "}"
        );

        dataset.begin(ReadWrite.READ);
        try {
            Query query = QueryFactory.create(queryString);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qexec.execSelect();
                Map<String, Long> counts = new HashMap<>();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    String value = sol.getLiteral("value").getString();
                    long count = sol.getLiteral("count").getLong();
                    counts.put(value, count);
                }
                assertEquals(2, counts.size());
                assertEquals(Long.valueOf(2), counts.get("technology"));
                assertEquals(Long.valueOf(1), counts.get("cooking"));
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testMultipleFacetFields() {
        String turtle = StrUtils.strjoinNL(
            "ex:doc1 rdfs:label \"Doc One\" ; ex:category \"tech\" ; ex:author \"Alice\" .",
            "ex:doc2 rdfs:label \"Doc Two\" ; ex:category \"tech\" ; ex:author \"Bob\" .",
            "ex:doc3 rdfs:label \"Doc Three\" ; ex:category \"science\" ; ex:author \"Alice\" ."
        );
        loadData(turtle);

        String queryString = StrUtils.strjoinNL(
            QUERY_PROLOG,
            "SELECT ?field ?value ?count",
            "WHERE {",
            "    (?field ?value ?count) text:facetCounts (\"category\" \"author\" 10) .",
            "}",
            "ORDER BY ?field DESC(?count)"
        );

        dataset.begin(ReadWrite.READ);
        try {
            Query query = QueryFactory.create(queryString);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qexec.execSelect();
                int authorCount = 0;
                int categoryCount = 0;
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    String field = sol.getLiteral("field").getString();
                    if ("author".equals(field)) authorCount++;
                    if ("category".equals(field)) categoryCount++;
                }
                assertTrue("Should have author facets", authorCount >= 2);
                assertTrue("Should have category facets", categoryCount >= 2);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testOpenFacetsNoQuery() {
        // Test open facets (no search query filter)
        String turtle = StrUtils.strjoinNL(
            "ex:doc1 rdfs:label \"Machine Learning Basics\" ; ex:category \"technology\" .",
            "ex:doc2 rdfs:label \"Machine Learning Advanced\" ; ex:category \"technology\" .",
            "ex:doc3 rdfs:label \"Cooking for Beginners\" ; ex:category \"cooking\" ."
        );
        loadData(turtle);

        // Get all facet counts without filtering
        String queryString = StrUtils.strjoinNL(
            QUERY_PROLOG,
            "SELECT ?field ?value ?count",
            "WHERE {",
            "    (?field ?value ?count) text:facetCounts (\"category\" 10) .",
            "}"
        );

        dataset.begin(ReadWrite.READ);
        try {
            Query query = QueryFactory.create(queryString);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qexec.execSelect();
                Map<String, Long> counts = new HashMap<>();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    String value = sol.getLiteral("value").getString();
                    long count = sol.getLiteral("count").getLong();
                    counts.put(value, count);
                }
                // Should have both categories
                assertEquals(Long.valueOf(2), counts.get("technology"));
                assertEquals(Long.valueOf(1), counts.get("cooking"));
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testFacetCountsDescendingOrder() {
        String turtle = StrUtils.strjoinNL(
            "ex:doc1 rdfs:label \"Doc A\" ; ex:category \"alpha\" .",
            "ex:doc2 rdfs:label \"Doc B\" ; ex:category \"beta\" .",
            "ex:doc3 rdfs:label \"Doc C\" ; ex:category \"beta\" .",
            "ex:doc4 rdfs:label \"Doc D\" ; ex:category \"beta\" ."
        );
        loadData(turtle);

        String queryString = StrUtils.strjoinNL(
            QUERY_PROLOG,
            "SELECT ?field ?value ?count",
            "WHERE {",
            "    (?field ?value ?count) text:facetCounts (\"category\" 10) .",
            "}",
            "ORDER BY DESC(?count)"
        );

        dataset.begin(ReadWrite.READ);
        try {
            Query query = QueryFactory.create(queryString);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qexec.execSelect();
                assertTrue("Should have results", rs.hasNext());
                QuerySolution first = rs.next();
                // First result should be beta with count 3
                assertEquals("beta", first.getLiteral("value").getString());
                assertEquals(3, first.getLiteral("count").getLong());
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testFilteredFacetCountsMultiWord() {
        // Test filtered facets with a multi-word query
        String turtle = StrUtils.strjoinNL(
            "ex:doc1 rdfs:label \"Machine Learning Intro\" ; ex:category \"technology\" ; ex:author \"Smith\" .",
            "ex:doc2 rdfs:label \"Deep Learning Networks\" ; ex:category \"technology\" ; ex:author \"Jones\" .",
            "ex:doc3 rdfs:label \"Learning to Cook\" ; ex:category \"cooking\" ; ex:author \"Smith\" .",
            "ex:doc4 rdfs:label \"Machine Learning Advanced\" ; ex:category \"technology\" ; ex:author \"Brown\" ."
        );
        loadData(turtle);

        // Query for "machine AND learning" - should only count technology docs with both words
        // Note: default QueryParser uses OR, so we use explicit AND
        String queryString = StrUtils.strjoinNL(
            QUERY_PROLOG,
            "SELECT ?field ?value ?count",
            "WHERE {",
            "    (?field ?value ?count) text:facetCounts (\"machine AND learning\" \"category\" 10) .",
            "}"
        );

        dataset.begin(ReadWrite.READ);
        try {
            Query query = QueryFactory.create(queryString);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qexec.execSelect();
                Map<String, Long> counts = new HashMap<>();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    String value = sol.getLiteral("value").getString();
                    long count = sol.getLiteral("count").getLong();
                    counts.put(value, count);
                }
                // Only docs 1 and 4 contain "machine learning"
                assertEquals(Long.valueOf(2), counts.get("technology"));
                assertNull("cooking should not appear", counts.get("cooking"));
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testFilteredFacetCountsSingleWord() {
        // Test filtered facets with a single-word query (non-facet-field word)
        String turtle = StrUtils.strjoinNL(
            "ex:doc1 rdfs:label \"Machine Learning Intro\" ; ex:category \"technology\" ; ex:author \"Smith\" .",
            "ex:doc2 rdfs:label \"Deep Networks\" ; ex:category \"technology\" ; ex:author \"Jones\" .",
            "ex:doc3 rdfs:label \"Learning to Cook\" ; ex:category \"cooking\" ; ex:author \"Smith\" .",
            "ex:doc4 rdfs:label \"Quantum Physics\" ; ex:category \"science\" ; ex:author \"Wilson\" ."
        );
        loadData(turtle);

        // First verify open facets work
        String openQueryString = StrUtils.strjoinNL(
            QUERY_PROLOG,
            "SELECT ?field ?value ?count",
            "WHERE {",
            "    (?field ?value ?count) text:facetCounts (\"category\" 10) .",
            "}"
        );

        dataset.begin(ReadWrite.READ);
        try {
            Query openQuery = QueryFactory.create(openQueryString);
            try (QueryExecution qexec = QueryExecutionFactory.create(openQuery, dataset)) {
                ResultSet rs = qexec.execSelect();
                Map<String, Long> openCounts = new HashMap<>();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    String value = sol.getLiteral("value").getString();
                    long count = sol.getLiteral("count").getLong();
                    openCounts.put(value, count);
                }
                System.err.println("Open facet counts: " + openCounts);
                // Should have 2 technology, 1 cooking, 1 science
                assertEquals("Open facets: technology", Long.valueOf(2), openCounts.get("technology"));
            }
        } finally {
            dataset.end();
        }

        // Now test filtered facets
        String queryString = StrUtils.strjoinNL(
            QUERY_PROLOG,
            "SELECT ?field ?value ?count",
            "WHERE {",
            "    (?field ?value ?count) text:facetCounts (\"learning\" \"category\" 10) .",
            "}"
        );

        dataset.begin(ReadWrite.READ);
        try {
            Query query = QueryFactory.create(queryString);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qexec.execSelect();
                Map<String, Long> counts = new HashMap<>();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    String value = sol.getLiteral("value").getString();
                    long count = sol.getLiteral("count").getLong();
                    counts.put(value, count);
                }
                System.err.println("Filtered facet counts for 'learning': " + counts);
                // Docs with "learning": doc1 (technology), doc3 (cooking)
                assertEquals(Long.valueOf(1), counts.get("technology"));
                assertEquals(Long.valueOf(1), counts.get("cooking"));
                assertNull("science should not appear", counts.get("science"));
            }
        } finally {
            dataset.end();
        }
    }
}
