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
import org.apache.sis.referencing.factory.sql.EPSGFactory;
import org.apache.sis.resources.embedded.EmbeddedResources;
import org.hsqldb.jdbc.JDBCDataSource;
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
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.jar.JarFile;

import static java.lang.System.arraycopy;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * Build-time command that generates the catalog, provenance, and EPSG license and notices.
 */
public final class GenerateEpsgCatalog
{
    private static final int FORMAT_VERSION = 1;
    private static final int WKT_BLOCK_RECORD_COUNT = 64;
    private static final String CATALOG_RESOURCE = "io/trino/epsg/epsg-catalog.bin";

    private GenerateEpsgCatalog() {}

    /**
     * Generates resources from the SIS embedded EPSG dataset on the runtime classpath.
     *
     * @param args output directory, SIS version, and generator version
     * @throws Exception if generation or writing resources fails
     */
    public static void main(String[] args)
            throws Exception
    {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: GenerateEpsgCatalog <generated-resources-directory> <sis-version> <generator-version>");
        }

        Path outputDirectory = Path.of(args[0]);
        String datasetVersion = args[1];
        String generatorVersion = args[2];
        Path datasetJar = Path.of(EmbeddedResources.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        Path resourceDirectory = outputDirectory.resolve("io/trino/epsg");
        Files.createDirectories(resourceDirectory);

        Path catalog = outputDirectory.resolve(CATALOG_RESOURCE);
        // The stamp is kept outside the generated resource directory so that it is not packaged.
        Path stamp = outputDirectory.resolveSibling("epsg-catalog.stamp");
        String fingerprint = fingerprint(datasetVersion, generatorVersion, datasetJar);
        if (isUpToDate(resourceDirectory, stamp, fingerprint)) {
            System.out.printf("EPSG catalog in %s is up to date%n", catalog);
            return;
        }
        // Remove the stamp first, so that a run failing part way through regenerates next time
        // instead of leaving a partial catalog looking up to date.
        Files.deleteIfExists(stamp);

        try (EPSGFactory factory = createFactory(datasetJar, outputDirectory.resolveSibling("epsg-database"))) {
            generate(factory, resourceDirectory, datasetJar, datasetVersion, generatorVersion);
        }
        Files.writeString(stamp, outputFingerprint(resourceDirectory, fingerprint), UTF_8);
    }

    private static EPSGFactory createFactory(Path datasetJar, Path databaseDirectory)
            throws IOException, FactoryException
    {
        Files.createDirectories(databaseDirectory);
        for (String suffix : List.of("properties", "script", "data")) {
            copyResource(datasetJar, "SIS-DATA/Databases/SpatialMetadata." + suffix, databaseDirectory.resolve("SpatialMetadata." + suffix));
        }
        JDBCDataSource dataSource = new JDBCDataSource();
        dataSource.setDatabase("jdbc:hsqldb:file:" + databaseDirectory.resolve("SpatialMetadata").toAbsolutePath() + ";readonly=true");
        dataSource.setUser("SA");
        dataSource.setPassword("");
        return new EPSGFactory(Map.of("dataSource", dataSource, "locale", Locale.ROOT));
    }

    private static void generate(EPSGFactory factory, Path resourceDirectory, Path datasetJar, String datasetVersion, String generatorVersion)
            throws Exception
    {
        Path catalog = resourceDirectory.resolve("epsg-catalog.bin");
        CRSAuthorityFactory crsFactory = factory;
        CoordinateOperationAuthorityFactory operationFactory = factory;
        WKTFormat format = new WKTFormat(Locale.ROOT, (ZoneId) null);
        format.setConvention(Convention.WKT2_2019);
        format.setIndentation(WKTFormat.SINGLE_LINE);

        List<String> skipped = new ArrayList<>();
        Map<Integer, byte[]> crsWkts = new TreeMap<>();
        int skippedCrs = 0;
        for (String code : crsFactory.getAuthorityCodes(CoordinateReferenceSystem.class).stream().sorted().toList()) {
            OptionalInt epsgCode = parseEpsgCode(code);
            if (epsgCode.isEmpty()) {
                throw new IllegalStateException("Non-numeric EPSG CRS code: " + code);
            }
            try {
                CoordinateReferenceSystem crs = crsFactory.createCoordinateReferenceSystem(code);
                crsWkts.put(epsgCode.orElseThrow(), format.format(crs).getBytes(UTF_8));
            }
            catch (FactoryException | UnformattableObjectException e) {
                skipped.add("CRS\t" + code + "\t" + e.getClass().getSimpleName());
                skippedCrs++;
            }
        }

        Map<Integer, OperationEntry> operationWkts = new TreeMap<>();
        Map<Pair, List<Integer>> operationCodesByPair = new TreeMap<>();
        int skippedOperations = 0;
        for (String code : operationFactory.getAuthorityCodes(CoordinateOperation.class).stream().sorted().toList()) {
            OptionalInt epsgCode = parseEpsgCode(code);
            if (epsgCode.isEmpty()) {
                throw new IllegalStateException("Non-numeric EPSG operation code: " + code);
            }
            try {
                CoordinateOperation operation = operationFactory.createCoordinateOperation(code);
                byte[] wkt = format.format(operation).getBytes(UTF_8);
                operationWkts.put(epsgCode.orElseThrow(), new OperationEntry(epsgCode.orElseThrow(), wkt));

                OptionalInt source = epsgIdentifier(operation.getSourceCRS());
                OptionalInt target = epsgIdentifier(operation.getTargetCRS());
                if (source.isPresent() && target.isPresent()) {
                    operationCodesByPair.computeIfAbsent(new Pair(source.orElseThrow(), target.orElseThrow()), _ -> new ArrayList<>())
                            .add(epsgCode.orElseThrow());
                }
            }
            catch (FactoryException | UnformattableObjectException e) {
                skipped.add("OPERATION\t" + code + "\t" + e.getClass().getSimpleName());
                skippedOperations++;
            }
        }

        if (crsWkts.isEmpty() || operationWkts.isEmpty()) {
            throw new IllegalStateException("The EPSG dataset did not produce CRS and operation definitions");
        }
        writeCatalog(catalog, crsWkts, operationWkts, operationCodesByPair);
        copyResource(datasetJar, "META-INF/LICENSE", resourceDirectory.resolve("EPSG-LICENSE.txt"));
        copyResource(datasetJar, "META-INF/NOTICE", resourceDirectory.resolve("EPSG-NOTICE.txt"));

        System.out.printf(
                "Generated %s with %,d CRS definitions, %,d operations, %,d operation pairs; skipped %,d CRS and %,d operations%n",
                catalog,
                crsWkts.size(),
                operationWkts.size(),
                operationCodesByPair.size(),
                skippedCrs,
                skippedOperations);
        String epsgVersion = crsFactory.getAuthority().getEdition().toString(Locale.ROOT);
        String provenance = "format.version=" + FORMAT_VERSION + "\n" +
                "generator.version=" + generatorVersion + "\n" +
                "sis.version=" + datasetVersion + "\n" +
                "epsg.version=" + epsgVersion + "\n" +
                "source.coordinates=org.apache.sis.non-free:sis-embedded-data:" + datasetVersion + "\n" +
                "source.sha256=" + sha256(Files.readAllBytes(datasetJar)) + "\n" +
                "catalog.sha256=" + sha256(Files.readAllBytes(catalog)) + "\n" +
                "crs.count=" + crsWkts.size() + "\n" +
                "operation.count=" + operationWkts.size() + "\n" +
                "pair.count=" + operationCodesByPair.size() + "\n" +
                "skipped.crs.count=" + skippedCrs + "\n" +
                "skipped.operation.count=" + skippedOperations + "\n";
        Files.writeString(resourceDirectory.resolve("catalog.properties"), provenance, UTF_8);
        Files.write(resourceDirectory.resolve("skipped-definitions.tsv"), skipped, UTF_8);
    }

    private static String fingerprint(String datasetVersion, String generatorVersion, Path datasetJar)
            throws Exception
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(datasetVersion.getBytes(UTF_8));
        digest.update(generatorVersion.getBytes(UTF_8));
        digest.update(Files.readAllBytes(datasetJar));
        digest.update(Files.readAllBytes(Path.of(EPSGFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI())));
        digest.update(Files.readAllBytes(Path.of(ZstdCompressor.class.getProtectionDomain().getCodeSource().getLocation().toURI())));
        digest.update(Files.readAllBytes(Path.of(JDBCDataSource.class.getProtectionDomain().getCodeSource().getLocation().toURI())));
        List<Class<?>> classes = new ArrayList<>();
        classes.add(GenerateEpsgCatalog.class);
        classes.addAll(Arrays.asList(GenerateEpsgCatalog.class.getDeclaredClasses()));
        classes.sort(Comparator.comparing(Class::getName));
        for (Class<?> type : classes) {
            String resource = "/" + type.getName().replace('.', '/') + ".class";
            try (InputStream input = type.getResourceAsStream(resource)) {
                digest.update(requireNonNull(input, "Missing generator bytecode: " + resource).readAllBytes());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static boolean isUpToDate(Path directory, Path stamp, String fingerprint)
            throws IOException, NoSuchAlgorithmException
    {
        String expected = outputFingerprint(directory, fingerprint);
        return !expected.isEmpty() && Files.isRegularFile(stamp) && expected.equals(Files.readString(stamp, UTF_8));
    }

    static String outputFingerprint(Path directory, String fingerprint)
            throws IOException, NoSuchAlgorithmException
    {
        StringBuilder result = new StringBuilder(fingerprint).append('\n');
        for (String name : List.of("epsg-catalog.bin", "EPSG-LICENSE.txt", "EPSG-NOTICE.txt", "catalog.properties", "skipped-definitions.tsv")) {
            Path file = directory.resolve(name);
            if (!Files.isRegularFile(file)) {
                return "";
            }
            result.append(name).append('=').append(sha256(Files.readAllBytes(file))).append('\n');
        }
        return result.toString();
    }

    private static String sha256(byte[] bytes)
            throws NoSuchAlgorithmException
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static void writeCatalog(
            Path catalog,
            Map<Integer, byte[]> crsWkts,
            Map<Integer, OperationEntry> operationWkts,
            Map<Pair, List<Integer>> operationCodesByPair)
            throws IOException
    {
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(catalog)))) {
            output.writeInt(0x54524745); // TRGE
            output.writeInt(FORMAT_VERSION);

            writeWktSection(output, crsWkts);
            writeWktSection(output, operationWkts.values().stream()
                    .collect(TreeMap::new, (map, operation) -> map.put(operation.code(), operation.wkt()), TreeMap::putAll));

            output.writeInt(operationCodesByPair.size());
            for (Map.Entry<Pair, List<Integer>> entry : new TreeMap<>(operationCodesByPair).entrySet()) {
                output.writeInt(entry.getKey().source());
                output.writeInt(entry.getKey().target());
                List<Integer> operationCodes = entry.getValue().stream()
                        .sorted()
                        .toList();
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
        List<CompressedBlock> blocks = new ArrayList<>();
        List<Map.Entry<Integer, byte[]>> records = List.copyOf(new TreeMap<>(wkts).entrySet());
        ZstdCompressor compressor = new ZstdJavaCompressor();

        for (int blockRecordStart = 0; blockRecordStart < records.size(); blockRecordStart += WKT_BLOCK_RECORD_COUNT) {
            int blockRecordEnd = Math.min(blockRecordStart + WKT_BLOCK_RECORD_COUNT, records.size());
            ByteArrayOutputStream uncompressed = new ByteArrayOutputStream();
            int blockIndex = blocks.size();
            for (int recordIndex = blockRecordStart; recordIndex < blockRecordEnd; recordIndex++) {
                Map.Entry<Integer, byte[]> record = records.get(recordIndex);
                byte[] wkt = record.getValue();
                entries.add(new WktIndexEntry(record.getKey(), blockIndex, uncompressed.size(), wkt.length));
                uncompressed.write(wkt);
            }
            byte[] uncompressedBytes = uncompressed.toByteArray();
            byte[] compressedBytes = new byte[compressor.maxCompressedLength(uncompressedBytes.length)];
            int compressedLength = compressor.compress(uncompressedBytes, 0, uncompressedBytes.length, compressedBytes, 0, compressedBytes.length);
            byte[] block = new byte[compressedLength];
            arraycopy(compressedBytes, 0, block, 0, compressedLength);
            blocks.add(new CompressedBlock(uncompressedBytes.length, block));
        }

        output.writeInt(entries.size());
        for (WktIndexEntry entry : entries) {
            output.writeInt(entry.code());
            output.writeInt(entry.block());
            output.writeInt(entry.offset());
            output.writeInt(entry.length());
        }

        output.writeInt(blocks.size());
        for (CompressedBlock block : blocks) {
            output.writeInt(block.uncompressedLength());
            writeBytes(output, block.bytes());
        }
    }

    private static void writeBytes(DataOutputStream output, byte[] bytes)
            throws IOException
    {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    static void copyResource(Path datasetJar, String resource, Path target)
            throws IOException
    {
        try (JarFile jar = new JarFile(datasetJar.toFile())) {
            var entry = jar.getJarEntry(resource);
            if (entry == null) {
                throw new IOException("EPSG dataset resource not found: " + resource);
            }
            try (InputStream input = jar.getInputStream(entry)) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static OptionalInt parseEpsgCode(String code)
    {
        requireNonNull(code, "code is null");
        int separator = code.lastIndexOf(':');
        if (separator >= 0) {
            code = code.substring(separator + 1);
        }
        try {
            return OptionalInt.of(Integer.parseInt(code));
        }
        catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private static OptionalInt epsgIdentifier(IdentifiedObject object)
    {
        if (object == null) {
            return OptionalInt.empty();
        }
        for (Identifier identifier : object.getIdentifiers()) {
            if (!(identifier instanceof ReferenceIdentifier referenceIdentifier)) {
                continue;
            }
            String codeSpace = referenceIdentifier.getCodeSpace();
            if ("EPSG".equalsIgnoreCase(codeSpace)) {
                return parseEpsgCode(identifier.getCode());
            }
        }
        return OptionalInt.empty();
    }

    record OperationEntry(int code, byte[] wkt) {}

    private record WktIndexEntry(int code, int block, int offset, int length) {}

    private record CompressedBlock(int uncompressedLength, byte[] bytes) {}

    record Pair(int source, int target)
            implements Comparable<Pair>
    {
        @Override
        public int compareTo(Pair other)
        {
            return Comparator.comparingInt(Pair::source)
                    .thenComparingInt(Pair::target)
                    .compare(this, other);
        }
    }
}
