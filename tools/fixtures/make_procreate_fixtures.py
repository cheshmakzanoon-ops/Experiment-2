"""Builds the synthetic .procreate test fixtures in app/src/test/resources/procreate.

Tiles are compressed with the reference LZO library (liblzo2, via ctypes) and python-lz4; the
keyed archive is written with plistlib. Usage: python3 make_procreate_fixtures.py <output dir>
(needs liblzo2 and `pip install lz4`). The LZO reference files (lzo-original.bin, lzo1x_1.bin,
lzo1x_999.bin) come from the same library's lzo1x_1 and lzo1x_999 compressors.
"""
import ctypes, io, plistlib, struct, sys, zipfile, zlib
import lz4.block

OUT = sys.argv[1]
W, H, T = 300, 200, 128
lzo = ctypes.CDLL('liblzo2.so.2')

def lzo_compress(data):
    out = ctypes.create_string_buffer(len(data) + len(data) // 16 + 64 + 3)
    n = ctypes.c_size_t(0)
    wrk = ctypes.create_string_buffer(1 << 22)
    assert lzo.lzo1x_999_compress(data, ctypes.c_size_t(len(data)), out, ctypes.byref(n), wrk) == 0
    return out.raw[:n.value]

# --- Upright layers (straight ARGB tuples) -------------------------------------------------
def sky(x, y): return (255, x * 255 // (W - 1), y * 255 // (H - 1), 200)
def line(x, y): return (255, 0, 0, 0) if abs(2 * x - 3 * y) < 6 else (0, 0, 0, 0)
def color(x, y):
    if 50 <= x < 150 and 40 <= y < 120: return (255, 255, 0, 0)
    if 200 <= x < 260 and 150 <= y < 190: return (128, 0, 128, 255)
    return (0, 0, 0, 0)
def masked(x, y): return (255, 0, 0, 255) if (x - 240) ** 2 + (y - 60) ** 2 < 50 ** 2 else (0, 0, 0, 0)
def mask(x, y): return (255, 255, 255, 255) if x < 240 else (255, 0, 0, 0)

def image(fn): return [[fn(x, y) for x in range(W)] for y in range(H)]

def over(top, bottom):
    a = top[0] / 255
    out_a = a + bottom[0] / 255 * (1 - a)
    if out_a == 0: return (0, 0, 0, 0)
    ch = [round((top[i] * a + bottom[i] * bottom[0] / 255 * (1 - a)) / out_a) for i in (1, 2, 3)]
    return (round(out_a * 255), *ch)

LAYERS = {'sky': image(sky), 'line': image(line), 'color': image(color), 'masked': image(masked), 'mask': image(mask)}
# Composite of the visible layers, bottom up (blend modes ignored: it only has to match the thumbnail).
composite = [[(0, 0, 0, 0)] * W for _ in range(H)]
for name in ['masked', 'line', 'color']:
    for y in range(H):
        for x in range(W):
            composite[y][x] = over(LAYERS[name][y][x], composite[y][x])
LAYERS['composite'] = composite
BACKGROUND = (0.9, 0.85, 0.8, 1.0)

def thumbnail_png():
    bg = [round(c * 255) for c in BACKGROUND[:3]]
    s = 4
    rows = []
    for ty in range(H // s):
        row = bytearray([0])
        for tx in range(W // s):
            acc = [0, 0, 0]
            for y in range(ty * s, ty * s + s):
                for x in range(tx * s, tx * s + s):
                    p = over(composite[y][x], (255, *bg))
                    for i in range(3): acc[i] += p[i + 1]
            row += bytes([round(a / (s * s)) for a in acc])
        rows.append(bytes(row))
    def chunk(kind, data): return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    ihdr = struct.pack('>IIBBBBB', W // s, H // s, 8, 2, 0, 0, 0)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr) + chunk(b'IDAT', zlib.compress(b''.join(rows))) + chunk(b'IEND', b'')

# --- Stored layout ------------------------------------------------------------------------
def rot_cw(img):
    h, w = len(img), len(img[0])
    return [[img[h - 1 - x][y] for x in range(h)] for y in range(w)]  # B(x, y) = A(y, h - 1 - x)
def rot_ccw(img): return rot_cw(rot_cw(rot_cw(img)))
def mirror(img): return [list(reversed(row)) for row in img]

def stored_layout(img, turns, mirrored):
    # Upright = rot^turns(mirror(stored)), so stored = mirror(rot^-turns(upright)).
    for _ in range(turns): img = rot_ccw(img)
    return mirror(img) if mirrored else img

def premultiplied_bytes(px, bgra):
    a, r, g, b = px
    r, g, b = (round(c * a / 255) for c in (r, g, b))
    return bytes([b, g, r, a] if bgra else [r, g, b, a])

def tiles(img, bgra, cut_edges):
    h, w = len(img), len(img[0])
    for row in range((h + T - 1) // T):
        for col in range((w + T - 1) // T):
            tw = min(T, w - col * T) if cut_edges else T
            th = min(T, h - row * T) if cut_edges else T
            data = bytearray()
            for y in range(th):
                for x in range(tw):
                    sx, sy = col * T + x, row * T + y
                    data += premultiplied_bytes(img[sy][sx], bgra) if sx < w and sy < h else bytes(4)
            if any(data[3::4]): yield col, row, bytes(data)

def apple_lz4(data, index):
    if index % 5 == 4:  # some tiles stored uncompressed
        return b'bvx-' + struct.pack('<I', len(data)) + data + b'bvx$'
    half = len(data) // 2
    first = lz4.block.compress(data[:half], store_size=False)
    # The second block may refer back into the first, as Apple's encoder does.
    second = lz4.block.compress(data[half:], store_size=False, dict=data[:half])
    assert lz4.block.decompress(second, uncompressed_size=len(data) - half, dict=data[:half]) == data[half:]
    return (b'bv41' + struct.pack('<II', half, len(first)) + first +
            b'bv41' + struct.pack('<II', len(data) - half, len(second)) + second + b'bv4$')

# --- Archive ------------------------------------------------------------------------------
class Archive:
    def __init__(self): self.objects = ['$null']
    def add(self, obj):
        self.objects.append(obj)
        return plistlib.UID(len(self.objects) - 1)
    def cls(self, name): return self.add({'$classname': name, '$classes': [name, 'NSObject']})

def build(path, turns, mirrored, bgra, codec, cut_edges):
    ar = Archive()
    root = {}
    root_uid = ar.add(root)
    layer_cls, group_cls, array_cls = ar.cls('SilicaLayer'), ar.cls('SilicaGroup'), ar.cls('NSMutableArray')
    def layer(uuid, name, **extra):
        d = {'$class': layer_cls, 'UUID': ar.add(uuid), 'name': ar.add(name), 'opacity': 1.0, 'hidden': False,
             'blend': 0, 'clipped': False, 'preserve': False}
        d.update(extra)
        return ar.add(d)
    def array(items): return ar.add({'$class': array_cls, 'NS.objects': items})
    mask_uid = layer('MASK-UUID', 'Mask')
    inks = ar.add({'$class': group_cls, 'name': ar.add('Inks'), 'hidden': False, 'opacity': 0.5,
                   'children': array([layer('COLOR-UUID', 'Color', clipped=True, preserve=True, blend=1, opacity=0.75),
                                      layer('LINE-UUID', 'Line')])})
    layers = array([layer('SKY-UUID', 'Sky', hidden=True, blend=0, extendedBlend=21), inks,
                    layer('MASKED-UUID', 'Masked', mask=mask_uid)])
    sw, sh = (H, W) if turns % 2 else (W, H)
    root.update({'$class': ar.cls('SilicaDocument'), 'size': ar.add('{%d, %d}' % (sw, sh)), 'tileSize': T,
                 'layers': layers, 'composite': layer('COMPOSITE-UUID', 'Composite'), 'name': ar.add('Fixture'),
                 'backgroundColor': ar.add(struct.pack('<4f', *BACKGROUND)), 'backgroundHidden': False,
                 'SilicaDocumentArchiveDPIKey': 264.0, 'orientation': 1})
    top = {'$archiver': 'NSKeyedArchiver', '$version': 100000, '$top': {'root': root_uid}, '$objects': ar.objects}
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_STORED) as z:
        z.writestr('Document.archive', plistlib.dumps(top, fmt=plistlib.FMT_BINARY))
        z.writestr('QuickLook/Thumbnail.png', thumbnail_png())
        names = {'sky': 'SKY-UUID', 'line': 'LINE-UUID', 'color': 'COLOR-UUID', 'masked': 'MASKED-UUID',
                 'mask': 'MASK-UUID', 'composite': 'COMPOSITE-UUID'}
        index = 0
        for key, uuid in names.items():
            for col, row, data in tiles(stored_layout(LAYERS[key], turns, mirrored), bgra, cut_edges):
                packed = lzo_compress(data) if codec == 'lzo' else apple_lz4(data, index)
                z.writestr('%s/%d~%d.%s' % (uuid, col, row, 'chunk' if codec == 'lzo' else 'lz4'), packed,
                           compress_type=zipfile.ZIP_DEFLATED)
                index += 1

build(OUT + '/upright.procreate', 0, False, False, 'lzo', True)
build(OUT + '/turned.procreate', 1, True, True, 'lz4', False)


# --- Brushes ------------------------------------------------------------------------------
def brush_archive(settings):
    ar = Archive()
    root = {}
    root_uid = ar.add(root)
    for key, value in settings.items():
        root[key] = ar.add(value) if isinstance(value, str) else value
    root['$class'] = ar.add({'$classname': 'SilicaBrush', '$classes': ['SilicaBrush', 'ValkyrieBrush', 'NSObject']})
    top = {'$archiver': 'NSKeyedArchiver', '$version': 100000, '$top': {'root': root_uid}, '$objects': ar.objects}
    return plistlib.dumps(top, fmt=plistlib.FMT_BINARY)

def grey_png(size, fn):
    rows = b''.join(b'\x00' + bytes(fn(x, y) for x in range(size)) for y in range(size))
    def chunk(kind, data): return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 0, 0, 0, 0)) +
            chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b''))

def build_brushset(path):
    inked = {'name': 'Inked Grain', 'plotSpacing': 0.2, 'plotJitter': 0.3, 'shapeCount': 0.2, 'shapeRoundness': 0.5,
             'dynamicsPressureSize': 0.8, 'dynamicsPressureOpacity': 0.1, 'maxSize': 2.0, 'paintSize': 0.5,
             'maxOpacity': 0.9, 'paintOpacity': 1.0, 'blendMode': 1, 'extendedBlend': 22, 'shapeInverted': True,
             'textureInverted': False, 'bundledShapePath': '$null', 'bundledGrainPath': '$null', 'shapeRandomise': True,
             'renderingRecursiveMixing': True, 'dynamicsMix': 0.4, 'textureMovement': 0.8, 'pencilTaperStartLength': 0.25,
             'dynamicsJitterHue': 0.15, 'textureScale': 1.5, 'dynamicsGlazedFlow': 0.7}
    plain = {'name': 'Plain', 'bundledShapePath': 'Brush-Preset-Blank.png', 'plotSpacing': 0.05}
    disc = lambda x, y: 255 if (x - 16) ** 2 + (y - 16) ** 2 < 100 else 0
    noise = lambda x, y: (x * 37 + y * 91) % 256
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('brushset.plist', plistlib.dumps({'name': 'Fixture Set', 'brushes': ['B-PLAIN', 'A-INKED']}))
        z.writestr('A-INKED/Brush.archive', brush_archive(inked))
        z.writestr('A-INKED/Shape.png', grey_png(32, disc))
        z.writestr('A-INKED/Grain.png', grey_png(32, noise))
        z.writestr('B-PLAIN/Brush.archive', brush_archive(plain))
        z.writestr('B-PLAIN/Grain.png', grey_png(32, noise))

build_brushset(OUT + '/fixture.brushset')


# --- Photoshop brushes (.abr), laid out as GIMP's and Krita's loaders read them ----------------
def packbits(row):
    out, i = bytearray(), 0
    while i < len(row):
        run = 1
        while i + run < len(row) and run < 128 and row[i + run] == row[i]: run += 1
        if run > 1:
            out += bytes([(257 - run) & 0xFF, row[i]]); i += run
        else:
            j = i
            while j < len(row) and j - i < 128 and (j + 1 >= len(row) or row[j + 1] != row[j]): j += 1
            j = max(j, i + 1)
            out += bytes([j - i - 1]) + bytes(row[i:j]); i = j
    return bytes(out)

def tip_pixels(width, height, depth, compressed, fn):
    rows = []
    for y in range(height):
        row = bytearray()
        for x in range(width):
            v = fn(x, y)
            row += bytes([v, (v * 7) & 0xFF]) if depth == 16 else bytes([v])
        rows.append(bytes(row))
    head = struct.pack('>hb', depth, 1 if compressed else 0)
    if not compressed: return head + b''.join(rows)
    packed = [packbits(r) for r in rows]
    return head + b''.join(struct.pack('>h', len(p)) for p in packed) + b''.join(packed)

def unicode_text(text):
    return struct.pack('>I', len(text) + 1) + (text + '\0').encode('utf-16-be')

def build_abr6(path):
    ring = lambda x, y: 255 if 36 <= (x - 10) ** 2 + (y - 7) ** 2 < 49 else 0
    ramp = lambda x, y: (x * 255) // 11
    samples = [('$1111aaaa-0000-0000-0000-000000000001', 20, 14, 8, True, ring),
               ('$2222bbbb-0000-0000-0000-000000000002', 12, 9, 16, False, ramp)]
    samp = bytearray()
    for key, w, h, depth, compressed, fn in samples:
        body = bytes([len(key)]) + key.encode('ascii')
        body += bytes(301 - len(body))
        body += struct.pack('>iiii', 2, 3, 2 + h, 3 + w) + tip_pixels(w, h, depth, compressed, fn)
        samp += struct.pack('>I', len(body)) + body + bytes((4 - len(body) % 4) % 4)
    # Presets name the tips they use; the second preset is listed first.
    desc = bytearray(b'\x00\x00\x00\x10null')
    for name, key in [('Smooth Ramp', samples[1][0]), ('Ring Tip', samples[0][0])]:
        desc += b'\x00\x00\x00\x00Nm  TEXT' + unicode_text(name)
        desc += b'\x00\x00\x00\x00BrshObjc' + b'\x00\x00\x00\x0bsampledDataTEXT' + unicode_text(key)
    data = struct.pack('>hh', 6, 2)
    for tag, body in [(b'samp', bytes(samp)), (b'desc', bytes(desc))]:
        data += b'8BIM' + tag + struct.pack('>I', len(body)) + body
    open(path, 'wb').write(data)

def build_abr2(path):
    cross = lambda x, y: 255 if x == 4 or y == 3 else 0
    computed = struct.pack('>ihhhhb', 0, 25, 30, 0, 100, 0)
    sampled = struct.pack('>ih', 0, 25) + unicode_text('Cross') + b'\x01' + struct.pack('>hhhh', 0, 0, 7, 9)
    sampled += struct.pack('>iiii', 0, 0, 7, 9) + tip_pixels(9, 7, 8, True, cross)
    data = struct.pack('>hh', 2, 2)
    data += struct.pack('>hI', 1, len(computed)) + computed + struct.pack('>hI', 2, len(sampled)) + sampled
    open(path, 'wb').write(data)

build_abr6(OUT + '/tips-v6.abr')
build_abr2(OUT + '/tips-v2.abr')
