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

import org.apache.sis.io.wkt.Convention;
import org.apache.sis.io.wkt.WKTFormat;
import org.apache.sis.io.wkt.Warnings;
import org.junit.jupiter.api.Test;
import org.opengis.metadata.Identifier;
import org.opengis.referencing.IdentifiedObject;
import org.opengis.referencing.ReferenceIdentifier;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class TestEpsgCatalog
{
    @Test
    public void testBundledCatalog()
    {
        EpsgCatalog catalog = EpsgCatalog.loadDefault();
        assertThat(catalog.crsWkt(4326).orElseThrow()).contains("WGS 84", "4326");
        assertThat(catalog.crsWkt(3857).orElseThrow()).contains("Pseudo-Mercator", "3857");
        assertThat(catalog.crsWkt(999999)).isEmpty();
        assertThat(catalog.hasCrsCode(4326)).isTrue();
        assertThat(catalog.hasOperationCode(1188)).isTrue();
        // NAD83 to WGS 84 transformations, in SIS's preference order rather than by code
        assertThat(catalog.operationCodesBetween(4269, 4326)).containsExactly(1188, 1252, 1251);
        assertThat(catalog.operationCodesBetween(4326, 4269)).isEmpty();
        // Map-projection conversions have no source or target CRS, so they are skipped
        assertThat(catalog.hasOperationCode(3811)).isFalse();
        for (int code : catalog.crsCodes()) {
            assertThat(catalog.crsWkt(code).orElseThrow()).isNotBlank();
        }
        for (int code : catalog.operationCodes()) {
            assertThat(catalog.operationWkt(code).orElseThrow()).contains("SOURCECRS[", "TARGETCRS[");
        }
    }

    @Test
    public void testParseDefinitions()
            throws ParseException
    {
        // Parse every definition with the SIS version the catalog was generated with, and check that it has
        // the code it was looked up by. SIS reports elements it cannot apply as warnings, so any warning other
        // than the known ones fails.
        EpsgCatalog catalog = EpsgCatalog.loadDefault();
        assertThat(catalog.crsCodes()).isNotEmpty();
        assertThat(catalog.operationCodes()).isNotEmpty();
        WKTFormat format = new WKTFormat(Locale.ROOT, (ZoneId) null);
        format.setConvention(Convention.WKT2_2019);
        List<String> warnings = new ArrayList<>();
        for (int code : catalog.crsCodes()) {
            parse(format, catalog.crsWkt(code).orElseThrow(), code, warnings);
        }
        for (int code : catalog.operationCodes()) {
            parse(format, catalog.operationWkt(code).orElseThrow(), code, warnings);
        }
        // The NCRS Las Vegas CRSs have a vertical extent in metres, which SIS cannot apply
        assertThat(warnings).containsExactlyElementsOf(IntStream.of(8379, 8380, 8381, 8382, 8383, 8384, 8385, 8387)
                .mapToObj(code -> "EPSG %s: Cannot assign units “m” to dimension “VerticalExtent”.".formatted(code))
                .toList());
    }

    private static void parse(WKTFormat format, String wkt, int code, List<String> warnings)
            throws ParseException
    {
        IdentifiedObject object = (IdentifiedObject) format.parseObject(wkt);
        assertThat(epsgCode(object)).as("EPSG code of %s", wkt).contains(code);
        Warnings parseWarnings = format.getWarnings();
        if (parseWarnings != null) {
            for (int i = 0; i < parseWarnings.getNumMessages(); i++) {
                warnings.add("EPSG %s: %s".formatted(code, parseWarnings.getMessage(i)));
            }
        }
    }

    private static Optional<Integer> epsgCode(IdentifiedObject object)
    {
        for (Identifier identifier : object.getIdentifiers()) {
            if (identifier instanceof ReferenceIdentifier referenceIdentifier && "EPSG".equals(referenceIdentifier.getCodeSpace())) {
                return Optional.of(Integer.parseInt(identifier.getCode()));
            }
        }
        return Optional.empty();
    }

    @Test
    public void testEpsgTermsAreBundled()
            throws IOException
    {
        try (InputStream input = EpsgCatalog.class.getResourceAsStream("EPSG-LICENSE.txt")) {
            assertThat(new String(input.readAllBytes(), UTF_8)).contains("EPSG dataset terms of use");
        }
        try (InputStream input = EpsgCatalog.class.getResourceAsStream("EPSG-NOTICE.txt")) {
            assertThat(new String(input.readAllBytes(), UTF_8)).contains("International Association of Oil and Gas Producers");
        }
    }
}
