#!/usr/bin/env python3
"""Validate declarations separately from a resolved release graph and its POM license evidence.

Run :app:writeReleaseDependencyInventory before producing an SBOM. The exported artifact
hashes describe Gradle's selected release inputs before shrinking, not final APK contents.
Only retrieved, hash-verified POM declarations (including explicit parent inheritance) supply
license evidence. Missing evidence is UNKNOWN; Maven group names never certify a license.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import quote, urlsplit

CONFIGURATION = r"(?:implementation|api|runtimeOnly|compileOnly|testImplementation|androidTestImplementation|debugImplementation|ksp|kspAndroidTest)"
DEP_RE = re.compile(rf'^\s*({CONFIGURATION})\("([^":]+):([^":]+)(?::([^"\n]+))?"\)')
BOM_RE = re.compile(r'platform\("([^":]+):([^":]+):([^"\n]+)"\)')
PLUGIN_RE = re.compile(r'^\s*id\("([^"]+)"\)\s+version\s+"([^"]+)"')
HASH_RE = re.compile(r"[0-9a-f]{64}\Z")
BUILD_INPUTS = (
    "build.gradle.kts", "settings.gradle.kts", "app/build.gradle.kts", "gradle.properties",
    "gradle/wrapper/gradle-wrapper.properties", "gradle/release-dependencies.gradle.kts",
    "tools/optimized-test-abi/src/artflow/build/TestAbiRules.java",
    "tools/optimized-test-abi/verify.py",
    "app/proguard-benchmark-test-reflection.pro",
    "gradle/libs.versions.toml", "gradle/verification-metadata.xml", "gradle.lockfile", "app/gradle.lockfile",
)
REQUIRED_BUILD_INPUTS = frozenset(BUILD_INPUTS[:6])

# This is a review policy for POM-declared licenses, not a legal compatibility certification.
ALLOWED_LICENSES = {"Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "EPL-1.0", "EPL-2.0"}
DISALLOWED_LICENSES = {
    f"{family}-{version}-{suffix}"
    for family, versions in (("GPL", ("1.0", "2.0", "3.0")), ("AGPL", ("1.0", "3.0")))
    for version in versions
    for suffix in ("only", "or-later")
}
LICENSE_NAMES = {name.casefold(): name for name in ALLOWED_LICENSES | DISALLOWED_LICENSES}
LICENSE_NAMES.update({
    "apache license, version 2.0": "Apache-2.0",
    "apache license version 2.0": "Apache-2.0",
    "apache license 2.0": "Apache-2.0",
    "apache 2.0": "Apache-2.0",
    "the apache license, version 2.0": "Apache-2.0",
    "the apache software license, version 2.0": "Apache-2.0",
    "mit license": "MIT",
    "the mit license": "MIT",
    "the mit license (mit)": "MIT",
    "bsd 2-clause license": "BSD-2-Clause",
    "bsd 3-clause license": "BSD-3-Clause",
    "eclipse public license 1.0": "EPL-1.0",
    "eclipse public license 2.0": "EPL-2.0",
})
LICENSE_URLS = {
    "apache.org/licenses/license-2.0": "Apache-2.0",
    "opensource.org/licenses/mit": "MIT",
    "opensource.org/license/mit": "MIT",
    "opensource.org/licenses/bsd-2-clause": "BSD-2-Clause",
    "opensource.org/licenses/bsd-3-clause": "BSD-3-Clause",
    "opensource.org/licenses/isc": "ISC",
    "creativecommons.org/publicdomain/zero/1.0": "CC0-1.0",
    "eclipse.org/legal/epl-v10": "EPL-1.0",
    "eclipse.org/legal/epl-2.0": "EPL-2.0",
}


class InventoryError(ValueError):
    """Incomplete, stale or inconsistent release evidence."""


class PomTreeBuilder(ET.TreeBuilder):
    """Reject DTDs before expansion, regardless of the POM's XML byte encoding."""

    def doctype(self, name: str, pubid: str | None, system: str | None) -> None:
        raise InventoryError("POM DTD/entity declarations are forbidden")


def extract_components(root_build: Path, app_build: Path) -> list[dict]:
    """Return source declarations only. An unversioned member is never given a BOM version."""
    components = []
    for line in root_build.read_text(encoding="utf-8").splitlines():
        match = PLUGIN_RE.match(line)
        if match:
            name, version = match.groups()
            components.append({"group": "gradle-plugin", "name": name, "version": version, "configuration": "build-plugin"})
    for line in app_build.read_text(encoding="utf-8").splitlines():
        if line.lstrip().startswith("//"):
            continue
        bom = BOM_RE.search(line)
        dependency = DEP_RE.match(line)
        if bom:
            group, name, version = bom.groups()
            components.append({"group": group, "name": name, "version": version, "configuration": "platform"})
        elif dependency:
            configuration, group, name, version = dependency.groups()
            components.append({"group": group, "name": name, "version": version, "configuration": configuration})
    return components


def fixed_version(version: str) -> bool:
    return bool(version) and not any(token in version for token in ("+", "[", "]", "(", ")", "$", ",")) and not (
        version.upper().endswith("-SNAPSHOT") or version.lower().startswith("latest.")
    )


def check_declarations(components: list[dict]) -> list[str]:
    errors = [] if components else ["No Gradle dependency or plugin declarations found"]
    for component in components:
        version = component["version"]
        if version is not None and not fixed_version(version):
            errors.append(f"Unpinned dependency declaration: {component['group']}:{component['name']}:{version}")
    return errors


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise InventoryError(message)


def is_text(value: object) -> bool:
    return isinstance(value, str) and bool(value.strip())


def validate_inventory(inventory: dict, root: Path) -> None:
    require(isinstance(inventory, dict) and inventory.get("schemaVersion") == 1, "Unsupported dependency inventory schema")
    require(inventory.get("configuration") == "releaseRuntimeClasspath", "Inventory must resolve releaseRuntimeClasspath")
    require(inventory.get("project") == ":app" and inventory.get("root") == "project::app", "Wrong release project/root")
    require(inventory.get("unresolved") == [], "Inventory has missing or unresolved dependencies")
    expected_inputs = {path for path in BUILD_INPUTS if (root / path).is_file()}
    inputs = inventory.get("buildInputs")
    require(isinstance(inputs, dict) and REQUIRED_BUILD_INPUTS <= inputs.keys(), "Inventory is missing build input fingerprints")
    require(set(inputs) == expected_inputs, "Inventory build input set differs from the current checkout")
    for path, digest in inputs.items():
        require(digest == sha256((root / path).read_bytes()), f"Stale dependency inventory: {path} changed")

    components = inventory.get("components")
    require(isinstance(components, list) and bool(components), "Resolved release graph is empty")
    ids = set()
    artifact_count = 0
    for component in components:
        require(isinstance(component, dict), "Invalid release component")
        require(all(is_text(component.get(key)) for key in ("group", "name", "version", "id")), "Missing resolved coordinates")
        coordinate = ":".join(component[key] for key in ("group", "name", "version"))
        require(component["id"] == coordinate and coordinate not in ids, f"Duplicate or inconsistent component: {coordinate}")
        require(fixed_version(component["version"]), f"Unresolved/dynamic component version: {coordinate}")
        require(component.get("pomId") == coordinate, f"Mismatched POM identity: {coordinate}")
        ids.add(coordinate)
        artifacts = component.get("artifacts")
        require(isinstance(artifacts, list), f"Missing artifact inventory: {coordinate}")
        seen = set()
        for artifact in artifacts:
            require(isinstance(artifact, dict), f"Invalid artifact: {coordinate}")
            name = artifact.get("fileName")
            require(is_text(name) and Path(name).name == name and name not in (".", "..") and "\\" not in name, "Invalid artifact name")
            digest = artifact.get("sha256")
            require(isinstance(digest, str) and HASH_RE.fullmatch(digest) is not None, f"Missing artifact SHA-256: {coordinate}/{name}")
            require(type(artifact.get("size")) is int and artifact["size"] > 0, f"Empty or invalid artifact: {coordinate}/{name}")
            require(is_text(artifact.get("variant")), f"Missing selected artifact variant: {coordinate}/{name}")
            key = (name, digest, artifact["variant"])
            require(key not in seen, f"Duplicate artifact: {coordinate}/{name}")
            seen.add(key)
            artifact_count += 1
    require(artifact_count > 0, "Resolved release artifact inventory is empty")

    root_id = inventory["root"]
    edges = inventory.get("edges")
    require(isinstance(edges, list) and bool(edges), "Release dependency edges are missing")
    adjacency = {identifier: set() for identifier in ids | {root_id}}
    for edge in edges:
        require(isinstance(edge, dict), "Invalid dependency edge")
        require(edge.get("from") in adjacency and edge.get("to") in ids, "Dependency edge references a missing component")
        require(type(edge.get("constraint")) is bool and is_text(edge.get("requested")), "Missing dependency selection evidence")
        # Constraints influence versions but cannot introduce a dependency. Requiring a path
        # of actual dependency edges also detects an omitted direct/transitive relationship.
        if not edge["constraint"]:
            adjacency[edge["from"]].add(edge["to"])
    reached, pending = set(), [root_id]
    while pending:
        identifier = pending.pop()
        if identifier not in reached:
            reached.add(identifier)
            pending.extend(adjacency[identifier] - reached)
    require(reached == ids | {root_id}, "Release graph contains unreachable components")


def child(element: ET.Element, name: str) -> ET.Element | None:
    return next((item for item in element if item.tag.rsplit("}", 1)[-1] == name), None)


def child_text(element: ET.Element, name: str) -> str:
    item = child(element, name)
    return "" if item is None else (item.text or "").strip()


def pom_parent(document: ET.Element) -> str | None:
    parent = child(document, "parent")
    if parent is None:
        return None
    parts = [child_text(parent, name) for name in ("groupId", "artifactId", "version")]
    require(all(re.fullmatch(r"[A-Za-z0-9_.-]+", part) for part in parts), "Unresolved parent POM coordinates")
    return ":".join(parts)


def load_poms(inventory: dict, directory: Path) -> dict[str, dict]:
    entries = inventory.get("poms")
    require(isinstance(entries, list), "POM evidence inventory is missing")
    poms = {}
    for entry in entries:
        require(isinstance(entry, dict) and is_text(entry.get("id")), "Invalid POM evidence entry")
        coordinate = entry["id"]
        require(coordinate not in poms, f"Duplicate POM evidence: {coordinate}")
        require(entry.get("status") in {"resolved", "unresolved"}, f"Unknown POM evidence status: {coordinate}")
        poms[coordinate] = dict(entry)
        if entry["status"] == "unresolved":
            require(is_text(entry.get("reason")), f"Missing unresolved POM reason: {coordinate}")
            continue
        relative = f"release-poms/{sha256(coordinate.encode('utf-8'))}.pom"
        require(entry.get("path") == relative, f"Unsafe or inconsistent POM evidence path: {coordinate}")
        evidence = directory / relative
        require(evidence.is_file() and evidence.resolve().is_relative_to(directory.resolve()), f"Missing POM evidence: {coordinate}")
        data = evidence.read_bytes()
        require(entry.get("sha256") == sha256(data) and entry.get("size") == len(data), f"POM evidence hash/size mismatch: {coordinate}")
        try:
            document = ET.fromstring(data, parser=ET.XMLParser(target=PomTreeBuilder()))
            require(document.tag.rsplit("}", 1)[-1] == "project", "POM does not have a project root")
            parts = coordinate.split(":")
            require(len(parts) == 3 and child_text(document, "artifactId") == parts[1], "POM artifact identity mismatch")
            parent = child(document, "parent")
            group = child_text(document, "groupId") or (child_text(parent, "groupId") if parent is not None else "")
            version = child_text(document, "version") or (child_text(parent, "version") if parent is not None else "")
            require(group == parts[0] and version == parts[2], "POM group/version identity mismatch or unresolved property")
            poms[coordinate]["document"] = document
        except (ET.ParseError, InventoryError) as error:
            poms[coordinate]["parseError"] = str(error)
    for component in inventory["components"]:
        require(component["pomId"] in poms, f"Missing POM result: {component['id']}")
    return poms


def gpl_declaration(name: str, url: str) -> bool:
    if re.search(r"\bA?GPL(?:\b|v?\d)|GNU\s+(?:Affero\s+)?General\s+Public", name, re.IGNORECASE):
        return True
    parsed = urlsplit(url)
    host = (parsed.hostname or "").lower().removeprefix("www.")
    # These canonical GNU text URLs establish GPL/AGPL evidence, but cannot establish
    # SPDX's 'only' versus 'or-later' choice. Never manufacture an SPDX identifier here.
    return parsed.scheme in {"http", "https"} and host == "gnu.org" and re.fullmatch(
        r"/licenses/(?:old-licenses/)?a?gpl(?:-[123]\.0)?(?:\.en)?(?:\.html|\.txt)?/?",
        parsed.path.lower(),
    ) is not None


def normalize_license(name: str, url: str) -> str | None:
    by_name = LICENSE_NAMES.get(" ".join(name.split()).casefold())
    parsed = urlsplit(url)
    host = (parsed.hostname or "").lower().removeprefix("www.")
    path = parsed.path.lower().rstrip("/")
    for suffix in (".html", ".txt"):
        path = path.removesuffix(suffix)
    by_url = LICENSE_URLS.get(host + path) if parsed.scheme in {"http", "https"} else None
    if parsed.scheme in {"http", "https"} and host == "spdx.org" and path.startswith("/licenses/"):
        by_url = LICENSE_NAMES.get(path.removeprefix("/licenses/"))
    if by_name and by_url and by_name != by_url:
        return None
    if gpl_declaration(name, url) and (by_name in ALLOWED_LICENSES or by_url in ALLOWED_LICENSES):
        return None
    return by_name or by_url


def license_evidence(coordinate: str, poms: dict[str, dict], visited: frozenset = frozenset()) -> tuple[list[dict], str | None]:
    if coordinate in visited:
        return [], "Cyclic parent POM inheritance"
    entry = poms.get(coordinate)
    if not entry or entry["status"] != "resolved":
        return [], f"POM unavailable: {coordinate}"
    if "document" not in entry:
        return [], f"Invalid POM {coordinate}: {entry.get('parseError', 'unparsed')}"
    document = entry["document"]
    licenses = child(document, "licenses")
    declarations = []
    for license_element in licenses if licenses is not None else []:
        if license_element.tag.rsplit("}", 1)[-1] != "license":
            continue
        name, url = child_text(license_element, "name"), child_text(license_element, "url")
        declarations.append({
            "id": normalize_license(name, url), "name": name, "url": url,
            "pomId": coordinate, "pomPath": entry["path"], "pomSha256": entry["sha256"],
        })
    if declarations:
        return declarations, None
    try:
        parent = pom_parent(document)
    except InventoryError as error:
        return [], str(error)
    if parent:
        return license_evidence(parent, poms, visited | {coordinate})
    return [], f"No license declared in POM or its parents: {coordinate}"


def attach_license_evidence(inventory: dict, directory: Path) -> list[dict]:
    poms = load_poms(inventory, directory)
    components = []
    for component in inventory["components"]:
        licenses, reason = license_evidence(component["pomId"], poms)
        components.append(dict(component, licenses=licenses, licenseUnknownReason=reason))
    return components


def check_licenses(components: list[dict]) -> list[str]:
    errors = []
    for component in components:
        coordinate = component["id"]
        if component["licenseUnknownReason"]:
            errors.append(f"UNKNOWN license for {coordinate}: {component['licenseUnknownReason']}")
        for license_item in component["licenses"]:
            license_id = license_item["id"]
            raw_name = license_item["name"]
            # Either a name or a canonical license URL can reveal conflicting GPL/AGPL
            # evidence. Keep that evidence visible even when the other field is permissive.
            if license_id in DISALLOWED_LICENSES or gpl_declaration(raw_name, license_item["url"]):
                errors.append(f"Disallowed GPL/AGPL POM declaration for {coordinate}: {raw_name!r} {license_item['url']!r}")
            elif license_id not in ALLOWED_LICENSES:
                errors.append(f"UNKNOWN/unreviewed POM license for {coordinate}: {raw_name!r} {license_item['url']!r}")
    return errors


def component_purl(component: dict) -> str:
    return f"pkg:maven/{quote(component['group'], safe='')}/{quote(component['name'], safe='')}@{quote(component['version'], safe='')}"


def build_cyclonedx_sbom(inventory: dict, components: list[dict], revision: str = "HEAD") -> dict:
    refs = {component["id"]: component_purl(component) for component in components}
    refs[inventory["root"]] = inventory["root"]
    cdx_components = []
    for component in sorted(components, key=lambda item: item["id"]):
        artifacts = component["artifacts"]
        status = "unknown" if component["licenseUnknownReason"] or any(item["id"] is None for item in component["licenses"]) else "pom-declared"
        record = {
            "type": "library", "bom-ref": refs[component["id"]], "group": component["group"],
            "name": component["name"], "version": component["version"], "purl": refs[component["id"]],
            "scope": "required" if artifacts else "excluded",
            "properties": [
                {"name": "artflow:license:evidence-status", "value": status},
                {"name": "artflow:license:pom-evidence", "value": json.dumps(component["licenses"], sort_keys=True)},
                {"name": "artflow:gradle:artifacts", "value": json.dumps(artifacts, sort_keys=True)},
            ],
        }
        licenses = []
        for item in component["licenses"]:
            value = {"id": item["id"]} if item["id"] else {"name": item["name"] or "Unidentified POM license"}
            if urlsplit(item["url"]).scheme in {"https", "http"}:
                value["url"] = item["url"]
            if {"license": value} not in licenses:
                licenses.append({"license": value})
        if licenses:
            record["licenses"] = licenses
        if component["licenseUnknownReason"]:
            record["properties"].append({"name": "artflow:license:unknown-reason", "value": component["licenseUnknownReason"]})
        if len(artifacts) == 1:
            record["hashes"] = [{"alg": "SHA-256", "content": artifacts[0]["sha256"]}]
        elif artifacts:
            record["components"] = [
                {"type": "file", "name": artifact["fileName"], "hashes": [{"alg": "SHA-256", "content": artifact["sha256"]}]}
                for artifact in artifacts
            ]
        cdx_components.append(record)
    adjacency = {reference: set() for reference in refs.values()}
    for edge in inventory["edges"]:
        # CycloneDX dependsOn denotes direct dependencies. Keep version constraints in
        # the complete selection-edges property instead of inventing dependency links.
        if not edge["constraint"]:
            adjacency[refs[edge["from"]]].add(refs[edge["to"]])
    fingerprint = sha256(json.dumps(inventory, sort_keys=True).encode("utf-8"))
    serial = uuid.uuid5(uuid.NAMESPACE_URL, f"artflow:{revision}:releaseRuntimeClasspath:{fingerprint}")
    return {
        "bomFormat": "CycloneDX", "specVersion": "1.5", "serialNumber": f"urn:uuid:{serial}", "version": 1,
        "metadata": {
            "component": {"type": "application", "bom-ref": inventory["root"], "name": "artflow-studio"},
            "properties": [
                {"name": "artflow:git:revision", "value": revision},
                {"name": "artflow:gradle:configuration", "value": inventory["configuration"]},
                {"name": "artflow:inventory:scope", "value": "Resolved release runtime inputs before shrinking; not final APK contents"},
                {"name": "artflow:inventory:sha256", "value": fingerprint},
                {"name": "artflow:gradle:build-inputs", "value": json.dumps(inventory["buildInputs"], sort_keys=True)},
                {"name": "artflow:gradle:selection-edges", "value": json.dumps(inventory["edges"], sort_keys=True)},
            ],
        },
        "components": cdx_components,
        "dependencies": [{"ref": reference, "dependsOn": sorted(targets)} for reference, targets in sorted(adjacency.items())],
    }


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", type=Path, default=Path("."))
    parser.add_argument("--declarations-only", action="store_true")
    parser.add_argument("--declarations-out", type=Path)
    parser.add_argument("--resolved-inventory", type=Path)
    parser.add_argument("--sbom-out", type=Path)
    parser.add_argument("--revision", default="HEAD")
    args = parser.parse_args(argv[1:])
    if args.declarations_only and (args.resolved_inventory or args.sbom_out):
        parser.error("--declarations-only cannot produce an SBOM or validate resolved evidence")
    # A failed run must not leave an older successful SBOM at the requested output path.
    if args.sbom_out:
        args.sbom_out.unlink(missing_ok=True)
    try:
        declarations = extract_components(args.root / "build.gradle.kts", args.root / "app/build.gradle.kts")
        errors = check_declarations(declarations)
        if args.declarations_out:
            write_json(args.declarations_out, {"scope": "source declarations; versions may be dependency-managed; no license certification", "components": declarations})
        if errors:
            raise InventoryError("\n".join(errors))
        if args.declarations_only:
            print(f"OK: {len(declarations)} dependency/plugin declarations checked; resolved versions and licenses require the release inventory.")
            return 0
        path = args.resolved_inventory or args.root / "app/build/reports/dependencies/release-runtime.json"
        inventory = json.loads(path.read_text(encoding="utf-8"))
        validate_inventory(inventory, args.root)
        components = attach_license_evidence(inventory, path.parent)
        # Retain the complete truthful inventory even when license review fails; UNKNOWN never
        # becomes a fabricated SPDX identifier or a successful license gate.
        if args.sbom_out:
            write_json(args.sbom_out, build_cyclonedx_sbom(inventory, components, args.revision))
        errors = check_licenses(components)
        if errors:
            raise InventoryError("\n".join(errors))
        print(f"OK: {len(components)} resolved release components have permitted POM-declared license evidence. This is not a legal or vulnerability certification.")
        return 0
    except (OSError, ValueError, TypeError, KeyError) as error:
        print(f"Dependency evidence failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
