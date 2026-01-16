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

import java.util.ArrayList;
import java.util.List;

import org.apache.jena.atlas.logging.Log;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.QueryBuildException;
import org.apache.jena.query.QueryExecException;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Substitute;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;
import org.apache.jena.sparql.engine.iterator.QueryIterPlainWrapper;
import org.apache.jena.sparql.pfunction.PropFuncArg;
import org.apache.jena.sparql.pfunction.PropertyFunctionBase;
import org.apache.jena.sparql.util.IterLib;
import org.apache.jena.sparql.util.NodeFactoryExtra;
import org.apache.jena.sparql.util.Symbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPARQL property function for faceted text search.
 * <p>
 * Usage:
 * <pre>
 * PREFIX text: &lt;http://jena.apache.org/text#&gt;
 *
 * SELECT ?s ?score ?facetField ?facetValue ?facetCount
 * WHERE {
 *   (?s ?score ?facetField ?facetValue ?facetCount) text:queryWithFacets
 *       (rdfs:label "search terms" "category" "author" 10)
 * }
 * </pre>
 * <p>
 * The object list contains:
 * <ol>
 *   <li>Optional: One or more property URIs to search</li>
 *   <li>Required: The query string (literal)</li>
 *   <li>Optional: Facet field names (strings)</li>
 *   <li>Optional: Max facet values per field (integer, default 10)</li>
 * </ol>
 * <p>
 * Returns bindings for each combination of hit and facet value:
 * <ul>
 *   <li>?s - The matched subject URI</li>
 *   <li>?score - The match score (float)</li>
 *   <li>?facetField - The facet field name</li>
 *   <li>?facetValue - A facet value</li>
 *   <li>?facetCount - The count for this facet value</li>
 * </ul>
 */
public class TextQueryFacetsPF extends PropertyFunctionBase {
    private static Logger log = LoggerFactory.getLogger(TextQueryFacetsPF.class);

    /** Symbol for storing faceted results in the execution context */
    public static final Symbol FACETED_RESULTS = Symbol.create(TextQuery.NS + "facetedResults");

    private TextIndex textIndex = null;
    private boolean warningIssued = false;

    public TextQueryFacetsPF() {}

    @Override
    public void build(PropFuncArg argSubject, Node predicate, PropFuncArg argObject, ExecutionContext execCxt) {
        super.build(argSubject, predicate, argObject, execCxt);
        DatasetGraph dsg = execCxt.getDataset();
        textIndex = chooseTextIndex(execCxt, dsg);

        if (argSubject.isList()) {
            int size = argSubject.getArgListSize();
            if (size < 1 || size > 5) {
                throw new QueryBuildException("Subject must have 1-5 elements: " + argSubject);
            }
        }

        if (argObject.isList()) {
            List<Node> list = argObject.getArgList();
            if (list.size() == 0) {
                throw new QueryBuildException("Object list cannot be empty");
            }
        }
    }

    private static TextIndex chooseTextIndex(ExecutionContext execCxt, DatasetGraph dsg) {
        Object obj = execCxt.getContext().get(TextQuery.textIndex);
        if (obj instanceof TextIndex) {
            return (TextIndex) obj;
        }
        if (obj != null) {
            Log.warn(TextQueryFacetsPF.class, "Context setting '" + TextQuery.textIndex + "' is not a TextIndex");
        }
        if (dsg instanceof DatasetGraphText) {
            return ((DatasetGraphText) dsg).getTextIndex();
        }
        Log.warn(TextQueryFacetsPF.class, "Failed to find the text index");
        return null;
    }

    @Override
    public QueryIterator exec(Binding binding,
                              PropFuncArg argSubject, Node predicate, PropFuncArg argObject,
                              ExecutionContext execCxt) {
        if (textIndex == null) {
            if (!warningIssued) {
                Log.warn(getClass(), "No text index - no faceted search performed");
                warningIssued = true;
            }
            return IterLib.result(binding, execCxt);
        }

        argSubject = Substitute.substitute(argSubject, binding);
        argObject = Substitute.substitute(argObject, binding);

        // Parse subject variables
        Node s = null;
        Node score = null;
        Node facetField = null;
        Node facetValue = null;
        Node facetCount = null;

        if (argSubject.isList()) {
            List<Node> subjList = argSubject.getArgList();
            s = subjList.get(0);

            if (subjList.size() > 1) {
                score = subjList.get(1);
                if (!score.isVariable()) {
                    throw new QueryExecException("Score must be a variable: " + argSubject);
                }
            }
            if (subjList.size() > 2) {
                facetField = subjList.get(2);
                if (!facetField.isVariable()) {
                    throw new QueryExecException("Facet field must be a variable: " + argSubject);
                }
            }
            if (subjList.size() > 3) {
                facetValue = subjList.get(3);
                if (!facetValue.isVariable()) {
                    throw new QueryExecException("Facet value must be a variable: " + argSubject);
                }
            }
            if (subjList.size() > 4) {
                facetCount = subjList.get(4);
                if (!facetCount.isVariable()) {
                    throw new QueryExecException("Facet count must be a variable: " + argSubject);
                }
            }
        } else {
            s = argSubject.getArg();
        }

        if (s.isLiteral()) {
            return IterLib.noResults(execCxt);
        }

        // Parse object arguments
        FacetedQueryParams params = parseObjectArgs(argObject);
        if (params == null) {
            return IterLib.noResults(execCxt);
        }

        // Execute faceted query
        FacetedTextResults results;
        try {
            results = textIndex.queryWithFacets(
                params.props,
                params.queryString,
                null,  // graphURI
                null,  // lang
                10000, // limit
                params.facetFields,
                params.maxFacetValues
            );
        } catch (UnsupportedOperationException e) {
            Log.warn(getClass(), "Faceted queries not supported by this text index");
            return IterLib.noResults(execCxt);
        }

        // Store results in context for potential reuse
        execCxt.getContext().put(FACETED_RESULTS, results);

        // Generate bindings
        return generateBindings(binding, s, score, facetField, facetValue, facetCount, results, execCxt);
    }

    private QueryIterator generateBindings(Binding binding, Node subj, Node score,
            Node facetFieldNode, Node facetValueNode, Node facetCountNode,
            FacetedTextResults results, ExecutionContext execCxt) {

        Var sVar = Var.isVar(subj) ? Var.alloc(subj) : null;
        Var scoreVar = score != null ? Var.alloc(score) : null;
        Var facetFieldVar = facetFieldNode != null ? Var.alloc(facetFieldNode) : null;
        Var facetValueVar = facetValueNode != null ? Var.alloc(facetValueNode) : null;
        Var facetCountVar = facetCountNode != null ? Var.alloc(facetCountNode) : null;

        List<Binding> bindings = new ArrayList<>();

        // If no facet variables are requested, just return hits
        if (facetFieldVar == null) {
            for (TextHit hit : results.getHits()) {
                BindingBuilder builder = Binding.builder(binding);
                if (sVar != null) {
                    builder.add(sVar, hit.getNode());
                }
                if (scoreVar != null) {
                    builder.add(scoreVar, NodeFactoryExtra.floatToNode(hit.getScore()));
                }
                bindings.add(builder.build());
            }
        } else {
            // Return one binding per hit + facet combination
            for (TextHit hit : results.getHits()) {
                for (String fieldName : results.getFacets().keySet()) {
                    for (FacetValue fv : results.getFacetsForField(fieldName)) {
                        BindingBuilder builder = Binding.builder(binding);
                        if (sVar != null) {
                            builder.add(sVar, hit.getNode());
                        }
                        if (scoreVar != null) {
                            builder.add(scoreVar, NodeFactoryExtra.floatToNode(hit.getScore()));
                        }
                        if (facetFieldVar != null) {
                            builder.add(facetFieldVar, NodeFactory.createLiteralString(fieldName));
                        }
                        if (facetValueVar != null) {
                            builder.add(facetValueVar, NodeFactory.createLiteralString(fv.getValue()));
                        }
                        if (facetCountVar != null) {
                            builder.add(facetCountVar, NodeFactory.createLiteralDT(
                                String.valueOf(fv.getCount()), XSDDatatype.XSDlong));
                        }
                        bindings.add(builder.build());
                    }
                }
            }
        }

        return QueryIterPlainWrapper.create(bindings.iterator(), execCxt);
    }

    private FacetedQueryParams parseObjectArgs(PropFuncArg argObject) {
        List<Resource> props = new ArrayList<>();
        List<String> facetFields = new ArrayList<>();
        String queryString = null;
        int maxFacetValues = 10;

        if (argObject.isNode()) {
            Node o = argObject.getArg();
            if (!o.isLiteral()) {
                log.warn("Query argument must be a literal: " + o);
                return null;
            }
            queryString = o.getLiteralLexicalForm();
            return new FacetedQueryParams(props, queryString, facetFields, maxFacetValues);
        }

        List<Node> list = argObject.getArgList();
        int idx = 0;

        // Parse properties
        while (idx < list.size() && list.get(idx).isURI()) {
            Property prop = ResourceFactory.createProperty(list.get(idx).getURI());
            props.add(prop);
            idx++;
        }

        // Parse query string (required)
        if (idx >= list.size() || !list.get(idx).isLiteral()) {
            log.warn("Missing or invalid query string");
            return null;
        }
        queryString = list.get(idx).getLiteralLexicalForm();
        idx++;

        // Parse facet fields and max values
        while (idx < list.size()) {
            Node n = list.get(idx);
            if (n.isLiteral()) {
                String lexForm = n.getLiteralLexicalForm();
                // Check if it's a number (max facet values)
                try {
                    int val = Integer.parseInt(lexForm);
                    maxFacetValues = val;
                } catch (NumberFormatException e) {
                    // It's a facet field name
                    facetFields.add(lexForm);
                }
            }
            idx++;
        }

        return new FacetedQueryParams(props, queryString, facetFields, maxFacetValues);
    }

    private static class FacetedQueryParams {
        final List<Resource> props;
        final String queryString;
        final List<String> facetFields;
        final int maxFacetValues;

        FacetedQueryParams(List<Resource> props, String queryString,
                          List<String> facetFields, int maxFacetValues) {
            this.props = props;
            this.queryString = queryString;
            this.facetFields = facetFields;
            this.maxFacetValues = maxFacetValues;
        }
    }
}
