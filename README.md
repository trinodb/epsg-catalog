# EPSG catalog

A compact catalog of [EPSG](https://epsg.org/) coordinate reference system (CRS) and coordinate-operation
definitions, and a small Java loader for it, extracted from [Trino](https://github.com/trinodb/trino).

The catalog contains every definition that Apache SIS can create and format from its embedded EPSG dataset,
as WKT 2 (2019), except deprecated codes, and coordinate operations without a source or target CRS, such as
map-projection conversions, which SIS cannot parse back. Parse the definitions with the SIS version set by `dep.sis.version`
in `pom.xml`, or a compatible one.

## Usage

```java
EpsgCatalog catalog = EpsgCatalog.loadDefault();

// WKT of WGS 84
String wgs84 = catalog.crsWkt(4326).orElseThrow();

// Operations from NAD83 to WGS 84, most preferred first: [1188, 1252, 1251]
List<Integer> operations = catalog.operationCodesBetween(4269, 4326);
```

## Building

```shell
mvn clean verify
```

The build generates the catalog from `org.apache.sis.non-free:sis-embedded-data`.

## License

The code is licensed under the [Apache License, Version 2.0](LICENSE). The EPSG dataset is owned by the
International Association of Oil and Gas Producers (IOGP) and is distributed under the
[EPSG terms of use](https://epsg.org/terms-of-use.html), which the `epsg-catalog` JAR includes in
`io/trino/epsg/EPSG-LICENSE.txt` and `io/trino/epsg/EPSG-NOTICE.txt`.
