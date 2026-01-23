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

import org.apache.jena.graph.Node;

/**
 * A text search hit that includes geographic coordinates.
 * Extends the basic text hit with latitude and longitude values.
 */
public class GeoTextHit {

    private final Node node;
    private final float score;
    private final double latitude;
    private final double longitude;
    private final Node literal;
    private final Node graph;
    private final Node predicate;

    /**
     * Create a geo text hit with coordinates.
     *
     * @param node Entity URI
     * @param score Relevance score
     * @param latitude Latitude value
     * @param longitude Longitude value
     */
    public GeoTextHit(Node node, float score, double latitude, double longitude) {
        this(node, score, latitude, longitude, null, null, null);
    }

    /**
     * Create a geo text hit with full details.
     *
     * @param node Entity URI
     * @param score Relevance score
     * @param latitude Latitude value
     * @param longitude Longitude value
     * @param literal The matched literal value
     * @param graph The graph URI
     * @param predicate The matched predicate
     */
    public GeoTextHit(Node node, float score, double latitude, double longitude,
                      Node literal, Node graph, Node predicate) {
        this.node = node;
        this.score = score;
        this.latitude = latitude;
        this.longitude = longitude;
        this.literal = literal;
        this.graph = graph;
        this.predicate = predicate;
    }

    /**
     * Get the entity URI.
     */
    public Node getNode() {
        return node;
    }

    /**
     * Get the relevance score.
     */
    public float getScore() {
        return score;
    }

    /**
     * Get the latitude.
     */
    public double getLatitude() {
        return latitude;
    }

    /**
     * Get the longitude.
     */
    public double getLongitude() {
        return longitude;
    }

    /**
     * Check if coordinates are available.
     */
    public boolean hasCoordinates() {
        return !Double.isNaN(latitude) && !Double.isNaN(longitude);
    }

    /**
     * Get the matched literal value.
     */
    public Node getLiteral() {
        return literal;
    }

    /**
     * Get the graph URI.
     */
    public Node getGraph() {
        return graph;
    }

    /**
     * Get the matched predicate.
     */
    public Node getPredicate() {
        return predicate;
    }

    @Override
    public String toString() {
        return String.format("GeoTextHit{node=%s, score=%.4f, lat=%.6f, lon=%.6f}",
            node, score, latitude, longitude);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        GeoTextHit that = (GeoTextHit) o;

        if (Float.compare(that.score, score) != 0) return false;
        if (Double.compare(that.latitude, latitude) != 0) return false;
        if (Double.compare(that.longitude, longitude) != 0) return false;
        return node != null ? node.equals(that.node) : that.node == null;
    }

    @Override
    public int hashCode() {
        int result;
        long temp;
        result = node != null ? node.hashCode() : 0;
        result = 31 * result + (score != 0.0f ? Float.floatToIntBits(score) : 0);
        temp = Double.doubleToLongBits(latitude);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(longitude);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        return result;
    }
}
