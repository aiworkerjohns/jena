/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jena.query.text;

import java.util.*;

/**
 * Container for text search results with faceting information.
 * This extends the standard search results to include facet counts
 * for specified fields.
 * 
 * Example usage:
 * <pre>
 *   FacetedTextResults results = index.queryWithFacets(...);
 *   List&lt;TextHit&gt; hits = results.getHits();  // Normal search results
 *   Map&lt;String, List&lt;FacetValue&gt;&gt; facets = results.getFacets();
 *   List&lt;FacetValue&gt; categoryFacets = facets.get("category");
 *   // categoryFacets might contain: [electronics(42), books(28), ...]
 * </pre>
 */
public class FacetedTextResults {
    private final List<TextHit> hits;
    private final Map<String, List<FacetValue>> facets;
    private final long totalHits;
    
    /**
     * Create faceted search results
     * @param hits The text search hits
     * @param facets Map of field names to their facet values
     * @param totalHits Total number of matching documents (may be > hits.size() if limited)
     */
    public FacetedTextResults(List<TextHit> hits, Map<String, List<FacetValue>> facets, long totalHits) {
        this.hits = Collections.unmodifiableList(new ArrayList<>(hits));
        this.facets = Collections.unmodifiableMap(new HashMap<>(facets));
        this.totalHits = totalHits;
    }
    
    /**
     * @return The search hits (same as regular query results)
     */
    public List<TextHit> getHits() {
        return hits;
    }
    
    /**
     * @return Map of facet field names to their values with counts
     */
    public Map<String, List<FacetValue>> getFacets() {
        return facets;
    }
    
    /**
     * Get facet values for a specific field
     * @param fieldName The facet field name
     * @return List of facet values for that field, or empty list if field not faceted
     */
    public List<FacetValue> getFacetsForField(String fieldName) {
        return facets.getOrDefault(fieldName, Collections.emptyList());
    }
    
    /**
     * @return Total number of documents matching the query
     */
    public long getTotalHits() {
        return totalHits;
    }
    
    /**
     * @return Number of hits returned (may be less than totalHits if limited)
     */
    public int getReturnedHitCount() {
        return hits.size();
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("FacetedTextResults{");
        sb.append("hits=").append(hits.size());
        sb.append(", totalHits=").append(totalHits);
        sb.append(", facets={");
        for (Map.Entry<String, List<FacetValue>> entry : facets.entrySet()) {
            sb.append(entry.getKey()).append(":");
            sb.append(entry.getValue().size()).append(" values, ");
        }
        sb.append("}}");
        return sb.toString();
    }
}
