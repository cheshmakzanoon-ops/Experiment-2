#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace {

inline float clamp01(float v) {
    return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
}

inline int clamp255(int v) {
    return v < 0 ? 0 : (v > 255 ? 255 : v);
}

}  // namespace

extern "C" {

/**
 * Native C++20 accelerated 4-neighbor + gravity watercolor diffusion pass on a single ARGB buffer.
 * Writes diffused pigment & wetness back into the supplied arrays.
 */
JNIEXPORT jboolean JNICALL
Java_com_artflow_studio_core_render_PhysicalPaintNative_nativeDiffuseRegion(
    JNIEnv* env,
    jobject /* this */,
    jintArray pixelsArr,
    jfloatArray wetnessArr,
    jfloatArray pigmentArr,
    jint width,
    jint height,
    jfloat gravityX,
    jfloat gravityY,
    jfloat diffusionRate,
    jfloat evaporationRate
) {
    if (pixelsArr == nullptr || wetnessArr == nullptr || pigmentArr == nullptr) return JNI_FALSE;
    const int count = width * height;
    if (count <= 0) return JNI_FALSE;

    jint* pixels = env->GetIntArrayElements(pixelsArr, nullptr);
    jfloat* wetness = env->GetFloatArrayElements(wetnessArr, nullptr);
    jfloat* pigment = env->GetFloatArrayElements(pigmentArr, nullptr);
    if (pixels == nullptr || wetness == nullptr || pigment == nullptr) {
        if (pixels != nullptr) env->ReleaseIntArrayElements(pixelsArr, pixels, JNI_ABORT);
        if (wetness != nullptr) env->ReleaseFloatArrayElements(wetnessArr, wetness, JNI_ABORT);
        if (pigment != nullptr) env->ReleaseFloatArrayElements(pigmentArr, pigment, JNI_ABORT);
        return JNI_FALSE;
    }

    const float flow = std::clamp(diffusionRate, 0.0f, 0.45f);
    const float evap = std::clamp(evaporationRate, 0.0f, 0.25f);
    const float gx = std::clamp(gravityX, -1.0f, 1.0f);
    const float gy = std::clamp(gravityY, -1.0f, 1.0f);

    bool anyWet = false;
    for (int y = 1; y < height - 1; ++y) {
        const int row = y * width;
        for (int x = 1; x < width - 1; ++x) {
            const int idx = row + x;
            const float w = wetness[idx];
            if (w <= 0.005f) continue;

            // Directional weights biased by accelerometer tilt gravity.
            const float wLeft = flow * (0.25f + std::max(0.0f, gx) * 0.20f);
            const float wRight = flow * (0.25f + std::max(0.0f, -gx) * 0.20f);
            const float wUp = flow * (0.25f + std::max(0.0f, gy) * 0.20f);
            const float wDown = flow * (0.25f + std::max(0.0f, -gy) * 0.20f);
            const float sumW = wLeft + wRight + wUp + wDown;
            const float keep = 1.0f - sumW;

            const int cSelf = pixels[idx];
            const int cL = pixels[idx - 1];
            const int cR = pixels[idx + 1];
            const int cU = pixels[idx - width];
            const int cD = pixels[idx + width];

            auto blendChannel = [&](int shift) -> int {
                const float s = static_cast<float>((cSelf >> shift) & 0xFF);
                const float l = static_cast<float>((cL >> shift) & 0xFF);
                const float r = static_cast<float>((cR >> shift) & 0xFF);
                const float u = static_cast<float>((cU >> shift) & 0xFF);
                const float d = static_cast<float>((cD >> shift) & 0xFF);
                return clamp255(static_cast<int>(std::lround(s * keep + l * wLeft + r * wRight + u * wUp + d * wDown)));
            };

            const int a = blendChannel(24);
            const int r = blendChannel(16);
            const int g = blendChannel(8);
            const int b = blendChannel(0);
            pixels[idx] = (a << 24) | (r << 16) | (g << 8) | b;

            const float nextW = clamp01(w * (1.0f - evap));
            wetness[idx] = nextW;
            pigment[idx] = clamp01(pigment[idx] * (1.0f - evap * 0.5f));
            if (nextW > 0.01f) anyWet = true;
        }
    }

    env->ReleaseIntArrayElements(pixelsArr, pixels, 0);
    env->ReleaseFloatArrayElements(wetnessArr, wetness, 0);
    env->ReleaseFloatArrayElements(pigmentArr, pigment, 0);
    return anyWet ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
