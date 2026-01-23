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

import org.apache.jena.query.text.FacetValue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Results from a combined text + geo + facets search.
 * Contains:
 * - List of geo text hits (with coordinates)
 * - Facet counts by field
 * - Total hit count
 */
public class GeoFacetedResults {

    private final List<GeoTextHit> hits;
    private final Map<String, List<FacetValue>> facets;
    private final long totalHits;

    /**
     * Create geo faceted results.
     *
     * @param hits List of geo text hits
     * @param facets Map of field name to facet values
     * @param totalHits Total number of hits (may exceed returned hits due to limits)
     */
    public GeoFacetedResults(List<GeoTextHit> hits, Map<String, List<FacetValue>> facets, long totalHits) {
        this.hits = hits != null ? Collections.unmodifiableList(hits) : Collections.emptyList();
        this.facets = facets != null ? Collections.unmodifiableMap(new HashMap<>(facets)) : Collections.emptyMap();
        this.totalHits = totalHits;
    }

    /**
     * Get the list of geo text hits.
     * @return unmodifiable list of hits
     */
    public List<GeoTextHit> getHits() {
        return hits;
    }

    /**
     * Get the number of returned hits.
     * @return number of hits in the results list
     */
    public int getReturnedHitCount() {
        return hits.size();
    }

    /**
     * Get the total number of matching documents.
     * This may be larger than getReturnedHitCount() if results were limited.
     * @return total hit count
     */
    public long getTotalHits() {
        return totalHits;
    }

    /**
     * Get all facet counts.
     * @return unmodifiable map of field name to facet values
     */
    public Map<String, List<FacetValue>> getFacets() {
        return facets;
    }

    /**
     * Get facet values for a specific field.
     * @param field the field name
     * @return list of facet values, or empty list if field not found
     */
    public List<FacetValue> getFacetsForField(String field) {
        return facets.getOrDefault(field, Collections.emptyList());
    }

    /**
     * Check if facets are available.
     * @return true if facet data is present
     */
    public boolean hasFacets() {
        return !facets.isEmpty();
    }

    /**
     * Check if any hits are returned.
     * @return true if there are hits
     */
    public boolean hasHits() {
        return !hits.isEmpty();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("GeoFacetedResults{");
        sb.append("totalHits=").append(totalHits);
        sb.append(", returnedHits=").append(hits.size());
        if (!facets.isEmpty()) {
            sb.append(", facetFields=").append(facets.keySet());
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        GeoFacetedResults that = (GeoFacetedResults) o;

        if (totalHits != that.totalHits) return false;
        if (!hits.equals(that.hits)) return false;
        return facets.equals(that.facets);
    }

    @Override
    public int hashCode() {
        int result = hits.hashCode();
        result = 31 * result + facets.hashCode();
        result = 31 * result + (int) (totalHits ^ (totalHits >>> 32));
        return result;
    }
}
