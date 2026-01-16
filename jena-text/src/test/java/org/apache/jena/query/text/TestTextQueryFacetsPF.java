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

import java.io.Reader;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

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
 * Integration tests for the text:queryWithFacets SPARQL property function.
 */
public class TestTextQueryFacetsPF {

    private static final String SPEC_BASE = "http://example.org/spec#";
    private static final String SPEC_ROOT_LOCAL = "faceted_text_dataset";
    private static final String SPEC_ROOT_URI = SPEC_BASE + SPEC_ROOT_LOCAL;

    private static final String SPEC;
    static {
        SPEC = StrUtils.strjoinNL(
            "prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> ",
            "prefix ja:   <http://jena.hpl.hp.com/2005/11/Assembler#> ",
            "prefix text: <http://jena.apache.org/text#>",
            "prefix :     <" + SPEC_BASE + ">",
            "",
            "[] ja:loadClass \"org.apache.jena.query.text.TextQuery\" .",
            "text:TextDataset      rdfs:subClassOf ja:RDFDataset .",
            "text:TextIndexLucene  rdfs:subClassOf text:TextIndex .",
            "",
            ":" + SPEC_ROOT_LOCAL,
            "    a              text:TextDataset ;",
            "    text:dataset   :dataset ;",
            "    text:index     :indexLucene ;",
            "    .",
            "",
            ":dataset",
            "    a               ja:RDFDataset ;",
            "    ja:defaultGraph :graph ;",
            ".",
            ":graph",
            "    a               ja:MemoryModel ;",
            ".",
            "",
            ":indexLucene",
            "    a text:TextIndexLucene ;",
            "    text:directory \"mem\" ;",
            "    text:storeValues true ;",
            "    text:entityMap :entMap ;",
            "    .",
            "",
            ":entMap",
            "    a text:EntityMap ;",
            "    text:entityField      \"uri\" ;",
            "    text:defaultField     \"label\" ;",
            "    text:map (",
            "         [ text:field \"label\" ; text:predicate rdfs:label ]",
            "         [ text:field \"category\" ; text:predicate <http://example.org/category> ]",
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
    public void before() {
        Reader reader = new StringReader(SPEC);
        Model specModel = ModelFactory.createDefaultModel();
        specModel.read(reader, "", "TURTLE");
        TextAssembler.init();
        Resource root = specModel.getResource(SPEC_ROOT_URI);
        dataset = (Dataset) Assembler.general().open(root);
    }

    @After
    public void after() {
        if (dataset != null) {
            dataset.close();
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
    public void testBasicFacetedQuery() {
        // Load test data
        String turtle = StrUtils.strjoinNL(
            "ex:doc1 rdfs:label \"machine learning algorithms\" ; ex:category \"technology\" .",
            "ex:doc2 rdfs:label \"deep learning networks\" ; ex:category \"technology\" .",
            "ex:doc3 rdfs:label \"learning to cook\" ; ex:category \"cooking\" ."
        );
        loadData(turtle);

        // Query with facets
        String queryString = StrUtils.strjoinNL(
            QUERY_PROLOG,
            "SELECT ?s ?score",
            "WHERE {",
            "    (?s ?score) text:queryWithFacets (\"learning\") .",
            "}"
        );

        dataset.begin(ReadWrite.READ);
        try {
            Query query = QueryFactory.create(queryString);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qexec.execSelect();
                int count = 0;
                Set<String> foundUris = new HashSet<>();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    foundUris.add(sol.getResource("s").getURI());
                    count++;
                }
                assertEquals(3, count);
                assertTrue(foundUris.contains("http://example.org/doc1"));
                assertTrue(foundUris.contains("http://example.org/doc2"));
                assertTrue(foundUris.contains("http://example.org/doc3"));
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testFacetedQueryMultipleHits() {
        // Load test data with multiple matching documents
        String turtle = StrUtils.strjoinNL(
            "ex:doc1 rdfs:label \"machine learning algorithms\" .",
            "ex:doc2 rdfs:label \"deep learning networks\" .",
            "ex:doc3 rdfs:label \"supervised learning methods\" .",
            "ex:doc4 rdfs:label \"unsupervised learning techniques\" ."
        );
        loadData(turtle);

        // Query with score variable
        String queryString = StrUtils.strjoinNL(
            QUERY_PROLOG,
            "SELECT ?s ?score",
            "WHERE {",
            "    (?s ?score) text:queryWithFacets (\"learning\") .",
            "}",
            "ORDER BY DESC(?score)"
        );

        dataset.begin(ReadWrite.READ);
        try {
            Query query = QueryFactory.create(queryString);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qexec.execSelect();
                int count = 0;
                float lastScore = Float.MAX_VALUE;
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    assertNotNull(sol.getResource("s"));
                    float score = sol.getLiteral("score").getFloat();
                    assertTrue("Scores should be in descending order", score <= lastScore);
                    lastScore = score;
                    count++;
                }
                assertEquals("Should find all 4 documents", 4, count);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testFacetedQueryNoResults() {
        // Load minimal data
        String turtle = "ex:doc1 rdfs:label \"hello world\" .";
        loadData(turtle);

        // Query for something that doesn't match
        String queryString = StrUtils.strjoinNL(
            QUERY_PROLOG,
            "SELECT ?s",
            "WHERE {",
            "    (?s) text:queryWithFacets (\"nonexistent\") .",
            "}"
        );

        dataset.begin(ReadWrite.READ);
        try {
            Query query = QueryFactory.create(queryString);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qexec.execSelect();
                assertFalse("Should have no results", rs.hasNext());
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testFacetedQueryWithProperty() {
        // Load test data
        String turtle = StrUtils.strjoinNL(
            "ex:doc1 rdfs:label \"java programming\" .",
            "ex:doc2 rdfs:label \"python programming\" ."
        );
        loadData(turtle);

        // Query with specific property
        String queryString = StrUtils.strjoinNL(
            QUERY_PROLOG,
            "SELECT ?s ?score",
            "WHERE {",
            "    (?s ?score) text:queryWithFacets (rdfs:label \"programming\") .",
            "}"
        );

        dataset.begin(ReadWrite.READ);
        try {
            Query query = QueryFactory.create(queryString);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qexec.execSelect();
                int count = 0;
                while (rs.hasNext()) {
                    rs.next();
                    count++;
                }
                assertEquals(2, count);
            }
        } finally {
            dataset.end();
        }
    }
}
