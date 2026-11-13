#!/usr/bin/env python3
"""Check the actual archives that CI and the release workflow produce."""

import hashlib
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

root = Path(__file__).resolve().parents[2]
namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
version = ET.parse(root / "pom.xml").findtext("m:version", namespaces=namespace)
for module in ("epsg-catalog-generator", "epsg-catalog"):
    for classifier in ("", "-sources", "-javadoc"):
        artifact = root / module / "target" / f"{module}-{version}{classifier}.jar"
        with ZipFile(artifact) as archive:
            names = set(archive.namelist())
            if classifier == "-javadoc":
                assert "index.html" in names, f"Missing Javadoc in {artifact}"
                continue
            for name in ("LICENSE", "NOTICE"):
                assert archive.read(f"META-INF/{name}") == (root / name).read_bytes(), (artifact, name)
            if module != "epsg-catalog":
                continue
            prefix = "io/trino/epsg/"
            for name in ("epsg-catalog.bin", "EPSG-LICENSE.txt", "EPSG-NOTICE.txt", "catalog.properties", "skipped-definitions.tsv"):
                assert prefix + name in names, (artifact, name)
            assert b"EPSG dataset terms of use" in archive.read(prefix + "EPSG-LICENSE.txt")
            assert b"International Association of Oil and Gas Producers" in archive.read(prefix + "EPSG-NOTICE.txt")
            properties = dict(line.split("=", 1) for line in archive.read(prefix + "catalog.properties").decode().splitlines())
            assert properties["catalog.sha256"] == hashlib.sha256(archive.read(prefix + "epsg-catalog.bin")).hexdigest()
            assert not any(name.startswith(("org/apache/sis/", "org/hsqldb/", "SIS-DATA/")) for name in names)
            assert not any(name.endswith(".stamp") for name in names)
        print(f"Checked {artifact.name}")
