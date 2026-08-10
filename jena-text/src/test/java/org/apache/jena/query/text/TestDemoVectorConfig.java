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

import static org.junit.Assume.assumeNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.embedding.EmbeddingConfig;
import org.apache.jena.query.text.embedding.EmbeddingProvider;
import org.apache.jena.query.text.embedding.EmbeddingProviders;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.Test;

/**
 * Checks that the demo's vector configuration actually assembles.
 * <p>
 * The demo config is a documented artifact — it is what a first-time user copies — but
 * nothing else in the build exercises it. The failure it guards against is specific and
 * quiet: {@code idx:dimension} drifting out of step with what the configured provider
 * produces. That combination is caught at assembly time in production, which means it
 * surfaces as a server that will not start, long after the edit that caused it.
 */
public class TestDemoVectorConfig {

    private static final Property TEXT_SHAPES =
        ResourceFactory.createProperty("http://jena.apache.org/text#shapes");

    private static final Path DEMO_CONFIG = findDemoConfig();

    private static Path findDemoConfig() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("demo/test/config.ttl");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    @Test
    public void testDemoVectorFieldMatchesItsProvider() {
        assumeNotNull(DEMO_CONFIG);

        Model model = RDFDataMgr.loadModel(DEMO_CONFIG.toUri().toString());
        Resource index = model.listResourcesWithProperty(TEXT_SHAPES).next();

        ShaclIndexMapping mapping = ShaclIndexAssembler.parseShapes(
            Assembler.general(), index.getPropertyResourceValue(TEXT_SHAPES));

        List<FieldDef> vectorFields = new ArrayList<>();
        for (IndexProfile profile : mapping.getProfiles()) {
            vectorFields.addAll(profile.getVectorFields());
        }
        assertFalse("The demo config declares no idx:vectorField", vectorFields.isEmpty());

        EmbeddingConfig embeddingConfig = ShaclIndexAssembler.parseEmbeddingConfig(index);
        assertNotNull("The demo config declares a vector field but no idx:embedding block",
            embeddingConfig);

        EmbeddingProvider provider = EmbeddingProviders.create(embeddingConfig);
        for (FieldDef field : vectorFields) {
            assertEquals("Demo vector field '" + field.getFieldName() + "' declares idx:dimension "
                    + field.getVectorDef().dimension() + " but provider '" + embeddingConfig.provider()
                    + "' produces " + provider.dimension() + "-dimensional vectors",
                provider.dimension(), field.getVectorDef().dimension());

            assertFalse("A vector field must not be idx:defaultSearch", field.isDefaultSearch());
            assertTrue("Vector field '" + field.getFieldName() + "' has no idx:embeddingSource",
                field.getVectorDef().sourceFieldIRIs().size() > 0);
        }
    }
}
