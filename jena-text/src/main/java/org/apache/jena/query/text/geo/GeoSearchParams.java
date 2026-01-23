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

package org.apache.jena.query.text.geo;

import org.apache.lucene.geo.Polygon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parameters for a combined text + geo + facets search.
 * Use the Builder to construct instances.
 */
public class GeoSearchParams {

    /**
     * Type of spatial query.
     */
    public enum GeoQueryType {
        NONE,       // No spatial filter
        BBOX,       // Bounding box query
        DISTANCE,   // Distance/radius query
        INTERSECTS, // Polygon intersection
        WITHIN      // Within polygon
    }

    private final String textQuery;
    private final String geoField;
    private final GeoQueryType geoQueryType;

    // BBox parameters
    private final double minLon;
    private final double minLat;
    private final double maxLon;
    private final double maxLat;

    // Distance parameters
    private final double centerLon;
    private final double centerLat;
    private final double radiusKm;

    // Polygon parameters
    private final Polygon polygon;
    private final String polygonWKT;

    // Facet parameters
    private final List<String> facetFields;
    private final int maxFacetValues;

    // Result limits
    private final int maxResults;
    private final String graphURI;
    private final String lang;

    private GeoSearchParams(Builder builder) {
        this.textQuery = builder.textQuery;
        this.geoField = builder.geoField;
        this.geoQueryType = builder.geoQueryType;
        this.minLon = builder.minLon;
        this.minLat = builder.minLat;
        this.maxLon = builder.maxLon;
        this.maxLat = builder.maxLat;
        this.centerLon = builder.centerLon;
        this.centerLat = builder.centerLat;
        this.radiusKm = builder.radiusKm;
        this.polygon = builder.polygon;
        this.polygonWKT = builder.polygonWKT;
        this.facetFields = Collections.unmodifiableList(new ArrayList<>(builder.facetFields));
        this.maxFacetValues = builder.maxFacetValues;
        this.maxResults = builder.maxResults;
        this.graphURI = builder.graphURI;
        this.lang = builder.lang;
    }

    // Getters

    public String getTextQuery() {
        return textQuery;
    }

    public boolean hasTextQuery() {
        return textQuery != null && !textQuery.isBlank();
    }

    public String getGeoField() {
        return geoField;
    }

    public GeoQueryType getGeoQueryType() {
        return geoQueryType;
    }

    public boolean hasGeoQuery() {
        return geoQueryType != GeoQueryType.NONE;
    }

    public double getMinLon() {
        return minLon;
    }

    public double getMinLat() {
        return minLat;
    }

    public double getMaxLon() {
        return maxLon;
    }

    public double getMaxLat() {
        return maxLat;
    }

    public double getCenterLon() {
        return centerLon;
    }

    public double getCenterLat() {
        return centerLat;
    }

    public double getRadiusKm() {
        return radiusKm;
    }

    public Polygon getPolygon() {
        return polygon;
    }

    public String getPolygonWKT() {
        return polygonWKT;
    }

    public List<String> getFacetFields() {
        return facetFields;
    }

    public boolean hasFacets() {
        return !facetFields.isEmpty();
    }

    public int getMaxFacetValues() {
        return maxFacetValues;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public String getGraphURI() {
        return graphURI;
    }

    public String getLang() {
        return lang;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("GeoSearchParams{");
        if (hasTextQuery()) {
            sb.append("textQuery='").append(textQuery).append("', ");
        }
        if (hasGeoQuery()) {
            sb.append("geoType=").append(geoQueryType).append(", ");
            switch (geoQueryType) {
                case BBOX:
                    sb.append(String.format("bbox=[%.4f,%.4f,%.4f,%.4f], ",
                        minLon, minLat, maxLon, maxLat));
                    break;
                case DISTANCE:
                    sb.append(String.format("center=[%.4f,%.4f], radius=%.2fkm, ",
                        centerLon, centerLat, radiusKm));
                    break;
                case INTERSECTS:
                case WITHIN:
                    sb.append("polygon=").append(polygonWKT != null ? polygonWKT.substring(0, Math.min(50, polygonWKT.length())) : "null").append(", ");
                    break;
                case NONE:
                    // No geo query - nothing to append
                    break;
            }
        }
        if (hasFacets()) {
            sb.append("facetFields=").append(facetFields).append(", ");
        }
        sb.append("maxResults=").append(maxResults);
        sb.append("}");
        return sb.toString();
    }

    /**
     * Builder for GeoSearchParams.
     */
    public static class Builder {
        private String textQuery;
        private String geoField = "location";  // Default geo field name
        private GeoQueryType geoQueryType = GeoQueryType.NONE;
        private double minLon, minLat, maxLon, maxLat;
        private double centerLon, centerLat, radiusKm;
        private Polygon polygon;
        private String polygonWKT;
        private List<String> facetFields = new ArrayList<>();
        private int maxFacetValues = 10;
        private int maxResults = 10000;
        private String graphURI;
        private String lang;

        public Builder() {}

        /**
         * Set the text search query.
         */
        public Builder textQuery(String query) {
            this.textQuery = query;
            return this;
        }

        /**
         * Set the geo field name to query.
         */
        public Builder geoField(String field) {
            this.geoField = field;
            return this;
        }

        /**
         * Set a bounding box filter.
         *
         * @param minLon Minimum longitude
         * @param minLat Minimum latitude
         * @param maxLon Maximum longitude
         * @param maxLat Maximum latitude
         */
        public Builder bbox(double minLon, double minLat, double maxLon, double maxLat) {
            this.geoQueryType = GeoQueryType.BBOX;
            this.minLon = minLon;
            this.minLat = minLat;
            this.maxLon = maxLon;
            this.maxLat = maxLat;
            return this;
        }

        /**
         * Set a distance/radius filter.
         *
         * @param centerLon Center longitude
         * @param centerLat Center latitude
         * @param radiusKm Radius in kilometers
         */
        public Builder distance(double centerLon, double centerLat, double radiusKm) {
            this.geoQueryType = GeoQueryType.DISTANCE;
            this.centerLon = centerLon;
            this.centerLat = centerLat;
            this.radiusKm = radiusKm;
            return this;
        }

        /**
         * Set a polygon intersection filter.
         *
         * @param wkt WKT POLYGON string
         */
        public Builder intersects(String wkt) {
            this.geoQueryType = GeoQueryType.INTERSECTS;
            this.polygonWKT = wkt;
            this.polygon = WKTParser.parsePolygon(wkt);
            return this;
        }

        /**
         * Set a within polygon filter.
         *
         * @param wkt WKT POLYGON string
         */
        public Builder within(String wkt) {
            this.geoQueryType = GeoQueryType.WITHIN;
            this.polygonWKT = wkt;
            this.polygon = WKTParser.parsePolygon(wkt);
            return this;
        }

        /**
         * Set facet fields to compute.
         */
        public Builder facetFields(List<String> fields) {
            this.facetFields = new ArrayList<>(fields);
            return this;
        }

        /**
         * Add a facet field.
         */
        public Builder addFacetField(String field) {
            this.facetFields.add(field);
            return this;
        }

        /**
         * Set maximum facet values per field.
         */
        public Builder maxFacetValues(int max) {
            this.maxFacetValues = max;
            return this;
        }

        /**
         * Set maximum number of results.
         */
        public Builder maxResults(int max) {
            this.maxResults = max;
            return this;
        }

        /**
         * Set graph URI filter.
         */
        public Builder graphURI(String uri) {
            this.graphURI = uri;
            return this;
        }

        /**
         * Set language filter.
         */
        public Builder lang(String lang) {
            this.lang = lang;
            return this;
        }

        /**
         * Build the GeoSearchParams.
         */
        public GeoSearchParams build() {
            return new GeoSearchParams(this);
        }
    }
}
