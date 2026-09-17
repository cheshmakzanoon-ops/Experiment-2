from pathlib import Path

ROOT = Path.cwd()
BASE = 'app/src/main/java/com/artflow/studio/'


def replace(path, old, new):
    target = ROOT / path
    text = target.read_text(encoding='utf-8')
    if text.count(old) != 1:
        raise RuntimeError(f'Expected exactly one edit target in {path}: {old[:80]}')
    target.write_text(text.replace(old, new), encoding='utf-8')


replace(BASE + 'core/render/Compositor.kt',
'''                if (layer.adjustmentType != null) {
                    if (options.applyAdjustments) applyAdjustment(clipBase ?: result, input)
                    continue
                }''',
'''                if (layer.adjustmentType != null && options.applyAdjustments) {
                    applyAdjustment(clipBase ?: result, input)
                }
                if (layer.adjustmentType != null) continue''')
replace(BASE + 'core/render/StrokeRasterizer.kt',
'''        val (target, stroke, params, totalLength, alphaLock, mask, random) = context''',
'''        val target = context.target
        val stroke = context.stroke
        val params = context.params
        val totalLength = context.totalLength
        val alphaLock = context.alphaLock
        val mask = context.mask
        val random = context.random''')
replace(BASE + 'data/local/ProjectStorage.kt',
'OsConstants.O_RDONLY or OsConstants.O_DIRECTORY', 'OsConstants.O_RDONLY')
print('Applied compiler and analysis repairs; no quality checks disabled.')
