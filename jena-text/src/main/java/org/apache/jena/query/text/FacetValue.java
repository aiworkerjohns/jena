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

/**
 * Represents a single facet value with its count from a faceted search.
 * A facet value consists of the field value and the number of documents
 * matching that value.
 * 
 * Example: For a facet on "category", one FacetValue might be:
 *   value="electronics", count=42
 */
public class FacetValue {
    private final String value;
    private final long count;
    
    public FacetValue(String value, long count) {
        this.value = value;
        this.count = count;
    }
    
    /**
     * @return The facet value (e.g., "electronics", "books")
     */
    public String getValue() {
        return value;
    }
    
    /**
     * @return The number of documents with this facet value
     */
    public long getCount() {
        return count;
    }
    
    @Override
    public String toString() {
        return value + " (" + count + ")";
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof FacetValue)) return false;
        FacetValue other = (FacetValue) obj;
        return count == other.count && 
               (value == null ? other.value == null : value.equals(other.value));
    }
    
    @Override
    public int hashCode() {
        int result = value != null ? value.hashCode() : 0;
        result = 31 * result + (int) (count ^ (count >>> 32));
        return result;
    }
}
