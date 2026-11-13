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

import io.airlift.compress.v3.zstd.ZstdCompressor;
import io.airlift.compress.v3.zstd.ZstdJavaCompressor;
import org.apache.sis.io.wkt.Convention;
import org.apache.sis.io.wkt.UnformattableObjectException;
import org.apache.sis.io.wkt.WKTFormat;
import org.apache.sis.io.wkt.Warnings;
import org.apache.sis.referencing.CRS;
import org.apache.sis.resources.embedded.EmbeddedResources;
import org.opengis.metadata.Identifier;
import org.opengis.referencing.IdentifiedObject;
import org.opengis.referencing.ReferenceIdentifier;
import org.opengis.referencing.crs.CRSAuthorityFactory;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.CoordinateOperation;
import org.opengis.referencing.operation.CoordinateOperationAuthorityFactory;
import org.opengis.util.FactoryException;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Build-time command that generates the catalog and copies the EPSG license and notices.
 */
public final class GenerateEpsgCatalog
{
    private static final int MAGIC = 0x54524745; // TRGE
    private static final int FORMAT_VERSION = 1;
    private static final int WKT_BLOCK_RECORD_COUNT = 64;

    private GenerateEpsgCatalog() {}

    public static void main(String[] args)
            throws Exception
    {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: GenerateEpsgCatalog <generated-resources-directory>");
        }
        Path resourceDirectory = Path.of(args[0]).resolve("io/trino/epsg");
        Files.createDirectories(resourceDirectory);

        CRSAuthorityFactory crsFactory = CRS.getAuthorityFactory("EPSG");
        CoordinateOperationAuthorityFactory operationFactory = (CoordinateOperationAuthorityFactory) crsFactory;
        WKTFormat format = new WKTFormat(Locale.ROOT, (ZoneId) null);
        format.setConvention(Convention.WKT2_2019);
        format.setIndentation(WKTFormat.SINGLE_LINE);

        Map<Integer, byte[]> crsWkts = new TreeMap<>();
        Map<String, Integer> skippedCrs = new TreeMap<>();
        for (String code : crsFactory.getAuthorityCodes(CoordinateReferenceSystem.class)) {
            try {
                crsWkts.put(parseEpsgCode(code), formatWkt(format, crsFactory.createCoordinateReferenceSystem(code), code));
            }
            catch (FactoryException | UnformattableObjectException e) {
                skippedCrs.merge(e.getClass().getSimpleName(), 1, Integer::sum);
            }
        }

        Map<Integer, byte[]> operationWkts = new TreeMap<>();
        Map<Pair, List<Integer>> operationCodesByPair = new TreeMap<>();
        Map<String, Integer> skippedOperations = new TreeMap<>();
        for (String code : operationFactory.getAuthorityCodes(CoordinateOperation.class)) {
            try {
                CoordinateOperation operation = operationFactory.createCoordinateOperation(code);
                // SIS writes operations without a source or target CRS, such as map-projection conversions, without
                // SOURCECRS and TARGETCRS, and cannot parse them back
                if (operation.getSourceCRS() == null || operation.getTargetCRS() == null) {
                    skippedOperations.merge("MissingSourceOrTargetCrs", 1, Integer::sum);
                    continue;
                }
                int operationCode = parseEpsgCode(code);
                operationWkts.put(operationCode, formatWkt(format, operation, code));
                OptionalInt source = epsgIdentifier(operation.getSourceCRS());
                OptionalInt target = epsgIdentifier(operation.getTargetCRS());
                if (source.isPresent() && target.isPresent()) {
                    operationCodesByPair.computeIfAbsent(new Pair(source.orElseThrow(), target.orElseThrow()), _ -> new ArrayList<>()).add(operationCode);
                }
            }
            catch (FactoryException | UnformattableObjectException e) {
                skippedOperations.merge(e.getClass().getSimpleName(), 1, Integer::sum);
            }
        }

        for (Entry<Pair, List<Integer>> entry : operationCodesByPair.entrySet()) {
            entry.setValue(preferenceOrder(operationFactory, entry.getKey(), entry.getValue()));
        }

        Path catalog = resourceDirectory.resolve("epsg-catalog.bin");
        writeCatalog(catalog, crsWkts, operationWkts, operationCodesByPair);
        Path datasetJar = Path.of(EmbeddedResources.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        copyResource(datasetJar, "META-INF/LICENSE", resourceDirectory.resolve("EPSG-LICENSE.txt"));
        copyResource(datasetJar, "META-INF/NOTICE", resourceDirectory.resolve("EPSG-NOTICE.txt"));

        System.out.printf(
                "Generated %s with %,d CRS definitions, %,d operations, %,d operation pairs; skipped CRS %s and operations %s%n",
                catalog,
                crsWkts.size(),
                operationWkts.size(),
                operationCodesByPair.size(),
                skippedCrs,
                skippedOperations);
    }

    /**
     * Formats a definition as single-line WKT 2. SIS reports a definition it can only write approximately
     * with warnings instead of an exception, so any warning fails generation.
     */
    private static byte[] formatWkt(WKTFormat format, Object definition, String code)
    {
        String wkt = format.format(definition);
        Warnings warnings = format.getWarnings();
        if (warnings != null) {
            throw new IllegalStateException("SIS reported warnings formatting EPSG %s: %s".formatted(code, warnings.toString(Locale.ROOT)));
        }
        return wkt.getBytes(UTF_8);
    }

    /**
     * Orders the operations between two CRSs as SIS's EPSG factory does: by accuracy, area of use, and
     * supersession. SIS's operation finder takes the first of several equally suitable operations.
     */
    private static List<Integer> preferenceOrder(CoordinateOperationAuthorityFactory operationFactory, Pair pair, List<Integer> operationCodes)
            throws FactoryException
    {
        List<Integer> ordered = new ArrayList<>();
        for (CoordinateOperation operation : operationFactory.createFromCoordinateReferenceSystemCodes(Integer.toString(pair.source()), Integer.toString(pair.target()))) {
            OptionalInt code = epsgIdentifier(operation);
            if (code.isPresent() && operationCodes.contains(code.orElseThrow()) && !ordered.contains(code.orElseThrow())) {
                ordered.add(code.orElseThrow());
            }
        }
        if (ordered.size() != operationCodes.size()) {
            throw new IllegalStateException("SIS did not return all operations from EPSG %s to %s: expected %s, but was %s".formatted(pair.source(), pair.target(), operationCodes, ordered));
        }
        return ordered;
    }

    private static void writeCatalog(Path catalog, Map<Integer, byte[]> crsWkts, Map<Integer, byte[]> operationWkts, Map<Pair, List<Integer>> operationCodesByPair)
            throws IOException
    {
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(catalog)))) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);

            writeWktSection(output, crsWkts);
            writeWktSection(output, operationWkts);

            output.writeInt(operationCodesByPair.size());
            for (Entry<Pair, List<Integer>> entry : operationCodesByPair.entrySet()) {
                output.writeInt(entry.getKey().source());
                output.writeInt(entry.getKey().target());
                List<Integer> operationCodes = entry.getValue();
                output.writeInt(operationCodes.size());
                for (int operationCode : operationCodes) {
                    output.writeInt(operationCode);
                }
            }
        }
    }

    private static void writeWktSection(DataOutputStream output, Map<Integer, byte[]> wkts)
            throws IOException
    {
        List<WktIndexEntry> entries = new ArrayList<>();
        // The blocks follow the index, so they are buffered until the index is complete
        ByteArrayOutputStream blockData = new ByteArrayOutputStream();
        DataOutputStream blockOutput = new DataOutputStream(blockData);
        int blockCount = 0;
        List<Entry<Integer, byte[]>> records = List.copyOf(wkts.entrySet());
        ZstdCompressor compressor = new ZstdJavaCompressor();

        for (int blockRecordStart = 0; blockRecordStart < records.size(); blockRecordStart += WKT_BLOCK_RECORD_COUNT) {
            int blockRecordEnd = Math.min(blockRecordStart + WKT_BLOCK_RECORD_COUNT, records.size());
            ByteArrayOutputStream uncompressed = new ByteArrayOutputStream();
            for (int recordIndex = blockRecordStart; recordIndex < blockRecordEnd; recordIndex++) {
                Entry<Integer, byte[]> record = records.get(recordIndex);
                byte[] wkt = record.getValue();
                entries.add(new WktIndexEntry(record.getKey(), blockCount, uncompressed.size(), wkt.length));
                uncompressed.write(wkt);
            }
            byte[] uncompressedBytes = uncompressed.toByteArray();
            byte[] compressedBytes = new byte[compressor.maxCompressedLength(uncompressedBytes.length)];
            int compressedLength = compressor.compress(uncompressedBytes, 0, uncompressedBytes.length, compressedBytes, 0, compressedBytes.length);
            blockOutput.writeInt(uncompressedBytes.length);
            blockOutput.writeInt(compressedLength);
            blockOutput.write(compressedBytes, 0, compressedLength);
            blockCount++;
        }

        output.writeInt(entries.size());
        for (WktIndexEntry entry : entries) {
            output.writeInt(entry.code());
            output.writeInt(entry.block());
            output.writeInt(entry.offset());
            output.writeInt(entry.length());
        }

        output.writeInt(blockCount);
        blockData.writeTo(output);
    }

    private static void copyResource(Path jarPath, String resource, Path target)
            throws IOException
    {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(resource);
            if (entry == null) {
                throw new IOException("Resource not found in %s: %s".formatted(jarPath, resource));
            }
            try (InputStream input = jar.getInputStream(entry)) {
                Files.copy(input, target, REPLACE_EXISTING);
            }
        }
    }

    private static int parseEpsgCode(String code)
    {
        return Integer.parseInt(code.substring(code.lastIndexOf(':') + 1));
    }

    private static OptionalInt epsgIdentifier(IdentifiedObject object)
    {
        for (Identifier identifier : object.getIdentifiers()) {
            if (identifier instanceof ReferenceIdentifier referenceIdentifier && "EPSG".equalsIgnoreCase(referenceIdentifier.getCodeSpace())) {
                return OptionalInt.of(parseEpsgCode(identifier.getCode()));
            }
        }
        return OptionalInt.empty();
    }

    private record WktIndexEntry(int code, int block, int offset, int length) {}

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
