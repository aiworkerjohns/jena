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
import java.util.Collections;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;

public class TextIndexConfig {

    EntityDefinition entDef;
    Analyzer analyzer;
    Analyzer queryAnalyzer;
    String queryParser;
    boolean multilingualSupport;
    int maxBasicQueries = 1024;
    boolean valueStored;
    boolean ignoreIndexErrors;
    List<String> facetFields = new ArrayList<>();

    // Geo/spatial configuration
    List<String> geoFields = new ArrayList<>();
    String geoFormat = "WKT";  // Default: WKT POINT (lon lat) format
    boolean storeCoordinates = true;  // Store lat/lon for retrieval

    // Document producer mode: "triple" (default) or "entity"
    String docProducerMode = "triple";

    public TextIndexConfig(EntityDefinition entDef) {
        this.entDef = entDef;
    }

    public EntityDefinition getEntDef() {
        return entDef;
    }

    public Analyzer getAnalyzer() {
        return analyzer;
    }

    public void setAnalyzer(Analyzer analyzer) {
        this.analyzer = analyzer;
    }

    public Analyzer getQueryAnalyzer() {
        return queryAnalyzer;
    }

    public void setQueryAnalyzer(Analyzer queryAnalyzer) {
        this.queryAnalyzer = queryAnalyzer;
    }

    public String getQueryParser() {
        return ((queryParser != null) ? queryParser : "QueryParser");
    }

    public void setQueryParser(String queryParser) {
        this.queryParser = queryParser;
    }

    public boolean isMultilingualSupport() {
        return multilingualSupport;
    }

    public void setMultilingualSupport(boolean multilingualSupport) {
        this.multilingualSupport = multilingualSupport;
    }

    public int getMaxBasicQueries() {
        return maxBasicQueries;
    }

    public void setMaxBasicQueries(int maxBasicQueries) {
        this.maxBasicQueries = maxBasicQueries;
    }

    public boolean isValueStored() {
        return valueStored;
    }

    public void setValueStored(boolean valueStored) {
        this.valueStored = valueStored;
    }

    public boolean isIgnoreIndexErrors() {
        return ignoreIndexErrors;
    }

    public void setIgnoreIndexErrors(boolean ignore) {
        this.ignoreIndexErrors = ignore;
    }

    /**
     * Get the list of fields to enable native Lucene faceting on.
     * These fields will have SortedSetDocValues added during indexing.
     */
    public List<String> getFacetFields() {
        return Collections.unmodifiableList(facetFields);
    }

    /**
     * Set the list of fields to enable native Lucene faceting on.
     * @param facetFields list of field names that should support faceting
     */
    public void setFacetFields(List<String> facetFields) {
        this.facetFields = new ArrayList<>(facetFields);
    }

    /**
     * Add a field to the list of facetable fields.
     * @param fieldName the field name to enable faceting on
     */
    public void addFacetField(String fieldName) {
        this.facetFields.add(fieldName);
    }

    /**
     * Check if a field is configured for faceting.
     * @param fieldName the field name to check
     * @return true if the field is configured for faceting
     */
    public boolean isFacetField(String fieldName) {
        return facetFields.contains(fieldName);
    }

    // ========== Geo/Spatial Configuration ==========

    /**
     * Get the list of fields configured for geo/spatial indexing.
     * @return unmodifiable list of geo field names
     */
    public List<String> getGeoFields() {
        return Collections.unmodifiableList(geoFields);
    }

    /**
     * Set the list of fields for geo/spatial indexing.
     * @param geoFields list of field names that should support spatial queries
     */
    public void setGeoFields(List<String> geoFields) {
        this.geoFields = new ArrayList<>(geoFields);
    }

    /**
     * Add a field to the list of geo-indexed fields.
     * @param fieldName the field name to enable geo indexing on
     */
    public void addGeoField(String fieldName) {
        this.geoFields.add(fieldName);
    }

    /**
     * Check if a field is configured for geo indexing.
     * @param fieldName the field name to check
     * @return true if the field is configured for geo indexing
     */
    public boolean isGeoField(String fieldName) {
        return geoFields.contains(fieldName);
    }

    /**
     * Get the geo input format.
     * @return the geo format (e.g., "WKT")
     */
    public String getGeoFormat() {
        return geoFormat;
    }

    /**
     * Set the geo input format.
     * @param geoFormat the format (e.g., "WKT" for WKT POINT/POLYGON)
     */
    public void setGeoFormat(String geoFormat) {
        this.geoFormat = geoFormat;
    }

    /**
     * Check if coordinates should be stored for retrieval.
     * @return true if lat/lon should be stored
     */
    public boolean isStoreCoordinates() {
        return storeCoordinates;
    }

    /**
     * Set whether to store coordinates for retrieval.
     * @param storeCoordinates true to store lat/lon values
     */
    public void setStoreCoordinates(boolean storeCoordinates) {
        this.storeCoordinates = storeCoordinates;
    }

    // ========== Document Producer Mode ==========

    /**
     * Get the document producer mode.
     * @return "triple" for one-doc-per-triple, "entity" for one-doc-per-entity
     */
    public String getDocProducerMode() {
        return docProducerMode;
    }

    /**
     * Set the document producer mode.
     * @param mode "triple" or "entity"
     */
    public void setDocProducerMode(String mode) {
        if (!"triple".equals(mode) && !"entity".equals(mode)) {
            throw new IllegalArgumentException("docProducerMode must be 'triple' or 'entity', got: " + mode);
        }
        this.docProducerMode = mode;
    }

    /**
     * Check if entity mode is enabled.
     * @return true if using entity-based document production
     */
    public boolean isEntityMode() {
        return "entity".equals(docProducerMode);
    }
}
