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

import io.airlift.compress.v3.zstd.ZstdDecompressor;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.unmodifiableSortedSet;

/**
 * Immutable, thread-safe catalog of the EPSG CRS and coordinate-operation definitions that Apache SIS can
 * create and format, as WKT 2 (2019). Deprecated codes are not included. Definitions are decompressed on
 * each lookup and are not cached.
 */
public final class EpsgCatalog
{
    private static final int MAGIC = 0x54524745; // TRGE
    private static final int FORMAT_VERSION = 1;
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

    public static EpsgCatalog loadDefault()
    {
        try (InputStream input = EpsgCatalog.class.getResourceAsStream(CATALOG_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("EPSG catalog resource not found: " + CATALOG_RESOURCE);
            }
            return read(input);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to read EPSG catalog", e);
        }
    }

    public boolean hasCrsCode(int code)
    {
        return crsWkts.contains(code);
    }

    public boolean hasOperationCode(int code)
    {
        return operationWkts.contains(code);
    }

    public Optional<String> crsWkt(int code)
    {
        return crsWkts.wkt(code);
    }

    public Optional<String> operationWkt(int code)
    {
        return operationWkts.wkt(code);
    }

    /**
     * Returns the codes of the operations from the source CRS to the target CRS, in the preference order of
     * Apache SIS's EPSG factory: by accuracy, area of use, and supersession. Operations in the opposite
     * direction are not included.
     */
    public List<Integer> operationCodesBetween(int sourceCode, int targetCode)
    {
        return operationCodesByPair.getOrDefault(new Pair(sourceCode, targetCode), List.of());
    }

    public SortedSet<Integer> crsCodes()
    {
        return crsWkts.codeSet;
    }

    public SortedSet<Integer> operationCodes()
    {
        return operationWkts.codeSet;
    }

    private static EpsgCatalog read(InputStream input)
            throws IOException
    {
        DataInputStream data = new DataInputStream(new BufferedInputStream(input));
        int magic = data.readInt();
        if (magic != MAGIC) {
            throw new IOException("Invalid EPSG catalog magic: " + magic);
        }
        int version = data.readInt();
        if (version != FORMAT_VERSION) {
            throw new IOException("Unsupported EPSG catalog version: " + version);
        }

        WktSection crsWkts = WktSection.read(data);
        WktSection operationWkts = WktSection.read(data);

        Map<Pair, List<Integer>> operationCodesByPair = new HashMap<>();
        int pairCount = data.readInt();
        for (int i = 0; i < pairCount; i++) {
            Pair pair = new Pair(data.readInt(), data.readInt());
            Integer[] operationCodes = new Integer[data.readInt()];
            for (int j = 0; j < operationCodes.length; j++) {
                operationCodes[j] = data.readInt();
            }
            operationCodesByPair.put(pair, List.of(operationCodes));
        }
        return new EpsgCatalog(crsWkts, operationWkts, operationCodesByPair);
    }

    private static final class WktSection
    {
        private final int[] codes;
        private final int[] blocks;
        private final int[] offsets;
        private final int[] lengths;
        private final int[] uncompressedLengths;
        private final byte[][] compressedBlocks;
        private final SortedSet<Integer> codeSet;

        private WktSection(int[] codes, int[] blocks, int[] offsets, int[] lengths, int[] uncompressedLengths, byte[][] compressedBlocks, SortedSet<Integer> codeSet)
        {
            this.codes = codes;
            this.blocks = blocks;
            this.offsets = offsets;
            this.lengths = lengths;
            this.uncompressedLengths = uncompressedLengths;
            this.compressedBlocks = compressedBlocks;
            this.codeSet = codeSet;
        }

        private static WktSection read(DataInputStream data)
                throws IOException
        {
            int entryCount = data.readInt();
            int[] codes = new int[entryCount];
            int[] blocks = new int[entryCount];
            int[] offsets = new int[entryCount];
            int[] lengths = new int[entryCount];
            SortedSet<Integer> codeSet = new TreeSet<>();
            for (int i = 0; i < entryCount; i++) {
                codes[i] = data.readInt();
                blocks[i] = data.readInt();
                offsets[i] = data.readInt();
                lengths[i] = data.readInt();
                codeSet.add(codes[i]);
            }

            int blockCount = data.readInt();
            int[] uncompressedLengths = new int[blockCount];
            byte[][] compressedBlocks = new byte[blockCount][];
            for (int i = 0; i < blockCount; i++) {
                uncompressedLengths[i] = data.readInt();
                compressedBlocks[i] = new byte[data.readInt()];
                data.readFully(compressedBlocks[i]);
            }
            return new WktSection(codes, blocks, offsets, lengths, uncompressedLengths, compressedBlocks, unmodifiableSortedSet(codeSet));
        }

        private boolean contains(int code)
        {
            return Arrays.binarySearch(codes, code) >= 0;
        }

        private Optional<String> wkt(int code)
        {
            int index = Arrays.binarySearch(codes, code);
            if (index < 0) {
                return Optional.empty();
            }
            int block = blocks[index];
            byte[] output = new byte[uncompressedLengths[block]];
            int length = ZstdDecompressor.create().decompress(compressedBlocks[block], 0, compressedBlocks[block].length, output, 0, output.length);
            if (length != output.length) {
                throw new IllegalStateException("Invalid EPSG catalog block length: expected %s bytes, but was %s".formatted(output.length, length));
            }
            return Optional.of(new String(output, offsets[index], lengths[index], UTF_8));
        }
    }

    private record Pair(int source, int target) {}
}
