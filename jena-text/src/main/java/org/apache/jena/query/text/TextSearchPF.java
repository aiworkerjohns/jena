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
import org.apache.jena.query.text.geo.*;
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
 * SPARQL property function for combined text + spatial + facets search.
 * <p>
 * Usage:
 * <pre>
 * PREFIX text: &lt;http://jena.apache.org/text#&gt;
 * PREFIX geo: &lt;http://www.opengis.net/ont/geosparql#&gt;
 *
 * # Basic text search with coordinates
 * SELECT ?s ?score ?lat ?lon
 * WHERE {
 *   (?s ?score ?lat ?lon) text:search ("search terms")
 * }
 *
 * # Text search with bounding box filter
 * SELECT ?s ?score ?lat ?lon
 * WHERE {
 *   (?s ?score ?lat ?lon) text:search ("search terms" geo:bbox -180 -90 180 90)
 * }
 *
 * # Text search with distance filter
 * SELECT ?s ?score ?lat ?lon
 * WHERE {
 *   (?s ?score ?lat ?lon) text:search ("search terms" geo:distance -122.4 37.8 5.0)
 * }
 *
 * # Text search with polygon filter and facets
 * SELECT ?s ?score ?lat ?lon ?facetField ?facetValue ?facetCount
 * WHERE {
 *   (?s ?score ?lat ?lon ?facetField ?facetValue ?facetCount) text:search
 *       ("search terms" geo:intersects "POLYGON((...)))" facet:fields "category" 10)
 * }
 * </pre>
 * <p>
 * Object list format:
 * <ol>
 *   <li>"query string" - Text query (optional if geo query provided)</li>
 *   <li>geo:bbox minLon minLat maxLon maxLat - Bounding box filter</li>
 *   <li>geo:distance centerLon centerLat radiusKm - Distance filter</li>
 *   <li>geo:intersects "WKT POLYGON" - Polygon intersection</li>
 *   <li>geo:within "WKT POLYGON" - Within polygon</li>
 *   <li>facet:fields field1 field2 ... - Facet fields to compute</li>
 *   <li>maxValues - Max facet values per field (integer)</li>
 * </ol>
 */
public class TextSearchPF extends PropertyFunctionBase {
    private static Logger log = LoggerFactory.getLogger(TextSearchPF.class);

    // URI prefixes for geo operators
    private static final String GEO_NS = "http://www.opengis.net/ont/geosparql#";
    private static final String FACET_NS = "http://jena.apache.org/text/facet#";

    private static final Node GEO_BBOX = NodeFactory.createURI(GEO_NS + "bbox");
    private static final Node GEO_DISTANCE = NodeFactory.createURI(GEO_NS + "distance");
    private static final Node GEO_INTERSECTS = NodeFactory.createURI(GEO_NS + "intersects");
    private static final Node GEO_WITHIN = NodeFactory.createURI(GEO_NS + "within");
    private static final Node FACET_FIELDS = NodeFactory.createURI(FACET_NS + "fields");

    /** Symbol for storing geo faceted results in the execution context */
    public static final Symbol GEO_FACETED_RESULTS = Symbol.create(TextQuery.NS + "geoFacetedResults");

    private TextIndexLucene textIndex = null;
    private boolean warningIssued = false;

    public TextSearchPF() {}

    @Override
    public void build(PropFuncArg argSubject, Node predicate, PropFuncArg argObject, ExecutionContext execCxt) {
        super.build(argSubject, predicate, argObject, execCxt);
        DatasetGraph dsg = execCxt.getDataset();
        textIndex = chooseTextIndex(execCxt, dsg);

        if (argSubject.isList()) {
            int size = argSubject.getArgListSize();
            if (size < 1 || size > 7) {
                throw new QueryBuildException("Subject must have 1-7 elements: " + argSubject);
            }
        }

        if (argObject.isList()) {
            List<Node> list = argObject.getArgList();
            if (list.isEmpty()) {
                throw new QueryBuildException("Object list cannot be empty");
            }
        }
    }

    private static TextIndexLucene chooseTextIndex(ExecutionContext execCxt, DatasetGraph dsg) {
        Object obj = execCxt.getContext().get(TextQuery.textIndex);
        if (obj instanceof TextIndexLucene) {
            return (TextIndexLucene) obj;
        }
        if (obj != null) {
            Log.warn(TextSearchPF.class, "Context setting '" + TextQuery.textIndex + "' is not a TextIndexLucene");
        }
        if (dsg instanceof DatasetGraphText) {
            TextIndex idx = ((DatasetGraphText) dsg).getTextIndex();
            if (idx instanceof TextIndexLucene) {
                return (TextIndexLucene) idx;
            }
        }
        Log.warn(TextSearchPF.class, "Failed to find a TextIndexLucene for text:search");
        return null;
    }

    @Override
    public QueryIterator exec(Binding binding,
                              PropFuncArg argSubject, Node predicate, PropFuncArg argObject,
                              ExecutionContext execCxt) {
        if (textIndex == null) {
            if (!warningIssued) {
                Log.warn(getClass(), "No text index - no search performed");
                warningIssued = true;
            }
            return IterLib.result(binding, execCxt);
        }

        argSubject = Substitute.substitute(argSubject, binding);
        argObject = Substitute.substitute(argObject, binding);

        // Parse subject variables: (?s ?score ?lat ?lon ?facetField ?facetValue ?facetCount)
        SubjectVars subjVars = parseSubjectVars(argSubject);
        if (subjVars == null) {
            return IterLib.noResults(execCxt);
        }

        // Parse object arguments
        SearchParams params = parseObjectArgs(argObject);
        if (params == null) {
            return IterLib.noResults(execCxt);
        }

        // Build GeoSearchParams
        GeoSearchParams.Builder builder = new GeoSearchParams.Builder();

        if (params.textQuery != null && !params.textQuery.isBlank()) {
            builder.textQuery(params.textQuery);
        }

        if (params.geoField != null) {
            builder.geoField(params.geoField);
        } else if (!textIndex.getGeoFields().isEmpty()) {
            builder.geoField(textIndex.getGeoFields().get(0));
        }

        // Add geo query
        switch (params.geoType) {
            case BBOX:
                builder.bbox(params.minLon, params.minLat, params.maxLon, params.maxLat);
                break;
            case DISTANCE:
                builder.distance(params.centerLon, params.centerLat, params.radiusKm);
                break;
            case INTERSECTS:
                builder.intersects(params.polygonWKT);
                break;
            case WITHIN:
                builder.within(params.polygonWKT);
                break;
            case NONE:
                // No geo filter
                break;
        }

        if (!params.facetFields.isEmpty()) {
            builder.facetFields(params.facetFields);
            builder.maxFacetValues(params.maxFacetValues);
        }

        builder.maxResults(10000);

        // Execute search
        GeoFacetedResults results;
        try {
            results = textIndex.searchWithGeoAndFacets(builder.build());
        } catch (Exception e) {
            log.warn("Search failed: {}", e.getMessage());
            return IterLib.noResults(execCxt);
        }

        // Store results in context for potential reuse
        execCxt.getContext().put(GEO_FACETED_RESULTS, results);

        // Generate bindings
        return generateBindings(binding, subjVars, results, params.facetFields, execCxt);
    }

    private QueryIterator generateBindings(Binding binding, SubjectVars subjVars,
            GeoFacetedResults results, List<String> facetFields, ExecutionContext execCxt) {

        List<Binding> bindings = new ArrayList<>();

        // If no facet variables are requested, just return hits
        if (subjVars.facetFieldVar == null || facetFields.isEmpty()) {
            for (GeoTextHit hit : results.getHits()) {
                BindingBuilder builder = Binding.builder(binding);
                addHitBindings(builder, subjVars, hit);
                bindings.add(builder.build());
            }
        } else {
            // Return one binding per hit + facet combination
            for (GeoTextHit hit : results.getHits()) {
                for (String fieldName : results.getFacets().keySet()) {
                    for (FacetValue fv : results.getFacetsForField(fieldName)) {
                        BindingBuilder builder = Binding.builder(binding);
                        addHitBindings(builder, subjVars, hit);

                        if (subjVars.facetFieldVar != null) {
                            builder.add(subjVars.facetFieldVar, NodeFactory.createLiteralString(fieldName));
                        }
                        if (subjVars.facetValueVar != null) {
                            builder.add(subjVars.facetValueVar, NodeFactory.createLiteralString(fv.getValue()));
                        }
                        if (subjVars.facetCountVar != null) {
                            builder.add(subjVars.facetCountVar, NodeFactory.createLiteralDT(
                                String.valueOf(fv.getCount()), XSDDatatype.XSDlong));
                        }
                        bindings.add(builder.build());
                    }
                }
            }
        }

        return QueryIterPlainWrapper.create(bindings.iterator(), execCxt);
    }

    private void addHitBindings(BindingBuilder builder, SubjectVars subjVars, GeoTextHit hit) {
        if (subjVars.sVar != null) {
            builder.add(subjVars.sVar, hit.getNode());
        }
        if (subjVars.scoreVar != null) {
            builder.add(subjVars.scoreVar, NodeFactoryExtra.floatToNode(hit.getScore()));
        }
        if (subjVars.latVar != null && hit.hasCoordinates()) {
            builder.add(subjVars.latVar, NodeFactoryExtra.doubleToNode(hit.getLatitude()));
        }
        if (subjVars.lonVar != null && hit.hasCoordinates()) {
            builder.add(subjVars.lonVar, NodeFactoryExtra.doubleToNode(hit.getLongitude()));
        }
    }

    private SubjectVars parseSubjectVars(PropFuncArg argSubject) {
        SubjectVars vars = new SubjectVars();

        if (argSubject.isList()) {
            List<Node> list = argSubject.getArgList();
            if (list.isEmpty()) return null;

            vars.sNode = list.get(0);
            vars.sVar = Var.isVar(vars.sNode) ? Var.alloc(vars.sNode) : null;

            if (list.size() > 1) {
                Node n = list.get(1);
                if (!n.isVariable()) throw new QueryExecException("Score must be a variable");
                vars.scoreVar = Var.alloc(n);
            }
            if (list.size() > 2) {
                Node n = list.get(2);
                if (!n.isVariable()) throw new QueryExecException("Lat must be a variable");
                vars.latVar = Var.alloc(n);
            }
            if (list.size() > 3) {
                Node n = list.get(3);
                if (!n.isVariable()) throw new QueryExecException("Lon must be a variable");
                vars.lonVar = Var.alloc(n);
            }
            if (list.size() > 4) {
                Node n = list.get(4);
                if (!n.isVariable()) throw new QueryExecException("Facet field must be a variable");
                vars.facetFieldVar = Var.alloc(n);
            }
            if (list.size() > 5) {
                Node n = list.get(5);
                if (!n.isVariable()) throw new QueryExecException("Facet value must be a variable");
                vars.facetValueVar = Var.alloc(n);
            }
            if (list.size() > 6) {
                Node n = list.get(6);
                if (!n.isVariable()) throw new QueryExecException("Facet count must be a variable");
                vars.facetCountVar = Var.alloc(n);
            }
        } else {
            vars.sNode = argSubject.getArg();
            vars.sVar = Var.isVar(vars.sNode) ? Var.alloc(vars.sNode) : null;
        }

        if (vars.sNode.isLiteral()) {
            return null;
        }

        return vars;
    }

    private SearchParams parseObjectArgs(PropFuncArg argObject) {
        SearchParams params = new SearchParams();

        if (argObject.isNode()) {
            Node o = argObject.getArg();
            if (!o.isLiteral()) {
                log.warn("Query argument must be a literal: " + o);
                return null;
            }
            params.textQuery = o.getLiteralLexicalForm();
            return params;
        }

        List<Node> list = argObject.getArgList();
        int idx = 0;

        // First argument could be text query
        if (idx < list.size() && list.get(idx).isLiteral()) {
            String val = list.get(idx).getLiteralLexicalForm();
            // Check if it's not a WKT polygon (those start with POLYGON)
            if (!val.toUpperCase().startsWith("POLYGON")) {
                params.textQuery = val;
                idx++;
            }
        }

        // Parse remaining arguments
        while (idx < list.size()) {
            Node n = list.get(idx);

            if (n.isURI()) {
                String uri = n.getURI();

                if (uri.equals(GEO_BBOX.getURI())) {
                    // geo:bbox minLon minLat maxLon maxLat
                    if (idx + 4 >= list.size()) {
                        log.warn("geo:bbox requires 4 numeric arguments");
                        return null;
                    }
                    params.geoType = GeoSearchParams.GeoQueryType.BBOX;
                    params.minLon = parseDouble(list.get(++idx));
                    params.minLat = parseDouble(list.get(++idx));
                    params.maxLon = parseDouble(list.get(++idx));
                    params.maxLat = parseDouble(list.get(++idx));

                } else if (uri.equals(GEO_DISTANCE.getURI())) {
                    // geo:distance centerLon centerLat radiusKm
                    if (idx + 3 >= list.size()) {
                        log.warn("geo:distance requires 3 numeric arguments");
                        return null;
                    }
                    params.geoType = GeoSearchParams.GeoQueryType.DISTANCE;
                    params.centerLon = parseDouble(list.get(++idx));
                    params.centerLat = parseDouble(list.get(++idx));
                    params.radiusKm = parseDouble(list.get(++idx));

                } else if (uri.equals(GEO_INTERSECTS.getURI())) {
                    // geo:intersects "WKT POLYGON"
                    if (idx + 1 >= list.size()) {
                        log.warn("geo:intersects requires a WKT polygon argument");
                        return null;
                    }
                    params.geoType = GeoSearchParams.GeoQueryType.INTERSECTS;
                    params.polygonWKT = list.get(++idx).getLiteralLexicalForm();

                } else if (uri.equals(GEO_WITHIN.getURI())) {
                    // geo:within "WKT POLYGON"
                    if (idx + 1 >= list.size()) {
                        log.warn("geo:within requires a WKT polygon argument");
                        return null;
                    }
                    params.geoType = GeoSearchParams.GeoQueryType.WITHIN;
                    params.polygonWKT = list.get(++idx).getLiteralLexicalForm();

                } else if (uri.equals(FACET_FIELDS.getURI())) {
                    // facet:fields field1 field2 ...
                    idx++;
                    while (idx < list.size() && list.get(idx).isLiteral()) {
                        String val = list.get(idx).getLiteralLexicalForm();
                        try {
                            int num = Integer.parseInt(val);
                            params.maxFacetValues = num;
                            break;
                        } catch (NumberFormatException e) {
                            params.facetFields.add(val);
                        }
                        idx++;
                    }
                    continue; // Don't increment idx again
                }
            } else if (n.isLiteral()) {
                String val = n.getLiteralLexicalForm();
                // Could be max facet values
                try {
                    params.maxFacetValues = Integer.parseInt(val);
                } catch (NumberFormatException e) {
                    // Could be a facet field name
                    params.facetFields.add(val);
                }
            }

            idx++;
        }

        return params;
    }

    private double parseDouble(Node n) {
        if (n.isLiteral()) {
            Object val = n.getLiteralValue();
            if (val instanceof Number) {
                return ((Number) val).doubleValue();
            }
            try {
                return Double.parseDouble(n.getLiteralLexicalForm());
            } catch (NumberFormatException e) {
                throw new QueryExecException("Expected numeric value: " + n);
            }
        }
        throw new QueryExecException("Expected numeric value: " + n);
    }

    private static class SubjectVars {
        Node sNode;
        Var sVar;
        Var scoreVar;
        Var latVar;
        Var lonVar;
        Var facetFieldVar;
        Var facetValueVar;
        Var facetCountVar;
    }

    private static class SearchParams {
        String textQuery;
        String geoField;
        GeoSearchParams.GeoQueryType geoType = GeoSearchParams.GeoQueryType.NONE;
        double minLon, minLat, maxLon, maxLat;
        double centerLon, centerLat, radiusKm;
        String polygonWKT;
        List<String> facetFields = new ArrayList<>();
        int maxFacetValues = 10;
    }
}
