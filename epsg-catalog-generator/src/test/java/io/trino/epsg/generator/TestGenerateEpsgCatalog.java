/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.epsg.generator;

import io.airlift.compress.v3.zstd.ZstdJavaDecompressor;
import io.trino.epsg.generator.GenerateEpsgCatalog.OperationEntry;
import io.trino.epsg.generator.GenerateEpsgCatalog.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarFile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestGenerateEpsgCatalog
{
    @TempDir
    public Path directory;

    @Test
    public void testWriterOrderingAndBlocks()
            throws Exception
    {
        Map<Integer, byte[]> definitions = new HashMap<>();
        for (int i = 65; i > 0; i--) {
            definitions.put(i, ("CRS é " + i).getBytes(UTF_8));
        }
        Path first = directory.resolve("first.bin");
        Path second = directory.resolve("second.bin");
        Map<Integer, OperationEntry> operations = Map.of(10, new OperationEntry(10, "operation".getBytes(UTF_8)));
        Map<Pair, List<Integer>> pairs = Map.of(new Pair(1, 2), List.of(10));
        GenerateEpsgCatalog.writeCatalog(first, definitions, operations, pairs);
        GenerateEpsgCatalog.writeCatalog(second, new TreeMap<>(definitions), operations, pairs);
        assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));

        try (DataInputStream input = new DataInputStream(Files.newInputStream(first))) {
            assertThat(input.readInt()).isEqualTo(0x54524745);
            assertThat(input.readInt()).isEqualTo(1);
            assertThat(input.readInt()).isEqualTo(65);
            int[] offsets = new int[65];
            int[] lengths = new int[65];
            for (int i = 0; i < 65; i++) {
                assertThat(input.readInt()).isEqualTo(i + 1);
                assertThat(input.readInt()).isEqualTo(i / 64);
                offsets[i] = input.readInt();
                lengths[i] = input.readInt();
            }
            assertThat(input.readInt()).isEqualTo(2);
            for (int block = 0; block < 2; block++) {
                byte[] raw = new byte[input.readInt()];
                byte[] compressed = input.readNBytes(input.readInt());
                assertThat(new ZstdJavaDecompressor().decompress(compressed, 0, compressed.length, raw, 0, raw.length)).isEqualTo(raw.length);
                for (int i = block * 64; i < Math.min((block + 1) * 64, 65); i++) {
                    assertThat(new String(raw, offsets[i], lengths[i], UTF_8)).isEqualTo("CRS é " + (i + 1));
                }
            }
        }
    }

    @Test
    public void testNoticesComeFromEmbeddedDataJar()
            throws Exception
    {
        Path jarPath = Path.of(Class.forName("org.apache.sis.resources.embedded.EmbeddedResources")
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        Path license = directory.resolve("license.txt");
        GenerateEpsgCatalog.copyResource(jarPath, "META-INF/LICENSE", license);
        try (JarFile jar = new JarFile(jarPath.toFile());
                var input = jar.getInputStream(jar.getJarEntry("META-INF/LICENSE"))) {
            assertThat(Files.readAllBytes(license)).isEqualTo(input.readAllBytes());
        }
        assertThat(Files.readString(license)).contains("EPSG dataset terms of use", "IOGP");
        Path notice = directory.resolve("notice.txt");
        GenerateEpsgCatalog.copyResource(jarPath, "META-INF/NOTICE", notice);
        assertThat(Files.readString(notice)).contains("International Association of Oil and Gas Producers");
        assertThatThrownBy(() -> GenerateEpsgCatalog.copyResource(jarPath, "missing", license))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("EPSG dataset resource not found");
    }

    @Test
    public void testIncrementalGenerationChecksAllOutputs()
            throws Exception
    {
        Path stamp = directory.resolve("generation.stamp");
        Files.writeString(stamp, "");
        assertThat(GenerateEpsgCatalog.isUpToDate(directory, stamp, "input")).isFalse();
        List<String> outputs = List.of("epsg-catalog.bin", "EPSG-LICENSE.txt", "EPSG-NOTICE.txt", "catalog.properties", "skipped-definitions.tsv");
        for (String name : outputs) {
            Files.writeString(directory.resolve(name), name);
        }
        Files.writeString(stamp, GenerateEpsgCatalog.outputFingerprint(directory, "input"));
        assertThat(GenerateEpsgCatalog.isUpToDate(directory, stamp, "input")).isTrue();
        assertThat(GenerateEpsgCatalog.isUpToDate(directory, stamp, "changed-input")).isFalse();
        for (String name : outputs) {
            Files.writeString(directory.resolve(name), "changed");
            assertThat(GenerateEpsgCatalog.isUpToDate(directory, stamp, "input")).isFalse();
            Files.writeString(directory.resolve(name), name);
            Files.delete(directory.resolve(name));
            assertThat(GenerateEpsgCatalog.isUpToDate(directory, stamp, "input")).isFalse();
            Files.writeString(directory.resolve(name), name);
        }
        Files.delete(stamp);
    }
}
