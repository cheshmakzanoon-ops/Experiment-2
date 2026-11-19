#!/usr/bin/env python3
"""Fetch one pinned official SDK kernel without replacing an AVD's Android image."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct
import tempfile
from urllib.parse import urlparse
from urllib.request import Request, urlopen
import zipfile


CHUNK_BYTES = 1024 * 1024
MAX_KERNEL_BYTES = 128 * CHUNK_BYTES
EXPECTED_PACKAGE = "system-images;android-36;google_apis_ps16k;x86_64"


def validate_pin(pin: dict) -> None:
    url = urlparse(pin["url"])
    if (
        url.scheme != "https"
        or url.netloc != "dl.google.com"
        or not url.path.startswith("/android/repository/sys-img/google_apis/")
        or url.query
        or url.fragment
    ):
        raise ValueError("Kernel archive must be an explicit official Google SDK HTTPS URL")
    if pin["package"] != EXPECTED_PACKAGE:
        raise ValueError("Unexpected kernel donor package")
    if pin["expected_api"] != 35 or ":15/" not in pin["expected_fingerprint"]:
        raise ValueError("The kernel control must retain Android 15 userspace")
    if not re.fullmatch(r"6\.6\.\d+-android15-8-g[0-9a-f]+-ab\d+", pin["kernel_release"]):
        raise ValueError("Kernel must remain in the android15-6.6 KMI generation 8 family")
    for key, length in (("archive_sha1", 40), ("kernel_sha256", 64)):
        if not re.fullmatch(r"[0-9a-f]{" + str(length) + r"}", pin[key]):
            raise ValueError(f"Invalid {key}")
    if pin.get("archive_sha256") and not re.fullmatch(r"[0-9a-f]{64}", pin["archive_sha256"]):
        raise ValueError("Invalid archive_sha256")
    if type(pin["archive_bytes"]) is not int or not 0 < pin["archive_bytes"] <= 4 * 1024**3:
        raise ValueError("Invalid archive size")
    if type(pin["kernel_bytes"]) is not int or not 0 < pin["kernel_bytes"] <= MAX_KERNEL_BYTES:
        raise ValueError("Invalid kernel size")
    if pin["member"] != "x86_64/kernel-ranchu":
        raise ValueError("Only the pinned kernel-ranchu member may be extracted")


def x86_kernel_release(data: bytes) -> str:
    if len(data) < 0x210 or data[0x202:0x206] != b"HdrS":
        raise ValueError("The donor is not a Linux x86 boot image")
    offset = struct.unpack_from("<H", data, 0x20E)[0] + 0x200
    if offset >= len(data):
        raise ValueError("Invalid kernel version offset")
    version = data[offset:offset + 512].split(b"\0", 1)[0].decode("ascii")
    return version.split(" ", 1)[0]


def verify_kernel(path: Path, pin: dict) -> dict:
    if path.is_symlink() or not path.is_file() or path.stat().st_size != pin["kernel_bytes"]:
        raise ValueError("Kernel file size/type does not match the pin")
    data = path.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if digest != pin["kernel_sha256"]:
        raise ValueError("Kernel SHA-256 does not match the pin")
    release = x86_kernel_release(data)
    if release != pin["kernel_release"]:
        raise ValueError("Kernel release does not match the pin")
    return {"kernel_bytes": len(data), "kernel_sha256": digest, "kernel_release": release}


def download_archive(pin: dict, destination: Path) -> dict:
    count = 0
    sha1 = hashlib.sha1()
    sha256 = hashlib.sha256()
    request = Request(pin["url"], headers={"User-Agent": "ArtFlow-CI-kernel-provenance/1"})
    with urlopen(request, timeout=60) as response, destination.open("xb") as output:
        while block := response.read(CHUNK_BYTES):
            count += len(block)
            if count > pin["archive_bytes"]:
                raise ValueError("SDK archive exceeded its pinned byte count")
            output.write(block)
            sha1.update(block)
            sha256.update(block)
    if count != pin["archive_bytes"] or sha1.hexdigest() != pin["archive_sha1"]:
        raise ValueError("SDK archive size/SHA-1 does not match official pinned metadata")
    if pin.get("archive_sha256") and sha256.hexdigest() != pin["archive_sha256"]:
        raise ValueError("SDK archive SHA-256 does not match the pin")
    return {"archive_bytes": count, "archive_sha1": sha1.hexdigest(), "archive_sha256": sha256.hexdigest()}


def extract_kernel(archive: Path, destination: Path, pin: dict) -> dict:
    with zipfile.ZipFile(archive) as source:
        members = [member for member in source.infolist() if member.filename == pin["member"]]
        if len(members) != 1:
            raise ValueError("SDK archive must contain exactly one pinned kernel member")
        member = members[0]
        mode = member.external_attr >> 16
        if (
            member.is_dir()
            or member.flag_bits & 1
            or stat.S_ISLNK(mode)
            or member.file_size != pin["kernel_bytes"]
            or member.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
        ):
            raise ValueError("Unexpected kernel ZIP member metadata")
        with source.open(member) as stream, destination.open("xb") as output:
            count = 0
            while block := stream.read(CHUNK_BYTES):
                count += len(block)
                if count > pin["kernel_bytes"]:
                    raise ValueError("Kernel ZIP member exceeded its pinned size")
                output.write(block)
    return verify_kernel(destination, pin)


def prepare_kernel(pin_path: Path, output: Path) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    manifest = output / "kernel-source.json"
    if pin_path.resolve() == manifest.resolve():
        raise ValueError("The input pin must be separate from generated provenance")
    # Never leave an earlier PASS record behind after a failed revalidation.
    manifest.unlink(missing_ok=True)
    pin = json.loads(pin_path.read_text(encoding="utf-8"))
    validate_pin(pin)
    target = output / "kernel-ranchu"
    provenance = dict(pin)
    provenance["kernel_path"] = str(target.resolve())
    # A cache is only useful after its complete pinned kernel identity is rechecked.
    if target.exists() or target.is_symlink():
        provenance.update(verify_kernel(target, pin))
        provenance["source"] = "verified-kernel-cache"
    else:
        with tempfile.TemporaryDirectory(prefix="sdk-kernel-", dir=output) as temporary:
            directory = Path(temporary)
            archive = directory / "donor.zip"
            provenance.update(download_archive(pin, archive))
            staged_kernel = directory / "kernel-ranchu"
            provenance.update(extract_kernel(archive, staged_kernel, pin))
            os.replace(staged_kernel, target)
        provenance["source"] = "verified-official-sdk-archive"
    provenance["status"] = "PASS"
    manifest.write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    return provenance


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    provenance = prepare_kernel(args.manifest, args.output)
    print(json.dumps(provenance, indent=2))


if __name__ == "__main__":
    main()
