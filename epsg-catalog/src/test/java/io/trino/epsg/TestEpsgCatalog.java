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
package io.trino.epsg;

import io.airlift.compress.v3.zstd.ZstdJavaCompressor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestEpsgCatalog
{
    @Test
    public void testLookupAndImmutability()
            throws Exception
    {
        EpsgCatalog catalog = EpsgCatalog.read(new ByteArrayInputStream(fixture()));
        assertThat(catalog.crsCodes()).containsExactly(1, 2);
        assertThat(catalog.operationCodes()).containsExactly(10, 11);
        assertThat(catalog.hasCrsCode(1)).isTrue();
        assertThat(catalog.hasOperationCode(10)).isTrue();
        assertThat(catalog.crsWkt(1)).contains("CRS é");
        assertThat(catalog.operationWkt(10)).contains("OP 10");
        assertThat(catalog.crsWkt(999)).isEmpty();
        assertThat(catalog.operationWkt(-1)).isEmpty();
        assertThat(catalog.hasCrsCode(-1)).isFalse();
        assertThat(catalog.hasOperationCode(999)).isFalse();
        assertThat(catalog.operationCodes(1, 2)).containsExactly(10, 11);
        assertThat(catalog.operationCodes(2, 1)).isEmpty();
        assertThatThrownBy(() -> catalog.crsCodes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.operationCodes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.operationCodes(1, 2).set(0, 99)).isInstanceOf(UnsupportedOperationException.class);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<String>> definitions = IntStream.range(0, 256)
                    .mapToObj(_ -> executor.submit(() -> catalog.crsWkt(1).orElseThrow()))
                    .toList();
            for (Future<String> definition : definitions) {
                assertThat(definition.get()).isEqualTo("CRS é");
            }
        }
    }

    @Test
    public void testPairEndpointsDoNotRequireStandaloneCrsDefinitions()
            throws Exception
    {
        byte[] bytes = fixture();
        ByteBuffer.wrap(bytes).putInt(bytes.length - 20, 999).putInt(bytes.length - 16, 998);
        EpsgCatalog catalog = EpsgCatalog.read(new ByteArrayInputStream(bytes));
        assertThat(catalog.crsWkt(999)).isEmpty();
        assertThat(catalog.operationCodes(999, 998)).containsExactly(10, 11);
    }

    @Test
    public void testMultipleBlocks()
            throws Exception
    {
        Map<Integer, byte[]> crs = new TreeMap<>();
        for (int code = 1; code <= 129; code++) {
            crs.put(code, ("CRS " + code).getBytes(UTF_8));
        }
        EpsgCatalog catalog = EpsgCatalog.read(new ByteArrayInputStream(catalog(crs)));
        assertThat(catalog.crsCodes()).hasSize(129);
        for (int code : catalog.crsCodes()) {
            assertThat(catalog.crsWkt(code)).contains("CRS " + code);
        }
    }

    @Test
    public void testEmptyCatalog()
            throws Exception
    {
        EpsgCatalog catalog = EpsgCatalog.read(new ByteArrayInputStream(catalog(Map.of())));
        assertThat(catalog.crsCodes()).isEmpty();
        assertThat(catalog.operationCodes()).isEmpty();
    }

    @Test
    public void testCallerOwnsStream()
            throws Exception
    {
        class TrackingInputStream
                extends ByteArrayInputStream
        {
            private boolean closed;

            TrackingInputStream(byte[] bytes)
            {
                super(bytes);
            }

            @Override
            public void close()
                    throws IOException
            {
                closed = true;
                super.close();
            }
        }

        TrackingInputStream input = new TrackingInputStream(fixture());
        EpsgCatalog.read(input);
        assertThat(input.closed).isFalse();
        assertThatThrownBy(() -> EpsgCatalog.read(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    public void testReadFailure()
    {
        IOException failure = new IOException("Read failed");
        InputStream input = new InputStream()
        {
            @Override
            public int read()
                    throws IOException
            {
                throw failure;
            }
        };
        assertThatThrownBy(() -> EpsgCatalog.read(input)).isSameAs(failure);
    }

    @Test
    public void testTruncationAndTrailingBytes()
            throws Exception
    {
        byte[] bytes = fixture();
        for (int length = 0; length < bytes.length; length++) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            assertThatThrownBy(() -> EpsgCatalog.read(new ByteArrayInputStream(truncated)))
                    .as("prefix of length %s", length)
                    .isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> EpsgCatalog.read(new ByteArrayInputStream(Arrays.copyOf(bytes, bytes.length + 1))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Trailing data");
    }

    @Test
    public void testInvalidIndexes()
            throws Exception
    {
        byte[] bytes = fixture();
        for (int[] mutation : List.of(
                new int[] {0, 0},
                new int[] {4, 2},
                new int[] {8, -1},
                new int[] {8, Integer.MAX_VALUE},
                new int[] {12, 0},
                new int[] {28, 1},
                new int[] {16, -1},
                new int[] {16, 1},
                new int[] {20, -1},
                new int[] {20, Integer.MAX_VALUE},
                new int[] {24, 0},
                new int[] {24, Integer.MAX_VALUE},
                new int[] {40, 999},
                new int[] {44, -1},
                new int[] {44, 2},
                new int[] {48, Integer.MAX_VALUE},
                new int[] {52, -1})) {
            byte[] corrupt = bytes.clone();
            ByteBuffer.wrap(corrupt).putInt(mutation[0], mutation[1]);
            assertThatThrownBy(() -> EpsgCatalog.read(new ByteArrayInputStream(corrupt)))
                    .as("offset %s, value %s", mutation[0], mutation[1])
                    .isInstanceOf(IOException.class);
        }
        for (int[] mutation : List.of(
                new int[] {bytes.length - 20, 0},
                new int[] {bytes.length - 16, 0},
                new int[] {bytes.length - 12, 0},
                new int[] {bytes.length - 8, 999},
                new int[] {bytes.length - 4, 10})) {
            byte[] corrupt = bytes.clone();
            ByteBuffer.wrap(corrupt).putInt(mutation[0], mutation[1]);
            assertThatThrownBy(() -> EpsgCatalog.read(new ByteArrayInputStream(corrupt)))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    public void testLazyCorruptionDetection()
            throws Exception
    {
        byte[] bytes = fixture();
        int compressedLength = ByteBuffer.wrap(bytes).getInt(52);
        Arrays.fill(bytes, 56, 56 + compressedLength, (byte) 0);
        EpsgCatalog corrupt = EpsgCatalog.read(new ByteArrayInputStream(bytes));
        assertThat(corrupt.crsCodes()).containsExactly(1, 2);
        assertThatThrownBy(() -> corrupt.crsWkt(1)).isInstanceOf(IOException.class);

        EpsgCatalog invalidUtf8 = EpsgCatalog.read(new ByteArrayInputStream(catalog(Map.of(1, new byte[] {(byte) 0xFF}))));
        assertThatThrownBy(() -> invalidUtf8.crsWkt(1)).isInstanceOf(IOException.class);

        EpsgCatalog invalidOperationUtf8 = EpsgCatalog.read(new ByteArrayInputStream(catalog(Map.of(), Map.of(1, new byte[] {(byte) 0xFF}))));
        assertThatThrownBy(() -> invalidOperationUtf8.operationWkt(1)).isInstanceOf(IOException.class);
    }

    @Test
    public void testBundledCatalog()
            throws Exception
    {
        EpsgCatalog catalog = EpsgCatalog.loadDefault();
        assertThat(catalog.crsWkt(4326).orElseThrow()).contains("WGS 84", "4326");
        assertThat(catalog.crsWkt(3857).orElseThrow()).contains("Pseudo-Mercator", "3857");
        assertThat(catalog.crsCodes()).hasSizeGreaterThan(5000);
        assertThat(catalog.operationCodes()).isNotEmpty();
        for (int code : catalog.crsCodes()) {
            assertThat(catalog.crsWkt(code).orElseThrow()).isNotBlank();
        }
        for (int code : catalog.operationCodes()) {
            assertThat(catalog.operationWkt(code).orElseThrow()).isNotBlank();
        }
        Properties provenance = new Properties();
        try (var input = EpsgCatalog.class.getResourceAsStream("catalog.properties")) {
            provenance.load(input);
        }
        assertThat(provenance.getProperty("crs.count")).isEqualTo(Integer.toString(catalog.crsCodes().size()));
        assertThat(provenance.getProperty("operation.count")).isEqualTo(Integer.toString(catalog.operationCodes().size()));
        assertThat(provenance.getProperty("epsg.version")).isNotBlank();
        assertThat(provenance.getProperty("sis.version")).isEqualTo("1.7");
        assertThat(provenance.getProperty("source.sha256")).hasSize(64);
        try (var input = EpsgCatalog.class.getResourceAsStream("epsg-catalog.bin")) {
            assertThat(provenance.getProperty("catalog.sha256"))
                    .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
        }
        try (var input = EpsgCatalog.class.getResourceAsStream("EPSG-LICENSE.txt")) {
            assertThat(new String(input.readAllBytes(), UTF_8)).contains("EPSG dataset terms of use", "IOGP");
        }
        try (var input = EpsgCatalog.class.getResourceAsStream("EPSG-NOTICE.txt")) {
            assertThat(new String(input.readAllBytes(), UTF_8)).contains("International Association of Oil and Gas Producers");
        }
        assertThatThrownBy(() -> Class.forName("org.apache.sis.referencing.CRS")).isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("org.hsqldb.jdbc.JDBCDriver")).isInstanceOf(ClassNotFoundException.class);
    }

    private static byte[] fixture()
            throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x54524745);
            output.writeInt(1);
            section(output, Map.of(1, "CRS é".getBytes(UTF_8), 2, "CRS 2".getBytes(UTF_8)));
            section(output, Map.of(10, "OP 10".getBytes(UTF_8), 11, "OP 11".getBytes(UTF_8)));
            output.writeInt(1);
            output.writeInt(1);
            output.writeInt(2);
            output.writeInt(2);
            output.writeInt(10);
            output.writeInt(11);
        }
        return bytes.toByteArray();
    }

    private static byte[] catalog(Map<Integer, byte[]> crs)
            throws IOException
    {
        return catalog(crs, Map.of());
    }

    private static byte[] catalog(Map<Integer, byte[]> crs, Map<Integer, byte[]> operations)
            throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x54524745);
            output.writeInt(1);
            section(output, crs);
            section(output, operations);
            output.writeInt(0);
        }
        return bytes.toByteArray();
    }

    private static void section(DataOutputStream output, Map<Integer, byte[]> definitions)
            throws IOException
    {
        var entries = List.copyOf(new TreeMap<>(definitions).entrySet());
        output.writeInt(entries.size());
        int offset = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (i % 64 == 0) {
                offset = 0;
            }
            output.writeInt(entries.get(i).getKey());
            output.writeInt(i / 64);
            output.writeInt(offset);
            output.writeInt(entries.get(i).getValue().length);
            offset += entries.get(i).getValue().length;
        }
        output.writeInt((entries.size() + 63) / 64);
        for (int i = 0; i < entries.size(); i += 64) {
            ByteArrayOutputStream block = new ByteArrayOutputStream();
            for (int j = i; j < Math.min(i + 64, entries.size()); j++) {
                block.write(entries.get(j).getValue());
            }
            byte[] raw = block.toByteArray();
            var compressor = new ZstdJavaCompressor();
            byte[] compressed = new byte[compressor.maxCompressedLength(raw.length)];
            int length = compressor.compress(raw, 0, raw.length, compressed, 0, compressed.length);
            output.writeInt(raw.length);
            output.writeInt(length);
            output.write(compressed, 0, length);
        }
    }
}
