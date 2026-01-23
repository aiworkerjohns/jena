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

import static org.junit.Assert.*;

import org.apache.jena.query.text.geo.WKTParser;
import org.apache.lucene.geo.Polygon;
import org.junit.Test;

/**
 * Tests for WKT parsing utility.
 */
public class TestWKTParser {

    // ========== Point Parsing Tests ==========

    @Test
    public void testParsePointBasic() {
        WKTParser.Point p = WKTParser.parsePoint("POINT(-122.4 37.8)");
        assertEquals(-122.4, p.longitude, 0.0001);
        assertEquals(37.8, p.latitude, 0.0001);
    }

    @Test
    public void testParsePointWithSpaces() {
        WKTParser.Point p = WKTParser.parsePoint("  POINT  (  -122.4   37.8  )  ");
        assertEquals(-122.4, p.longitude, 0.0001);
        assertEquals(37.8, p.latitude, 0.0001);
    }

    @Test
    public void testParsePointCaseInsensitive() {
        WKTParser.Point p = WKTParser.parsePoint("point(-122.4 37.8)");
        assertEquals(-122.4, p.longitude, 0.0001);
        assertEquals(37.8, p.latitude, 0.0001);
    }

    @Test
    public void testParsePointNegativeCoordinates() {
        WKTParser.Point p = WKTParser.parsePoint("POINT(-180.0 -90.0)");
        assertEquals(-180.0, p.longitude, 0.0001);
        assertEquals(-90.0, p.latitude, 0.0001);
    }

    @Test
    public void testParsePointPositiveCoordinates() {
        WKTParser.Point p = WKTParser.parsePoint("POINT(180.0 90.0)");
        assertEquals(180.0, p.longitude, 0.0001);
        assertEquals(90.0, p.latitude, 0.0001);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParsePointInvalidFormat() {
        WKTParser.parsePoint("POINT(-122.4)");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParsePointInvalidLatitude() {
        WKTParser.parsePoint("POINT(-122.4 91.0)");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParsePointInvalidLongitude() {
        WKTParser.parsePoint("POINT(-181.0 37.8)");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParsePointNull() {
        WKTParser.parsePoint(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParsePointEmpty() {
        WKTParser.parsePoint("");
    }

    // ========== Polygon Parsing Tests ==========

    @Test
    public void testParsePolygonBasic() {
        String wkt = "POLYGON((-122.5 37.7, -122.3 37.7, -122.3 37.9, -122.5 37.9, -122.5 37.7))";
        Polygon p = WKTParser.parsePolygon(wkt);
        assertNotNull(p);
        // Lucene Polygon stores lats and lons separately
        double[] lats = p.getPolyLats();
        double[] lons = p.getPolyLons();
        assertEquals(5, lats.length);
        assertEquals(5, lons.length);
    }

    @Test
    public void testParsePolygonWithSpaces() {
        String wkt = "  POLYGON  (  (  -122.5 37.7,  -122.3 37.7,  -122.3 37.9,  -122.5 37.9,  -122.5 37.7  )  )  ";
        Polygon p = WKTParser.parsePolygon(wkt);
        assertNotNull(p);
    }

    @Test
    public void testParsePolygonCaseInsensitive() {
        String wkt = "polygon((-122.5 37.7, -122.3 37.7, -122.3 37.9, -122.5 37.9, -122.5 37.7))";
        Polygon p = WKTParser.parsePolygon(wkt);
        assertNotNull(p);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParsePolygonNotClosed() {
        // Polygon first and last points don't match
        String wkt = "POLYGON((-122.5 37.7, -122.3 37.7, -122.3 37.9, -122.5 37.9))";
        WKTParser.parsePolygon(wkt);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParsePolygonTooFewPoints() {
        // Need at least 4 points (including closing point)
        String wkt = "POLYGON((-122.5 37.7, -122.3 37.7, -122.5 37.7))";
        WKTParser.parsePolygon(wkt);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParsePolygonNull() {
        WKTParser.parsePolygon(null);
    }

    // ========== Type Detection Tests ==========

    @Test
    public void testIsPoint() {
        assertTrue(WKTParser.isPoint("POINT(-122.4 37.8)"));
        assertTrue(WKTParser.isPoint("point(-122.4 37.8)"));
        assertTrue(WKTParser.isPoint("  POINT  (-122.4 37.8)  "));
        assertFalse(WKTParser.isPoint("POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))"));
        assertFalse(WKTParser.isPoint(null));
        assertFalse(WKTParser.isPoint(""));
    }

    @Test
    public void testIsPolygon() {
        assertTrue(WKTParser.isPolygon("POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))"));
        assertTrue(WKTParser.isPolygon("polygon((0 0, 1 0, 1 1, 0 1, 0 0))"));
        assertFalse(WKTParser.isPolygon("POINT(-122.4 37.8)"));
        assertFalse(WKTParser.isPolygon(null));
    }

    // ========== toString Test ==========

    @Test
    public void testPointToString() {
        WKTParser.Point p = WKTParser.parsePoint("POINT(-122.4 37.8)");
        String s = p.toString();
        assertTrue(s.contains("-122.4"));
        assertTrue(s.contains("37.8"));
    }
}
