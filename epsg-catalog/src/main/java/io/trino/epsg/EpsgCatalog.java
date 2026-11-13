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

import io.airlift.compress.v3.zstd.ZstdJavaDecompressor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeSet;

import static java.nio.charset.CodingErrorAction.REPORT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.unmodifiableNavigableSet;
import static java.util.Objects.requireNonNull;

/**
 * An immutable, thread-safe catalog of EPSG CRS and coordinate-operation definitions in WKT2:2019.
 * Indexes and compressed blocks are read eagerly; definitions are decompressed on demand without caching.
 * Definitions that SIS cannot create or serialize are recorded in the bundled skipped-definitions.tsv.
 * This class does not parse WKT, choose coordinate operations, change axis order, or require SIS or a database.
 */
public final class EpsgCatalog
{
    private static final int MAGIC = 0x54524745; // TRGE: retain compatibility with Trino format v1
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_CATALOG_BYTES = 64 * 1024 * 1024;
    private static final int MAX_BLOCK_BYTES = 8 * 1024 * 1024;
    private static final String CATALOG_RESOURCE = "/io/trino/epsg/epsg-catalog.bin";

    private final WktSection crsWkts;
    private final WktSection operationWkts;
    private final Map<Pair, List<Integer>> operationCodesByPair;

    private EpsgCatalog(WktSection crsWkts, WktSection operationWkts, Map<Pair, List<Integer>> operationCodesByPair)
    {
        this.crsWkts = crsWkts;
        this.operationWkts = operationWkts;
        this.operationCodesByPair = Map.copyOf(operationCodesByPair);
    }

    /**
     * Loads the catalog bundled in this artifact.
     *
     * @return the immutable bundled catalog
     * @throws IOException if the bundled catalog cannot be read
     */
    public static EpsgCatalog loadDefault()
            throws IOException
    {
        try (InputStream input = EpsgCatalog.class.getResourceAsStream(CATALOG_RESOURCE)) {
            if (input == null) {
                throw new IOException("EPSG catalog resource not found: " + CATALOG_RESOURCE);
            }
            return read(input);
        }
    }

    /**
     * Reads a format-v1 catalog of at most 64 MiB, without closing the caller's stream.
     * Individual uncompressed blocks are limited to 8 MiB. Invalid indexes, unsupported versions,
     * truncated input, and trailing data are rejected with IOException. Compressed-block and UTF-8
     * corruption is detected lazily during lookup and reported as IOException.
     *
     * @param input the catalog stream
     * @return the immutable catalog
     * @throws IOException if the catalog cannot be read or its structure is invalid
     */
    public static EpsgCatalog read(InputStream input)
            throws IOException
    {
        requireNonNull(input, "input is null");
        byte[] bytes = input.readNBytes(MAX_CATALOG_BYTES + 1);
        if (bytes.length > MAX_CATALOG_BYTES) {
            throw new IOException("EPSG catalog exceeds 64 MiB");
        }
        ByteBuffer data = ByteBuffer.wrap(bytes);
        try {
            if (data.getInt() != MAGIC) {
                throw new IOException("Invalid EPSG catalog magic");
            }
            int version = data.getInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported EPSG catalog version: " + version);
            }
            WktSection crs = WktSection.read(data);
            WktSection operations = WktSection.read(data);
            int pairCount = count(data, 12, "pair count");
            Map<Pair, List<Integer>> pairs = new HashMap<>();
            Pair previous = new Pair(0, 0);
            for (int i = 0; i < pairCount; i++) {
                Pair pair = new Pair(data.getInt(), data.getInt());
                if (pair.compareTo(previous) <= 0 || pair.source() <= 0 || pair.target() <= 0) {
                    throw new IOException("Invalid or unsorted EPSG operation pair: " + pair);
                }
                previous = pair;
                int operationCount = count(data, Integer.BYTES, "operation count");
                if (operationCount == 0) {
                    throw new IOException("Empty EPSG operation pair");
                }
                List<Integer> codes = new ArrayList<>(operationCount);
                int previousCode = 0;
                for (int j = 0; j < operationCount; j++) {
                    int code = data.getInt();
                    if (code <= previousCode || !operations.contains(code)) {
                        throw new IOException("Invalid or unsorted EPSG operation reference: " + code);
                    }
                    codes.add(code);
                    previousCode = code;
                }
                pairs.put(pair, List.copyOf(codes));
            }
            if (data.hasRemaining()) {
                throw new IOException("Trailing data in EPSG catalog");
            }
            return new EpsgCatalog(crs, operations, pairs);
        }
        catch (BufferUnderflowException e) {
            throw new IOException("Truncated EPSG catalog", e);
        }
    }

    /**
     * Returns whether a CRS definition exists for the numeric EPSG code.
     *
     * @param code the numeric EPSG code
     * @return true if a CRS definition exists
     */
    public boolean hasCrsCode(int code)
    {
        return crsWkts.contains(code);
    }

    /**
     * Returns whether an operation definition exists for the numeric EPSG code.
     *
     * @param code the numeric EPSG code
     * @return true if an operation definition exists
     */
    public boolean hasOperationCode(int code)
    {
        return operationWkts.contains(code);
    }

    /**
     * Returns the WKT2:2019 CRS definition, or empty for an unknown code.
     *
     * @param code the numeric EPSG code
     * @return the CRS definition, or empty if absent
     * @throws IOException if the definition is corrupt
     */
    public Optional<String> crsWkt(int code)
            throws IOException
    {
        return crsWkts.wkt(code);
    }

    /**
     * Returns the WKT2:2019 operation definition, or empty for an unknown code.
     *
     * @param code the numeric EPSG code
     * @return the operation definition, or empty if absent
     * @throws IOException if the definition is corrupt
     */
    public Optional<String> operationWkt(int code)
            throws IOException
    {
        return operationWkts.wkt(code);
    }

    /**
     * Returns the immutable, numerically sorted operation codes for the directed CRS pair.
     * An unknown pair returns an empty list. Reverse or concatenated operations are not synthesized.
     * Pair endpoints may refer to EPSG CRS codes whose standalone WKT is absent from this catalog.
     *
     * @param sourceCode the source CRS EPSG code
     * @param targetCode the target CRS EPSG code
     * @return the operation codes for the directed pair
     */
    public List<Integer> operationCodes(int sourceCode, int targetCode)
    {
        return operationCodesByPair.getOrDefault(new Pair(sourceCode, targetCode), List.of());
    }

    /**
     * Returns an immutable, numerically sorted set of CRS codes.
     *
     * @return all CRS codes in the catalog
     */
    public NavigableSet<Integer> crsCodes()
    {
        return crsWkts.codeSet;
    }

    /**
     * Returns an immutable, numerically sorted set of operation codes.
     *
     * @return all operation codes in the catalog
     */
    public NavigableSet<Integer> operationCodes()
    {
        return operationWkts.codeSet;
    }

    private static int count(ByteBuffer data, int minimumEntryBytes, String label)
            throws IOException
    {
        int value = data.getInt();
        if (value < 0 || value > data.remaining() / minimumEntryBytes) {
            throw new IOException("Invalid EPSG catalog " + label + ": " + value);
        }
        return value;
    }

    private static final class WktSection
    {
        private final int[] codes;
        private final int[] blocks;
        private final int[] offsets;
        private final int[] lengths;
        private final int[] uncompressedLengths;
        private final byte[][] compressedBlocks;
        private final NavigableSet<Integer> codeSet;

        private WktSection(int[] codes, int[] blocks, int[] offsets, int[] lengths, int[] uncompressedLengths, byte[][] compressedBlocks)
        {
            this.codes = codes;
            this.blocks = blocks;
            this.offsets = offsets;
            this.lengths = lengths;
            this.uncompressedLengths = uncompressedLengths;
            this.compressedBlocks = compressedBlocks;
            NavigableSet<Integer> values = new TreeSet<>();
            for (int code : codes) {
                values.add(code);
            }
            this.codeSet = unmodifiableNavigableSet(values);
        }

        private static WktSection read(ByteBuffer data)
                throws IOException
        {
            int entryCount = count(data, 16, "entry count");
            int[] codes = new int[entryCount];
            int[] blocks = new int[entryCount];
            int[] offsets = new int[entryCount];
            int[] lengths = new int[entryCount];
            int previousCode = 0;
            for (int i = 0; i < entryCount; i++) {
                codes[i] = data.getInt();
                blocks[i] = data.getInt();
                offsets[i] = data.getInt();
                lengths[i] = data.getInt();
                if (codes[i] <= previousCode) {
                    throw new IOException("Unsorted or duplicate EPSG code: " + codes[i]);
                }
                previousCode = codes[i];
            }

            int blockCount = count(data, 8, "block count");
            if (blockCount != (entryCount + 63) / 64) {
                throw new IOException("Invalid EPSG catalog block count");
            }
            int[] uncompressedLengths = new int[blockCount];
            byte[][] compressedBlocks = new byte[blockCount][];
            for (int i = 0; i < blockCount; i++) {
                int length = data.getInt();
                if (length <= 0 || length > MAX_BLOCK_BYTES) {
                    throw new IOException("Invalid EPSG catalog uncompressed block length: " + length);
                }
                uncompressedLengths[i] = length;
                int compressedLength = count(data, 1, "compressed block length");
                if (compressedLength == 0) {
                    throw new IOException("Empty EPSG catalog compressed block");
                }
                compressedBlocks[i] = new byte[compressedLength];
                data.get(compressedBlocks[i]);
            }

            for (int i = 0; i < entryCount; i++) {
                if (blocks[i] != i / 64 || offsets[i] < 0 || lengths[i] <= 0 ||
                        lengths[i] > uncompressedLengths[blocks[i]] ||
                        offsets[i] > uncompressedLengths[blocks[i]] - lengths[i]) {
                    throw new IOException("Invalid EPSG catalog entry for code: " + codes[i]);
                }
                int expectedOffset = 0;
                if (i % 64 != 0) {
                    expectedOffset = offsets[i - 1] + lengths[i - 1];
                }
                if (offsets[i] != expectedOffset ||
                        ((i == entryCount - 1 || blocks[i + 1] != blocks[i]) && offsets[i] + lengths[i] != uncompressedLengths[blocks[i]])) {
                    throw new IOException("Noncontiguous EPSG catalog entry for code: " + codes[i]);
                }
            }
            return new WktSection(codes, blocks, offsets, lengths, uncompressedLengths, compressedBlocks);
        }

        private boolean contains(int code)
        {
            return Arrays.binarySearch(codes, code) >= 0;
        }

        private Optional<String> wkt(int code)
                throws IOException
        {
            int index = Arrays.binarySearch(codes, code);
            if (index < 0) {
                return Optional.empty();
            }
            int blockIndex = blocks[index];
            byte[] output = new byte[uncompressedLengths[blockIndex]];
            byte[] compressed = compressedBlocks[blockIndex];
            try {
                int size = new ZstdJavaDecompressor().decompress(compressed, 0, compressed.length, output, 0, output.length);
                if (size != output.length) {
                    throw new IOException("Invalid EPSG catalog decompressed block length for code: " + code);
                }
                return Optional.of(UTF_8.newDecoder()
                        .onMalformedInput(REPORT)
                        .onUnmappableCharacter(REPORT)
                        .decode(ByteBuffer.wrap(output, offsets[index], lengths[index]))
                        .toString());
            }
            catch (CharacterCodingException e) {
                throw new IOException("Invalid UTF-8 in EPSG definition: " + code, e);
            }
            catch (RuntimeException e) {
                throw new IOException("Corrupt EPSG compressed block for code: " + code, e);
            }
        }
    }

    private record Pair(int source, int target)
            implements Comparable<Pair>
    {
        @Override
        public int compareTo(Pair other)
        {
            int result = Integer.compare(source, other.source);
            if (result == 0) {
                return Integer.compare(target, other.target);
            }
            return result;
        }
    }
}
