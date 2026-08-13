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

import java.nio.file.Path;

import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphWrapper;
import org.apache.jena.tdb2.store.DatasetGraphSwitchable;

/**
 * Finding the on-disk home of a dataset, when it has one.
 * <p>
 * Used to locate the {@link DatasetInstanceId} sidecar. Everything here returns null
 * rather than throwing for a dataset that is in memory, is not TDB2, or is otherwise not
 * backed by a directory — those are ordinary configurations, not failures.
 */
public class DatasetLocations {

    private DatasetLocations() {}

    /**
     * The TDB2 container directory backing a dataset — the directory holding
     * {@code Data-NNNN} — or null if there is not one.
     * <p>
     * Unwraps {@link DatasetGraphWrapper} layers, because a text-indexed dataset presents
     * as a wrapper around the storage it is indexing.
     */
    public static Path tdb2ContainerPath(DatasetGraph dsg) {
        DatasetGraph current = dsg;
        // Bounded rather than while(true): a cyclic wrapper chain should not hang startup.
        for ( int depth = 0 ; current != null && depth < 20 ; depth++ ) {
            if ( current instanceof DatasetGraphSwitchable switchable )
                return switchable.getContainerPath();
            if ( current instanceof DatasetGraphWrapper wrapper ) {
                current = wrapper.getWrapped();
                continue;
            }
            return null;
        }
        return null;
    }

    /**
     * The identity of the dataset backing {@code dsg}, minting one if the dataset is on
     * disk and has none yet. Null for an in-memory or non-TDB2 dataset.
     */
    public static String datasetInstanceId(DatasetGraph dsg, boolean mintIfAbsent) {
        Path container = tdb2ContainerPath(dsg);
        if ( container == null )
            return null;
        return mintIfAbsent ? DatasetInstanceId.readOrMint(container) : DatasetInstanceId.read(container);
    }
}
