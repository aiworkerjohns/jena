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
import java.util.Map;

import org.apache.jena.atlas.logging.Log;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.QueryBuildException;
import org.apache.jena.query.QueryExecException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPARQL property function for native Lucene facet counts.
 * <p>
 * This property function provides efficient facet counting using Lucene's native
 * SortedSetDocValues faceting. It does NOT iterate through documents - counts are
 * computed directly from the index structure.
 * <p>
 * <b>Syntax:</b>
 * <pre>
 * (?field ?value ?count) text:facetCounts (query? field1 field2 ... maxValues?)
 * </pre>
 * <p>
 * <b>Usage Examples:</b>
 * <pre>
 * PREFIX text: &lt;http://jena.apache.org/text#&gt;
 *
 * # Open facets - counts for all indexed documents
 * SELECT ?field ?value ?count WHERE {
 *   (?field ?value ?count) text:facetCounts ("category" "author" 10)
 * }
 *
 * # Filtered facets - counts only for documents matching "machine learning"
 * SELECT ?field ?value ?count WHERE {
 *   (?field ?value ?count) text:facetCounts ("machine learning" "category" "author" 10)
 * }
 *
 * # Single-word query filter (detected as query if not a configured facet field)
 * SELECT ?field ?value ?count WHERE {
 *   (?field ?value ?count) text:facetCounts ("learning" "category" 10)
 * }
 * </pre>
 * <p>
 * <b>Arguments:</b>
 * <ol>
 *   <li><b>query</b> (optional): A Lucene query string to filter documents.
 *       Detected automatically: if the first argument is not a configured facet field name
 *       and not a number, it is treated as a query.</li>
 *   <li><b>field1, field2, ...</b> (required): One or more facet field names (must be
 *       configured in text:facetFields)</li>
 *   <li><b>maxValues</b> (optional): Maximum facet values per field (integer, default 10)</li>
 * </ol>
 * <p>
 * <b>Returns:</b> Bindings for each facet value with:
 * <ul>
 *   <li>?field - The facet field name (xsd:string)</li>
 *   <li>?value - A facet value (xsd:string)</li>
 *   <li>?count - Number of documents with this value (xsd:long)</li>
 * </ul>
 */
public class TextFacetCountsPF extends PropertyFunctionBase {
    private static Logger log = LoggerFactory.getLogger(TextFacetCountsPF.class);

    private TextIndexLucene textIndex = null;
    private boolean warningIssued = false;

    public TextFacetCountsPF() {}

    @Override
    public void build(PropFuncArg argSubject, Node predicate, PropFuncArg argObject, ExecutionContext execCxt) {
        super.build(argSubject, predicate, argObject, execCxt);
        DatasetGraph dsg = execCxt.getDataset();
        textIndex = chooseTextIndex(execCxt, dsg);

        if (argSubject.isList()) {
            int size = argSubject.getArgListSize();
            if (size < 1 || size > 3) {
                throw new QueryBuildException("Subject must have 1-3 elements (field, value, count): " + argSubject);
            }
        }

        if (argObject.isList()) {
            List<Node> list = argObject.getArgList();
            if (list.isEmpty()) {
                throw new QueryBuildException("Object list must contain at least one facet field name");
            }
        } else if (!argObject.getArg().isLiteral()) {
            throw new QueryBuildException("Object must be a literal facet field name or a list");
        }
    }

    private static TextIndexLucene chooseTextIndex(ExecutionContext execCxt, DatasetGraph dsg) {
        Object obj = execCxt.getContext().get(TextQuery.textIndex);
        if (obj instanceof TextIndexLucene) {
            return (TextIndexLucene) obj;
        }
        if (obj != null) {
            Log.warn(TextFacetCountsPF.class, "Context setting '" + TextQuery.textIndex + "' is not a TextIndexLucene");
        }
        if (dsg instanceof DatasetGraphText) {
            TextIndex ti = ((DatasetGraphText) dsg).getTextIndex();
            if (ti instanceof TextIndexLucene) {
                return (TextIndexLucene) ti;
            }
            Log.warn(TextFacetCountsPF.class, "TextIndex is not a TextIndexLucene - native faceting not supported");
        }
        Log.warn(TextFacetCountsPF.class, "Failed to find the text index");
        return null;
    }

    @Override
    public QueryIterator exec(Binding binding,
                              PropFuncArg argSubject, Node predicate, PropFuncArg argObject,
                              ExecutionContext execCxt) {
        if (textIndex == null) {
            if (!warningIssued) {
                Log.warn(getClass(), "No text index - no facet counts available");
                warningIssued = true;
            }
            return IterLib.noResults(execCxt);
        }

        if (!textIndex.isFacetingEnabled()) {
            Log.warn(getClass(), "Faceting is not enabled on this text index. Configure facet fields in the index definition.");
            return IterLib.noResults(execCxt);
        }

        argSubject = Substitute.substitute(argSubject, binding);
        argObject = Substitute.substitute(argObject, binding);

        // Parse subject variables
        Node fieldNode = null;
        Node valueNode = null;
        Node countNode = null;

        if (argSubject.isList()) {
            List<Node> subjList = argSubject.getArgList();
            fieldNode = subjList.get(0);
            if (!fieldNode.isVariable()) {
                throw new QueryExecException("Field must be a variable: " + argSubject);
            }
            if (subjList.size() > 1) {
                valueNode = subjList.get(1);
                if (!valueNode.isVariable()) {
                    throw new QueryExecException("Value must be a variable: " + argSubject);
                }
            }
            if (subjList.size() > 2) {
                countNode = subjList.get(2);
                if (!countNode.isVariable()) {
                    throw new QueryExecException("Count must be a variable: " + argSubject);
                }
            }
        } else {
            fieldNode = argSubject.getArg();
            if (!fieldNode.isVariable()) {
                throw new QueryExecException("Subject must be a variable: " + argSubject);
            }
        }

        // Parse object arguments
        FacetCountParams params = parseObjectArgs(argObject);
        if (params == null || params.facetFields.isEmpty()) {
            return IterLib.noResults(execCxt);
        }

        // Get facet counts from the index
        Map<String, List<FacetValue>> facetCounts;
        try {
            facetCounts = textIndex.getFacetCounts(params.queryString, params.facetFields, params.maxValues);
        } catch (Exception e) {
            log.error("Error getting facet counts: {}", e.getMessage());
            return IterLib.noResults(execCxt);
        }

        // Generate bindings
        return generateBindings(binding, fieldNode, valueNode, countNode, facetCounts, execCxt);
    }

    private QueryIterator generateBindings(Binding binding, Node fieldNode, Node valueNode, Node countNode,
            Map<String, List<FacetValue>> facetCounts, ExecutionContext execCxt) {

        Var fieldVar = Var.isVar(fieldNode) ? Var.alloc(fieldNode) : null;
        Var valueVar = valueNode != null ? Var.alloc(valueNode) : null;
        Var countVar = countNode != null ? Var.alloc(countNode) : null;

        List<Binding> bindings = new ArrayList<>();

        for (Map.Entry<String, List<FacetValue>> entry : facetCounts.entrySet()) {
            String field = entry.getKey();
            for (FacetValue fv : entry.getValue()) {
                BindingBuilder builder = Binding.builder(binding);
                if (fieldVar != null) {
                    builder.add(fieldVar, NodeFactory.createLiteralString(field));
                }
                if (valueVar != null) {
                    builder.add(valueVar, NodeFactory.createLiteralString(fv.getValue()));
                }
                if (countVar != null) {
                    builder.add(countVar, NodeFactory.createLiteralDT(
                        String.valueOf(fv.getCount()), XSDDatatype.XSDlong));
                }
                bindings.add(builder.build());
            }
        }

        return QueryIterPlainWrapper.create(bindings.iterator(), execCxt);
    }

    private FacetCountParams parseObjectArgs(PropFuncArg argObject) {
        List<String> facetFields = new ArrayList<>();
        String queryString = null;
        int maxValues = 10;

        // Get configured facet fields for detection
        List<String> configuredFacetFields = textIndex != null ?
            textIndex.getFacetFields() : new ArrayList<>();

        if (argObject.isNode()) {
            Node o = argObject.getArg();
            if (!o.isLiteral()) {
                log.warn("Facet field name must be a literal: " + o);
                return null;
            }
            facetFields.add(o.getLiteralLexicalForm());
            return new FacetCountParams(queryString, facetFields, maxValues);
        }

        List<Node> list = argObject.getArgList();
        int idx = 0;

        // First argument detection: if it's a literal that is NOT a configured facet field
        // and NOT a number, treat it as a search query
        if (!list.isEmpty() && list.get(0).isLiteral()) {
            String firstArg = list.get(0).getLiteralLexicalForm();

            // Check if it's a number (would be maxValues at wrong position - unlikely)
            boolean isNumber = false;
            try {
                Integer.parseInt(firstArg);
                isNumber = true;
            } catch (NumberFormatException e) {
                // Not a number
            }

            // If not a number and not a configured facet field, it's a query
            if (!isNumber && !configuredFacetFields.contains(firstArg)) {
                queryString = firstArg;
                idx++;
            }
        }

        // Parse facet fields and max values
        while (idx < list.size()) {
            Node n = list.get(idx);
            if (n.isLiteral()) {
                String lexForm = n.getLiteralLexicalForm();
                // Check if it's a number (max facet values)
                try {
                    int val = Integer.parseInt(lexForm);
                    maxValues = val;
                } catch (NumberFormatException e) {
                    // It's a facet field name
                    facetFields.add(lexForm);
                }
            }
            idx++;
        }

        return new FacetCountParams(queryString, facetFields, maxValues);
    }

    private static class FacetCountParams {
        final String queryString;
        final List<String> facetFields;
        final int maxValues;

        FacetCountParams(String queryString, List<String> facetFields, int maxValues) {
            this.queryString = queryString;
            this.facetFields = facetFields;
            this.maxValues = maxValues;
        }
    }
}
