#!/usr/bin/env python3
"""Validate Gradle dependency licenses (Apache/MIT/BSD/EPL-compatible, rejecting GPL/AGPL) and generate a CycloneDX 1.5 SBOM."""

from __future__ import annotations

import json
import re
import sys
import uuid
from pathlib import Path

DEP_RE = re.compile(
    r'^\s*(implementation|testImplementation|androidTestImplementation|debugImplementation|ksp|kspAndroidTest)\("([^":]+):([^":]+):([^"]+)"\)'
)
BOM_RE = re.compile(r'platform\("([^":]+):([^":]+):([^"]+)"\)')
BOM_MEMBER_RE = re.compile(r'^\s*(implementation|androidTestImplementation)\("([^":]+):([^":]+)"\)')
PLUGIN_RE = re.compile(r'id\("([^"]+)"\)\s+version\s+"([^"]+)"')

DISALLOWED_LICENSES = {"GPL-2.0-only", "GPL-3.0-only", "GPL-3.0-or-later", "AGPL-3.0-only", "AGPL-3.0-or-later"}

# Verified SPDX licenses for declared Maven groups in this repository.
KNOWN_GROUP_LICENSES: dict[str, str] = {
    "androidx.core": "Apache-2.0",
    "androidx.lifecycle": "Apache-2.0",
    "androidx.activity": "Apache-2.0",
    "androidx.compose": "Apache-2.0",
    "androidx.compose.ui": "Apache-2.0",
    "androidx.compose.material3": "Apache-2.0",
    "androidx.compose.material": "Apache-2.0",
    "androidx.navigation": "Apache-2.0",
    "androidx.hilt": "Apache-2.0",
    "androidx.media3": "Apache-2.0",
    "androidx.graphics": "Apache-2.0",
    "androidx.room": "Apache-2.0",
    "androidx.test": "Apache-2.0",
    "androidx.test.ext": "Apache-2.0",
    "androidx.test.espresso": "Apache-2.0",
    "com.google.dagger": "Apache-2.0",
    "org.jetbrains.kotlinx": "Apache-2.0",
    "io.coil-kt": "Apache-2.0",
    "com.jakewharton.timber": "Apache-2.0",
    "junit": "EPL-1.0",
    "org.mockito": "MIT",
    "app.cash.turbine": "Apache-2.0",
}

KNOWN_PLUGIN_LICENSES: dict[str, str] = {
    "com.android.application": "Apache-2.0",
    "org.jetbrains.kotlin.android": "Apache-2.0",
    "org.jetbrains.kotlin.plugin.compose": "Apache-2.0",
    "org.jetbrains.kotlin.plugin.serialization": "Apache-2.0",
    "com.google.dagger.hilt.android": "Apache-2.0",
    "com.google.devtools.ksp": "Apache-2.0",
    "org.jlleitschuh.gradle.ktlint": "MIT",
    "io.gitlab.arturbosch.detekt": "Apache-2.0",
}


def extract_components(root_build: Path, app_build: Path) -> list[dict[str, str]]:
    components: list[dict[str, str]] = []
    seen: set[tuple[str, str, str, str]] = set()
    compose_bom_version = ""

    for line in root_build.read_text(encoding="utf-8").splitlines():
        m = PLUGIN_RE.search(line)
        if m:
            pid, ver = m.group(1), m.group(2)
            key = ("gradle-plugin", pid, ver, "build-plugin")
            if key not in seen:
                seen.add(key)
                components.append(
                    {
                        "group": "gradle-plugin",
                        "name": pid,
                        "version": ver,
                        "configuration": "build-plugin",
                        "scope": "excluded",
                        "license": KNOWN_PLUGIN_LICENSES.get(pid, "UNKNOWN"),
                    }
                )

    app_text = app_build.read_text(encoding="utf-8")
    for line in app_text.splitlines():
        bom_match = BOM_RE.search(line)
        if bom_match:
            g, a, v = bom_match.group(1), bom_match.group(2), bom_match.group(3)
            if a == "compose-bom":
                compose_bom_version = v
            key = (g, a, v, "implementation")
            if key not in seen:
                seen.add(key)
                components.append(
                    {
                        "group": g,
                        "name": a,
                        "version": v,
                        "configuration": "implementation",
                        "scope": "required",
                        "license": KNOWN_GROUP_LICENSES.get(g, "UNKNOWN"),
                    }
                )
            continue

        dep_match = DEP_RE.match(line)
        if dep_match:
            cfg, g, a, v = dep_match.groups()
            scope = "required" if cfg in {"implementation", "ksp"} else "optional"
            key = (g, a, v, cfg)
            if key not in seen:
                seen.add(key)
                components.append(
                    {
                        "group": g,
                        "name": a,
                        "version": v,
                        "configuration": cfg,
                        "scope": scope,
                        "license": KNOWN_GROUP_LICENSES.get(g, "UNKNOWN"),
                    }
                )
            continue

        mem_match = BOM_MEMBER_RE.match(line)
        if mem_match:
            cfg, g, a = mem_match.groups()
            ver = compose_bom_version or "bom-managed"
            scope = "required" if cfg == "implementation" else "optional"
            key = (g, a, ver, cfg)
            if key not in seen:
                seen.add(key)
                components.append(
                    {
                        "group": g,
                        "name": a,
                        "version": ver,
                        "configuration": cfg,
                        "scope": scope,
                        "license": KNOWN_GROUP_LICENSES.get(g, "UNKNOWN"),
                    }
                )
    return components


def check_licenses(components: list[dict[str, str]]) -> list[str]:
    errors: list[str] = []
    for comp in components:
        lic = comp["license"]
        if lic == "UNKNOWN":
            errors.append(f"Unverified license for {comp['group']}:{comp['name']}:{comp['version']} ({comp['configuration']})")
        elif lic in DISALLOWED_LICENSES:
            errors.append(f"Incompatible copyleft license {lic} on {comp['group']}:{comp['name']}:{comp['version']}")
    return errors


def build_cyclonedx_sbom(components: list[dict[str, str]], revision: str = "HEAD") -> dict[str, object]:
    serial = str(uuid.uuid5(uuid.NAMESPACE_URL, f"https://github.com/cheshmakzanoon-ops/Experiment-2@{revision}"))
    cdx_components = []
    for comp in components:
        purl = (
            f"pkg:maven/{comp['group']}/{comp['name']}@{comp['version']}"
            if comp["group"] != "gradle-plugin"
            else f"pkg:gradle/{comp['name']}@{comp['version']}"
        )
        cdx_components.append(
            {
                "type": "library",
                "group": comp["group"],
                "name": comp["name"],
                "version": comp["version"],
                "scope": comp["scope"],
                "purl": purl,
                "licenses": [{"license": {"id": comp["license"]}}],
            }
        )
    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": f"urn:uuid:{serial}",
        "version": 1,
        "metadata": {
            "component": {
                "type": "application",
                "group": "com.artflow",
                "name": "artflow-studio",
                "version": "1.1.0",
            },
            "properties": [
                {"name": "artflow:git:revision", "value": revision},
            ],
        },
        "components": cdx_components,
    }


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) > 1 and not argv[1].startswith("--") else Path(".")
    sbom_out: Path | None = None
    revision = "HEAD"
    args = argv[1:]
    for i, token in enumerate(args):
        if token == "--sbom-out" and i + 1 < len(args):
            sbom_out = Path(args[i + 1])
        elif token == "--revision" and i + 1 < len(args):
            revision = args[i + 1]

    components = extract_components(root / "build.gradle.kts", root / "app" / "build.gradle.kts")
    if not components:
        print("No Gradle dependencies or plugins extracted", file=sys.stderr)
        return 1
    errors = check_licenses(components)
    if errors:
        for err in errors:
            print(err, file=sys.stderr)
        return 1
    sbom = build_cyclonedx_sbom(components, revision=revision)
    if sbom_out is not None:
        sbom_out.parent.mkdir(parents=True, exist_ok=True)
        sbom_out.write_text(json.dumps(sbom, indent=2) + "\n", encoding="utf-8")
    print(f"OK: Verified {len(components)} Gradle components with zero GPL/AGPL or unknown licenses.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
