"""Rebuild synthetic colour-import fixtures with self-authored ICC profiles.

Usage: python tools/fixtures/make_procreate_color_fixture.py [--samples /path/to/demo_files]
No reference artwork or original ICC payload is copied. The profiles reproduce the observed
numeric colourimetry with our own metadata. The optional samples argument verifies the original
payload hashes. Python's standard library writes the archive, PNG and uncompressed LZ4 blocks.
"""

import argparse
import hashlib
import json
import plistlib
import struct
import zipfile
import zlib
from pathlib import Path


WIDTH, HEIGHT, TILE = 64, 48, 64
BACKGROUND = (240, 30, 90)
NATIVE_PROFILES = {
    "apple": (
        "Mask_Test_File.procreate",
        "0ff6958f98684c61f6bbdce1368ddeaf3873baf84545baba482e920d92a914c0",
    ),
    "derived": (
        "Reference_Blend_File.procreate",
        "20789fdbea9835251a4f0796c8bf45cbd964896044886540da21ffc7457af0ab",
    ),
}


def authored_profile(function):
    """ICC v4 matrix profile; observed s15Fixed16 values, independently written structure."""
    def xyz(values):
        return b"XYZ " + bytes(4) + struct.pack(">3i", *(round(value * 65536) for value in values))

    def text(value):
        encoded = value.encode("utf-16be")
        return b"mluc" + bytes(4) + struct.pack(">II4sII", 1, 12, b"enUS", len(encoded), 28) + encoded

    white = (0.9641876220703125, 1.0, 0.82489013671875)
    parameters = (2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045)
    if function == 4:
        parameters += (0.0, 0.0)
    curve = b"para" + bytes(4) + struct.pack(">HH", function, 0)
    curve += struct.pack(">" + "i" * len(parameters), *(round(value * 65536) for value in parameters))
    tags = [
        (b"desc", text("ArtFlow synthetic Display P3 fixture")),
        (b"cprt", text("No copyright, use freely")),
        (b"wtpt", xyz(white)),
        (b"rXYZ", xyz((0.5151214599609375, 0.2411956787109375, -0.0010528564453125))),
        (b"gXYZ", xyz((0.2919769287109375, 0.6922454833984375, 0.0418853759765625))),
        (b"bXYZ", xyz((0.1571044921875, 0.0665740966796875, 0.7840728759765625))),
        (b"rTRC", curve), (b"gTRC", curve), (b"bTRC", curve),
    ]
    output = bytearray(132 + 12 * len(tags))
    struct.pack_into(">I", output, 8, 0x04000000)
    output[12:24] = b"mntrRGB XYZ "
    struct.pack_into(">6H", output, 24, 2026, 1, 1, 0, 0, 0)
    output[36:40] = b"acsp"
    struct.pack_into(">3i", output, 68, *(round(value * 65536) for value in white))
    struct.pack_into(">I", output, 128, len(tags))
    shared = {}
    for index, (name, body) in enumerate(tags):
        if body not in shared:
            shared[body] = len(output)
            output.extend(body)
            output.extend(bytes((-len(output)) % 4))
        struct.pack_into(">4sII", output, 132 + index * 12, name, shared[body], len(body))
    struct.pack_into(">I", output, 0, len(output))
    return bytes(output)


def icc_from_sample(path):
    with zipfile.ZipFile(path) as archive:
        png = archive.read("QuickLook/Thumbnail.png")
    position = 8
    while position + 12 <= len(png):
        size = struct.unpack_from(">I", png, position)[0]
        kind = png[position + 4:position + 8]
        body = png[position + 8:position + 8 + size]
        position += size + 12
        if kind == b"iCCP":
            compressed = body.split(b"\0", 1)[1]
            assert compressed[0] == 0
            return zlib.decompress(compressed[1:])
    raise ValueError(f"No ICC profile in {path.name}")


def native_pixel(x, y):
    if x < 8 and y < 8:
        return (0, 0, 0, 0)
    if x >= 48 and y >= 32:
        return (128, 200, 40, 180)
    colours = ((230, 70, 45), (20, 180, 120), (40, 70, 230), (190, 30, 185))
    return (255, *colours[(y >= HEIGHT // 2) * 2 + (x >= WIDTH // 2)])


def premultiplied(pixel):
    alpha, red, green, blue = pixel
    return (alpha, *((channel * alpha + 127) // 255 for channel in (red, green, blue)))


def straight(pixel):
    alpha, *channels = pixel
    if alpha == 0:
        return (0, 0, 0, 0)
    return (alpha, *(min(255, (channel * 255 + alpha // 2) // alpha) for channel in channels))


def png(pixels, profile):
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))

    rows = bytearray()
    for row in pixels:
        rows.append(0)
        for alpha, red, green, blue in row:
            rows.extend((red, green, blue, alpha))
    header = struct.pack(">IIBBBBB", WIDTH, HEIGHT, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"iCCP", b"kCGColorSpaceDisplayP3\0\0" + zlib.compress(profile))
            + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def description(profile):
    objects = ["$null"]

    def add(value):
        objects.append(value)
        return plistlib.UID(len(objects) - 1)

    def cls(name):
        return add({"$classname": name, "$classes": [name, "NSObject"]})

    layer_class = cls("SilicaLayer")

    def layer(uuid, name):
        return add({"$class": layer_class, "UUID": add(uuid), "name": add(name),
                    "opacity": 1.0, "hidden": False, "blend": 0})

    native_profile = add({
        "$class": cls("SiColorProfile"),
        "SiColorProfileArchiveICCNameKey": add("ColorspaceDerived"),
        "SiColorProfileArchiveICCDataKey": add(profile),
    })
    document = {
        "$class": cls("SilicaDocument"), "size": add(f"{{{HEIGHT}, {WIDTH}}}"), "tileSize": TILE,
        "layers": add({"$class": cls("NSMutableArray"), "NS.objects": [layer("INK", "P3 ink")]}),
        "composite": layer("COMPOSITE", "Composite"), "name": add("Synthetic P3 import"),
        "backgroundColor": add(struct.pack("<4f", *(channel / 255 for channel in BACKGROUND), 1.0)),
        "backgroundHidden": False, "SilicaDocumentArchiveDPIKey": 144.0, "colorProfile": native_profile,
    }
    root = add(document)
    return plistlib.dumps({"$archiver": "NSKeyedArchiver", "$version": 100000,
                          "$top": {"root": root}, "$objects": objects}, fmt=plistlib.FMT_BINARY)


def build(destination, profile):
    source = [[premultiplied(native_pixel(x, y)) for x in range(WIDTH)] for y in range(HEIGHT)]
    expected = [[straight(pixel) for pixel in row] for row in source]
    # Upright = clockwise(mirror(stored)); stored is a counterclockwise turn, then a mirror.
    turned = [[source[y][WIDTH - 1 - x] for y in range(HEIGHT)] for x in range(WIDTH)]
    stored = [list(reversed(row)) for row in turned]
    rgba = bytearray()
    for y in range(TILE):
        for x in range(TILE):
            alpha, red, green, blue = stored[y][x] if x < HEIGHT and y < WIDTH else (0, 0, 0, 0)
            rgba.extend((blue, green, red, alpha))  # Deliberately BGRA.
    packed = b"bvx-" + struct.pack("<I", len(rgba)) + rgba + b"bvx$"
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, body in (("Document.archive", description(profile)), ("QuickLook/Thumbnail.png", png(expected, profile)),
                           ("INK/0~0.lz4", packed), ("COMPOSITE/0~0.lz4", packed)):
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, body)
    return b"".join(bytes(pixel) for row in expected for pixel in row)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=Path)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    resources = args.repo_root / "app/src/test/resources/procreate/color"
    assets = args.repo_root / "app/src/androidTest/assets/procreate/color"
    resources.mkdir(parents=True, exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)
    if args.samples is not None:
        for sample, digest in NATIVE_PROFILES.values():
            data = icc_from_sample(args.samples / sample)
            assert len(data) == 536 and hashlib.sha256(data).hexdigest() == digest
    profiles = {f"display-p3-parametric{function}.icc": authored_profile(function) for function in (3, 4)}
    for name, data in profiles.items():
        (resources / name).write_bytes(data)
    document = resources / "display-p3-derived.procreate"
    expected = build(document, profiles["display-p3-parametric3.icc"])
    (resources / "display-p3-native.argb").write_bytes(expected)
    for name in (document.name, "display-p3-native.argb"):
        (assets / name).write_bytes((resources / name).read_bytes())
    report = {"width": WIDTH, "height": HEIGHT, "backgroundARGB": "fff01e5a", "dpi": 144,
              "artwork": "synthetic", "storedLayout": "quarter-turn, mirror, BGRA, premultiplied",
              "profileName": "ColorspaceDerived", "profilePayloads": "self-authored; numeric matrix/TRCs from inspected profiles",
              "nativeProfileSha256": {key: value[1] for key, value in NATIVE_PROFILES.items()},
              "documentSha256": hashlib.sha256(document.read_bytes()).hexdigest(),
              "nativeArgbSha256": hashlib.sha256(expected).hexdigest()}
    (resources / "fixture.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
