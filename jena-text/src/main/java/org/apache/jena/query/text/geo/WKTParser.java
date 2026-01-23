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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for parsing WKT (Well-Known Text) geometry strings.
 * Supports POINT and POLYGON geometries with standard coordinate order (lon lat).
 */
public class WKTParser {

    // Pattern for POINT(lon lat) - coordinates are longitude first, then latitude
    private static final Pattern POINT_PATTERN = Pattern.compile(
        "^\\s*POINT\\s*\\(\\s*(-?[\\d.]+)\\s+(-?[\\d.]+)\\s*\\)\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    // Pattern for POLYGON((x1 y1, x2 y2, ...)) - exterior ring only for simplicity
    private static final Pattern POLYGON_PATTERN = Pattern.compile(
        "^\\s*POLYGON\\s*\\(\\s*\\((.+)\\)\\s*\\)\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    // Pattern for POLYGON with holes: POLYGON((exterior), (hole1), (hole2))
    private static final Pattern POLYGON_WITH_HOLES_PATTERN = Pattern.compile(
        "^\\s*POLYGON\\s*\\((.+)\\)\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    // Pattern for coordinate pair
    private static final Pattern COORD_PAIR_PATTERN = Pattern.compile(
        "(-?[\\d.]+)\\s+(-?[\\d.]+)"
    );

    /**
     * Result of parsing a WKT POINT.
     */
    public static class Point {
        public final double longitude;
        public final double latitude;

        public Point(double longitude, double latitude) {
            this.longitude = longitude;
            this.latitude = latitude;
        }

        @Override
        public String toString() {
            return String.format("Point(lon=%.6f, lat=%.6f)", longitude, latitude);
        }
    }

    /**
     * Parse a WKT POINT string.
     *
     * @param wkt WKT string in format "POINT(lon lat)"
     * @return Point with longitude and latitude
     * @throws IllegalArgumentException if parsing fails
     */
    public static Point parsePoint(String wkt) {
        if (wkt == null || wkt.isBlank()) {
            throw new IllegalArgumentException("WKT string is null or empty");
        }

        Matcher matcher = POINT_PATTERN.matcher(wkt.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid WKT POINT: " + wkt);
        }

        try {
            double lon = Double.parseDouble(matcher.group(1));
            double lat = Double.parseDouble(matcher.group(2));

            validateCoordinates(lat, lon);

            return new Point(lon, lat);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid coordinates in WKT POINT: " + wkt, e);
        }
    }

    /**
     * Parse a WKT POLYGON string into a Lucene Polygon.
     *
     * @param wkt WKT string in format "POLYGON((lon1 lat1, lon2 lat2, ...))"
     * @return Lucene Polygon
     * @throws IllegalArgumentException if parsing fails
     */
    public static Polygon parsePolygon(String wkt) {
        if (wkt == null || wkt.isBlank()) {
            throw new IllegalArgumentException("WKT string is null or empty");
        }

        String trimmed = wkt.trim();

        // First try simple polygon (no holes)
        Matcher simpleMatcher = POLYGON_PATTERN.matcher(trimmed);
        if (simpleMatcher.matches()) {
            String coordsStr = simpleMatcher.group(1);
            double[][] coords = parseCoordinateString(coordsStr);
            return new Polygon(coords[0], coords[1]);  // lats, lons
        }

        // Try polygon with potential holes
        Matcher holesMatcher = POLYGON_WITH_HOLES_PATTERN.matcher(trimmed);
        if (holesMatcher.matches()) {
            String ringsStr = holesMatcher.group(1);
            List<double[][]> rings = parseRings(ringsStr);

            if (rings.isEmpty()) {
                throw new IllegalArgumentException("No rings found in POLYGON: " + wkt);
            }

            // First ring is exterior, rest are holes
            double[][] exterior = rings.get(0);

            if (rings.size() == 1) {
                return new Polygon(exterior[0], exterior[1]);
            } else {
                // Create holes
                Polygon[] holes = new Polygon[rings.size() - 1];
                for (int i = 1; i < rings.size(); i++) {
                    double[][] hole = rings.get(i);
                    holes[i - 1] = new Polygon(hole[0], hole[1]);
                }
                return new Polygon(exterior[0], exterior[1], holes);
            }
        }

        throw new IllegalArgumentException("Invalid WKT POLYGON: " + wkt);
    }

    /**
     * Parse a ring string "(...)" content into list of rings.
     */
    private static List<double[][]> parseRings(String ringsStr) {
        List<double[][]> rings = new ArrayList<>();

        // Split by ")," to separate rings
        int depth = 0;
        int start = 0;

        for (int i = 0; i < ringsStr.length(); i++) {
            char c = ringsStr.charAt(i);
            if (c == '(') {
                if (depth == 0) {
                    start = i + 1;
                }
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    String ringContent = ringsStr.substring(start, i).trim();
                    if (!ringContent.isEmpty()) {
                        rings.add(parseCoordinateString(ringContent));
                    }
                }
            }
        }

        return rings;
    }

    /**
     * Parse a coordinate string "lon1 lat1, lon2 lat2, ..." into arrays.
     *
     * @param coordsStr coordinate string
     * @return double[2][] where [0] is lats and [1] is lons
     */
    private static double[][] parseCoordinateString(String coordsStr) {
        List<Double> lats = new ArrayList<>();
        List<Double> lons = new ArrayList<>();

        String[] pairs = coordsStr.split(",");
        for (String pair : pairs) {
            Matcher matcher = COORD_PAIR_PATTERN.matcher(pair.trim());
            if (matcher.find()) {
                double lon = Double.parseDouble(matcher.group(1));
                double lat = Double.parseDouble(matcher.group(2));
                validateCoordinates(lat, lon);
                lats.add(lat);
                lons.add(lon);
            }
        }

        if (lats.size() < 4) {
            throw new IllegalArgumentException("Polygon must have at least 4 points (including closing point)");
        }

        // Ensure polygon is closed
        if (!lats.get(0).equals(lats.get(lats.size() - 1)) ||
            !lons.get(0).equals(lons.get(lons.size() - 1))) {
            throw new IllegalArgumentException("Polygon must be closed (first and last points must match)");
        }

        double[] latArray = lats.stream().mapToDouble(Double::doubleValue).toArray();
        double[] lonArray = lons.stream().mapToDouble(Double::doubleValue).toArray();

        return new double[][] { latArray, lonArray };
    }

    /**
     * Check if a string looks like a WKT POINT.
     */
    public static boolean isPoint(String wkt) {
        if (wkt == null) return false;
        return POINT_PATTERN.matcher(wkt.trim()).matches();
    }

    /**
     * Check if a string looks like a WKT POLYGON.
     */
    public static boolean isPolygon(String wkt) {
        if (wkt == null) return false;
        String trimmed = wkt.trim().toUpperCase();
        return trimmed.startsWith("POLYGON");
    }

    /**
     * Validate latitude and longitude bounds.
     */
    private static void validateCoordinates(double lat, double lon) {
        if (lat < -90 || lat > 90) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90: " + lat);
        }
        if (lon < -180 || lon > 180) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180: " + lon);
        }
    }
}
