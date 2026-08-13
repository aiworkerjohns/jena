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

import java.io.IOException;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.store.Directory;

/**
 * Provenance written into a Lucene index's commit user data when the index is built,
 * recording the configuration it was built from.
 * <p>
 * Lucene persists commit user data in {@code segments_N}. It survives merges, and it is
 * carried forward by subsequent commits unless explicitly changed, so it costs a few
 * hundred bytes per commit and needs no separate file to keep in step with the index.
 *
 * <h3>What a match does and does not prove</h3>
 *
 * A matching {@code configFingerprint} means the configuration in force when the index
 * was built was <em>shape-equivalent</em> to the one running now. It does not prove that
 * these particular files were produced by this particular configuration file, and it
 * cannot distinguish two indexes built from the same shapes over different data — for
 * that, see {@code pairedDatasetId}.
 */
public class ShaclIndexStamp {

    public static final String KEY_FINGERPRINT = "jena.shacl.configFingerprint";
    public static final String KEY_VERSION     = "jena.shacl.fingerprintVersion";
    public static final String KEY_BUILT_AT    = "jena.shacl.builtAt";
    public static final String KEY_JENA        = "jena.shacl.jenaVersion";
    public static final String KEY_INDEX_ID    = "jena.shacl.indexInstanceId";
    public static final String KEY_PAIRED_ID   = "jena.shacl.pairedDatasetId";

    /** Outcome of comparing an index's stamp against the running configuration. */
    public enum Status {
        /** The index was built from a shape-equivalent configuration. */
        MATCH,
        /** The index was built from a different configuration; a rebuild is probably needed. */
        MISMATCH,
        /**
         * No usable stamp. Either the index predates fingerprinting, or it carries a
         * {@code fingerprintVersion} this build does not understand. Never an error:
         * every index built before this feature shipped is in this state.
         */
        UNKNOWN
    }

    /**
     * @param fingerprint     {@link ShaclConfigFingerprint#fingerprint}, or null
     * @param version         the serialisation version that produced {@code fingerprint}
     * @param builtAt         ISO-8601 instant, informational
     * @param jenaVersion     the build that wrote the stamp, informational
     * @param indexInstanceId minted per index build; identifies these files specifically
     * @param pairedDatasetId the dataset instance this index was built from, or null
     */
    public record StampData(String fingerprint,
                            int version,
                            String builtAt,
                            String jenaVersion,
                            String indexInstanceId,
                            String pairedDatasetId) {}

    private ShaclIndexStamp() {}

    /**
     * Stage a stamp for the writer's next commit. Lucene carries live commit data forward
     * across later commits, so this is called once and persists.
     */
    public static void write(IndexWriter writer, StampData stamp) {
        List<Map.Entry<String, String>> data = new ArrayList<>();
        put(data, KEY_FINGERPRINT, stamp.fingerprint());
        put(data, KEY_VERSION, Integer.toString(stamp.version()));
        put(data, KEY_BUILT_AT, stamp.builtAt());
        put(data, KEY_JENA, stamp.jenaVersion());
        put(data, KEY_INDEX_ID, stamp.indexInstanceId());
        put(data, KEY_PAIRED_ID, stamp.pairedDatasetId());
        writer.setLiveCommitData(data);
    }

    private static void put(List<Map.Entry<String, String>> data, String key, String value) {
        if ( value != null )
            data.add(new SimpleEntry<>(key, value));
    }

    /**
     * Read the stamp committed to disk, or null if the directory holds no index, or an
     * index with no stamp.
     */
    public static StampData read(Directory directory) {
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            return fromUserData(reader.getIndexCommit().getUserData());
        } catch (IndexNotFoundException e) {
            return null;
        } catch (IOException e) {
            throw new TextIndexException("Failed to read index commit data", e);
        }
    }

    /** Build a stamp from raw commit user data; null if the fingerprint key is absent. */
    public static StampData fromUserData(Map<String, String> userData) {
        if ( userData == null )
            return null;
        String fingerprint = userData.get(KEY_FINGERPRINT);
        if ( fingerprint == null )
            return null;
        int version;
        try {
            version = Integer.parseInt(userData.getOrDefault(KEY_VERSION, "-1"));
        } catch (NumberFormatException e) {
            version = -1;
        }
        return new StampData(fingerprint,
                             version,
                             userData.get(KEY_BUILT_AT),
                             userData.get(KEY_JENA),
                             userData.get(KEY_INDEX_ID),
                             userData.get(KEY_PAIRED_ID));
    }

    /**
     * Compare a stamp against the fingerprint of the running configuration.
     * <p>
     * A stamp written by a future (or unreadable) serialisation version is
     * {@link Status#UNKNOWN}, not {@link Status#MISMATCH}: its fingerprint was produced by
     * a different rendering, so comparing the two strings would be meaningless and would
     * report every index as broken the first time the format changes.
     */
    public static Status compare(StampData stamp, String runningFingerprint) {
        if ( stamp == null || stamp.fingerprint() == null )
            return Status.UNKNOWN;
        if ( stamp.version() != ShaclConfigFingerprint.FINGERPRINT_VERSION )
            return Status.UNKNOWN;
        return stamp.fingerprint().equals(runningFingerprint) ? Status.MATCH : Status.MISMATCH;
    }

    /**
     * Compare the dataset this index was built from against the dataset now attached.
     * <p>
     * Separate from {@link #compare} because it answers a different question: not "is this
     * index valid for this configuration" but "do these two artifacts know about each
     * other". Two indexes built from identical shapes over different data have the same
     * fingerprint and different paired ids, which is exactly the crossed-wires case that
     * the fingerprint alone cannot see.
     *
     * @return {@link Status#UNKNOWN} if either side is unknown — an unpaired index is not
     *         evidence of a problem.
     */
    public static Status comparePairing(StampData stamp, String currentDatasetId) {
        if ( stamp == null || stamp.pairedDatasetId() == null || currentDatasetId == null )
            return Status.UNKNOWN;
        return stamp.pairedDatasetId().equals(currentDatasetId) ? Status.MATCH : Status.MISMATCH;
    }
}
