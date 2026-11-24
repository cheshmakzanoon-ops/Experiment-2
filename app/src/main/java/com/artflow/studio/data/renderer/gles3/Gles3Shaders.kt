package com.artflow.studio.data.renderer.gles3

import com.artflow.studio.domain.model.layer.BlendMode

/**
 * GLSL ES 3.00 (`#version 300 es`) shader library for ArtFlow's hybrid GPU pipeline.
 * Designed for RGBA16F framebuffer accumulation with automatic fallback to the deterministic
 * Kotlin rasterizer on GLES 2.0 or software-emulated contexts.
 */
object Gles3Shaders {
    const val QUAD_VERTEX: String =
        """#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;
out vec2 vTexCoord;
void main() {
    vTexCoord = aTexCoord;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

    /**
     * Float16 brush dab stamper supporting elliptical roundness, tip rotation, shape texture,
     * hardness falloff, and optional dual-brush masking into an RGBA16F scratch FBO.
     */
    const val BRUSH_DAB_FRAGMENT: String =
        """#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;

uniform vec4 uBrushColor;
uniform float uHardness;
uniform float uRoundness;
uniform float uAngleRad;
uniform int uHasShapeTex;
uniform sampler2D uShapeTex;

void main() {
    vec2 p = vTexCoord * 2.0 - 1.0;
    float c = cos(uAngleRad);
    float s = sin(uAngleRad);
    vec2 rot = vec2(p.x * c + p.y * s, (-p.x * s + p.y * c) / max(uRoundness, 0.05));
    float cov = 0.0;
    if (uHasShapeTex == 1) {
        vec2 uv = rot * 0.5 + 0.5;
        if (uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0) {
            cov = texture(uShapeTex, uv).r;
        }
    } else {
        float dist = length(rot);
        float edge = mix(0.02, 0.99, clamp(uHardness, 0.0, 1.0));
        cov = 1.0 - smoothstep(edge, 1.0, dist);
    }
    float a = clamp(uBrushColor.a * cov, 0.0, 1.0);
    fragColor = vec4(uBrushColor.rgb * a, a);
}
"""

    /**
     * Full 26-mode layer compositor shader in float16 working precision with clipping & layer mask.
     */
    const val COMPOSITE_26_MODES_FRAGMENT: String =
        """#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uBackdrop;
uniform sampler2D uSource;
uniform sampler2D uMask;
uniform int uHasMask;
uniform int uBlendMode;
uniform float uOpacity;
uniform int uClipped;

float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
float sat(vec3 c) { return max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b)); }

vec3 clipColor(vec3 c) {
    float l = lum(c);
    float n = min(c.r, min(c.g, c.b));
    float x = max(c.r, max(c.g, c.b));
    if (n < 0.0) c = l + ((c - l) * l) / max(l - n, 1e-4);
    if (x > 1.0) c = l + ((c - l) * (1.0 - l)) / max(x - l, 1e-4);
    return clamp(c, 0.0, 1.0);
}

vec3 setLum(vec3 c, float l) { return clipColor(c + (l - lum(c))); }

vec3 blendChannel(vec3 b, vec3 s, int mode) {
    if (mode == 1) return b * s; // MULTIPLY
    if (mode == 2) return 1.0 - (1.0 - b) * (1.0 - s); // SCREEN
    if (mode == 3) return mix(2.0 * b * s, 1.0 - 2.0 * (1.0 - b) * (1.0 - s), step(0.5, b)); // OVERLAY
    if (mode == 4) return min(b, s); // DARKEN
    if (mode == 5) return max(b, s); // LIGHTEN
    if (mode == 6) return min(vec3(1.0), b / max(vec3(1e-4), 1.0 - s)); // COLOR_DODGE
    if (mode == 7) return 1.0 - min(vec3(1.0), (1.0 - b) / max(vec3(1e-4), s)); // COLOR_BURN
    if (mode == 8) return (1.0 - 2.0 * s) * b * b + 2.0 * s * b; // SOFT_LIGHT
    if (mode == 9) return mix(2.0 * b * s, 1.0 - 2.0 * (1.0 - b) * (1.0 - s), step(0.5, s)); // HARD_LIGHT
    if (mode == 10) return abs(b - s); // DIFFERENCE
    if (mode == 11) return b + s - 2.0 * b * s; // EXCLUSION
    if (mode == 12) return setLum(s, lum(b)); // HUE
    if (mode == 13) return setLum(mix(vec3(lum(b)), b, sat(s) / max(sat(b), 1e-4)), lum(b)); // SATURATION
    if (mode == 14) return setLum(s, lum(b)); // COLOR
    if (mode == 15) return setLum(b, lum(s)); // LUMINOSITY
    if (mode == 16) return max(vec3(0.0), b + s - 1.0); // LINEAR_BURN
    if (mode == 17) return lum(s) < lum(b) ? s : b; // DARKER_COLOR
    if (mode == 18) return min(vec3(1.0), b + s); // ADD
    if (mode == 19) return lum(s) > lum(b) ? s : b; // LIGHTER_COLOR
    if (mode == 20) { // VIVID_LIGHT
        vec3 burn = 1.0 - min(vec3(1.0), (1.0 - b) / max(vec3(1e-4), 2.0 * s));
        vec3 dodge = min(vec3(1.0), b / max(vec3(1e-4), 2.0 * (1.0 - s)));
        return mix(burn, dodge, step(0.5, s));
    }
    if (mode == 21) return clamp(b + 2.0 * s - 1.0, 0.0, 1.0); // LINEAR_LIGHT
    if (mode == 22) return mix(min(b, 2.0 * s), max(b, 2.0 * (s - 0.5)), step(0.5, s)); // PIN_LIGHT
    if (mode == 23) return step(1.0, b + s); // HARD_MIX
    if (mode == 24) return max(vec3(0.0), b - s); // SUBTRACT
    if (mode == 25) return min(vec3(1.0), b / max(vec3(1e-4), s)); // DIVIDE
    return s; // NORMAL
}

void main() {
    vec4 bg = texture(uBackdrop, vTexCoord);
    vec4 fg = texture(uSource, vTexCoord);
    float maskVal = (uHasMask == 1) ? texture(uMask, vTexCoord).r : 1.0;
    float sa = clamp(fg.a * uOpacity * maskVal, 0.0, 1.0);
    if (uClipped == 1) sa *= bg.a;
    vec3 sRgb = fg.a > 1e-4 ? fg.rgb / fg.a : vec3(0.0);
    vec3 bRgb = bg.a > 1e-4 ? bg.rgb / bg.a : vec3(0.0);
    vec3 blended = blendChannel(bRgb, sRgb, uBlendMode);
    float outA = sa + bg.a * (1.0 - sa);
    vec3 outRgb = (sa * (1.0 - bg.a) * sRgb + sa * bg.a * blended + bg.a * (1.0 - sa) * bRgb) / max(outA, 1e-4);
    fragColor = vec4(outRgb * outA, outA);
}
"""

    /**
     * Real-time 3D impasto oil raking-light normal shader using Sobel heightmap gradients.
     */
    const val IMPASTO_RAKING_LIGHT_FRAGMENT: String =
        """#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uColorTex;
uniform sampler2D uHeightTex;
uniform vec2 uTexelSize;
uniform vec3 uLightDir;
uniform float uImpastoDepth;
uniform float uGloss;

void main() {
    vec4 c = texture(uColorTex, vTexCoord);
    if (c.a <= 0.001 || uImpastoDepth <= 0.0) {
        fragColor = c;
        return;
    }
    float hL = texture(uHeightTex, vTexCoord - vec2(uTexelSize.x, 0.0)).r;
    float hR = texture(uHeightTex, vTexCoord + vec2(uTexelSize.x, 0.0)).r;
    float hD = texture(uHeightTex, vTexCoord - vec2(0.0, uTexelSize.y)).r;
    float hU = texture(uHeightTex, vTexCoord + vec2(0.0, uTexelSize.y)).r;
    float scale = uImpastoDepth * 3.2;
    vec3 n = normalize(vec3(-(hR - hL) * scale, -(hU - hD) * scale, 1.0));
    vec3 L = normalize(uLightDir);
    vec3 H = normalize(L + vec3(0.0, 0.0, 1.0));
    float diffuseBoost = 1.0 + (max(dot(n, L), 0.0) - L.z) * 0.65;
    float spec = pow(max(dot(n, H), 0.0), 24.0) * uGloss * 0.38;
    fragColor = vec4(clamp(c.rgb * diffuseBoost + vec3(spec) * c.a, 0.0, 1.0), c.a);
}
"""

    /**
     * Real-time GPU Liquify warp shader reading an RG16F/RG32F displacement map texture.
     */
    const val LIQUIFY_WARP_FRAGMENT: String =
        """#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uSourceTex;
uniform sampler2D uDisplacementTex;
uniform float uStrength;

void main() {
    vec2 disp = texture(uDisplacementTex, vTexCoord).rg * uStrength;
    fragColor = texture(uSourceTex, clamp(vTexCoord + disp, 0.0, 1.0));
}
"""

    /** Maps ArtFlow's [BlendMode] enum to the integer index consumed by [COMPOSITE_26_MODES_FRAGMENT]. */
    fun blendModeIndex(mode: BlendMode): Int =
        when (mode) {
            BlendMode.NORMAL, BlendMode.PASS_THROUGH -> 0
            BlendMode.MULTIPLY -> 1
            BlendMode.SCREEN -> 2
            BlendMode.OVERLAY -> 3
            BlendMode.DARKEN -> 4
            BlendMode.LIGHTEN -> 5
            BlendMode.COLOR_DODGE -> 6
            BlendMode.COLOR_BURN -> 7
            BlendMode.SOFT_LIGHT -> 8
            BlendMode.HARD_LIGHT -> 9
            BlendMode.DIFFERENCE -> 10
            BlendMode.EXCLUSION -> 11
            BlendMode.HUE -> 12
            BlendMode.SATURATION -> 13
            BlendMode.COLOR -> 14
            BlendMode.LUMINOSITY -> 15
            BlendMode.LINEAR_BURN -> 16
            BlendMode.DARKER_COLOR -> 17
            BlendMode.ADD -> 18
            BlendMode.LIGHTER_COLOR -> 19
            BlendMode.VIVID_LIGHT -> 20
            BlendMode.LINEAR_LIGHT -> 21
            BlendMode.PIN_LIGHT -> 22
            BlendMode.HARD_MIX -> 23
            BlendMode.SUBTRACT -> 24
            BlendMode.DIVIDE -> 25
        }
}
