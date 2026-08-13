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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

/**
 * A stable identity for a dataset directory, kept in a small sidecar file.
 * <p>
 * This exists to answer one question the configuration fingerprint cannot: two indexes
 * built from identical shapes over different data have the same fingerprint, so if a
 * deployment mounts one dataset's storage alongside another dataset's index, every check
 * based on configuration alone reports healthy while queries return confidently wrong
 * answers. An identifier minted once and stored beside the data makes that visible.
 * <p>
 * The identifier is minted on first use and never recomputed, so it survives the dataset
 * being moved or copied to a different path — which is the point: building on one machine
 * and serving from another must not look like a different dataset.
 *
 * <h3>Placement</h3>
 *
 * The file sits in the TDB2 <em>container</em> directory, alongside {@code Data-NNNN}.
 * That is safe: {@code DatabaseOps.cleanDatabaseDirectory} removes only {@code -tmp}
 * compaction directories and files listed in {@code jena-tdb-temp-files}, so an unknown
 * file is left alone. It also survives compaction, which creates a new {@code Data-NNNN}
 * inside the same container directory.
 * <p>
 * It does <em>not</em> survive a backup and restore through {@code .nq.gz}, which produces
 * a fresh directory. A restored dataset mints a new identifier and any index built against
 * the original will report an unpaired index rather than a match.
 */
public class DatasetInstanceId {

    /** Sidecar filename, in Java properties format so it needs no parser. */
    public static final String FILENAME = "jena-dataset-id.properties";

    private static final String PROP_ID      = "datasetId";
    private static final String PROP_CREATED = "created";

    private DatasetInstanceId() {}

    /**
     * Read the identifier for a dataset directory, or null if the directory does not
     * exist or holds no sidecar. Never mints — use this on the read path so that merely
     * inspecting a dataset does not modify it.
     */
    public static String read(Path datasetDirectory) {
        if ( datasetDirectory == null )
            return null;
        Path file = datasetDirectory.resolve(FILENAME);
        if ( !Files.isRegularFile(file) )
            return null;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            // A damaged sidecar is not worth failing a query over; treat it as absent.
            return null;
        }
        String id = props.getProperty(PROP_ID);
        return (id == null || id.isBlank()) ? null : id;
    }

    /**
     * Read the identifier, minting and storing one if absent.
     * <p>
     * Creation uses {@link StandardOpenOption#CREATE_NEW}, so if two processes race, one
     * wins and the other re-reads the winner's value rather than overwriting it.
     *
     * @return the identifier, or null if the directory is missing or not writable — an
     *         unwritable dataset directory is a normal read-only deployment, not an error.
     */
    public static String readOrMint(Path datasetDirectory) {
        String existing = read(datasetDirectory);
        if ( existing != null )
            return existing;
        if ( datasetDirectory == null || !Files.isDirectory(datasetDirectory) )
            return null;

        Properties props = new Properties();
        String minted = UUID.randomUUID().toString();
        props.setProperty(PROP_ID, minted);
        props.setProperty(PROP_CREATED, Instant.now().toString());

        Path file = datasetDirectory.resolve(FILENAME);
        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            props.store(out, "Jena dataset instance identity. Safe to copy with the dataset; do not edit.");
            return minted;
        } catch (FileAlreadyExistsException e) {
            // Lost a race - the winner's value is the right one.
            return read(datasetDirectory);
        } catch (IOException e) {
            return null;
        }
    }
}
