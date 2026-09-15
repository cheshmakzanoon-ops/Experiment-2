/**
 * ArtFlow Native Brush Engine
 * High-performance brush rendering algorithms using C++ and OpenGL ES
 * 
 * This native module provides:
 * - Optimized stroke rendering with pressure sensitivity
 * - Advanced brush texture blending
 * - Multi-threaded paint mixing simulation
 * - GPU-accelerated filter operations
 */

#include <jni.h>
#include <android/log.h>
#include <GLES3/gl3.h>
#include <cmath>
#include <vector>
#include <memory>

#include "native_brush_engine.h"

#define LOG_TAG "ArtFlowNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace artflow {

// ============================================================================
// Data Structures
//
// StrokePoint and BrushParams are declared in native_brush_engine.h so that
// they can be shared with the other native translation units (for example
// brush_algorithms.cpp) without duplicating their definitions.
// ============================================================================

// ============================================================================
// Native Brush Engine Class
// ============================================================================

class NativeBrushEngine {
public:
    NativeBrushEngine();
    ~NativeBrushEngine();
    
    /**
     * Initialize the brush engine with OpenGL context
     */
    bool initialize();
    
    /**
     * Render a stroke to the canvas texture
     */
    void renderStroke(const std::vector<StrokePoint>& points, 
                      const BrushParams& params,
                      GLuint targetTextureId);
    
    /**
     * Calculate interpolated points between input samples
     */
    std::vector<StrokePoint> interpolatePoints(
        const std::vector<StrokePoint>& inputPoints,
        float spacing);
    
    /**
     * Apply pressure-based size modulation
     */
    float calculateSizeFromPressure(float baseSize, float pressure, 
                                    float pressureToSize);
    
    /**
     * Apply pressure-based opacity modulation
     */
    float calculateOpacityFromPressure(float baseOpacity, float pressure,
                                       float pressureToOpacity);
    
    /**
     * Clean up resources
     */
    void dispose();

private:
    bool initialized;
    GLuint shaderProgram;
    GLuint vertexBuffer;
    GLuint indexBuffer;
    
    /**
     * Create and compile shaders
     */
    GLuint createShaderProgram();
    
    /**
     * Generate brush dab geometry
     */
    void generateDabGeometry(float x, float y, float size, float rotation,
                            float opacity, uint32_t color);
};

// ============================================================================
// Implementation
// ============================================================================

NativeBrushEngine::NativeBrushEngine() 
    : initialized(false), shaderProgram(0), vertexBuffer(0), indexBuffer(0) {
    LOGI("NativeBrushEngine created");
}

NativeBrushEngine::~NativeBrushEngine() {
    dispose();
    LOGI("NativeBrushEngine destroyed");
}

bool NativeBrushEngine::initialize() {
    if (initialized) {
        return true;
    }
    
    shaderProgram = createShaderProgram();
    if (shaderProgram == 0) {
        LOGE("Failed to create shader program");
        return false;
    }
    
    // Generate buffers
    glGenBuffers(1, &vertexBuffer);
    glGenBuffers(1, &indexBuffer);
    
    initialized = true;
    LOGI("NativeBrushEngine initialized successfully");
    return true;
}

GLuint NativeBrushEngine::createShaderProgram() {
    // Vertex shader for brush dabs
    const char* vertexShaderSource = R"(
        #version 300 es
        layout(location = 0) in vec2 a_position;
        layout(location = 1) in vec4 a_color;
        
        uniform mat4 u_matrix;
        out vec4 v_color;
        
        void main() {
            gl_Position = u_matrix * vec4(a_position, 0.0, 1.0);
            v_color = a_color;
        }
    )";
    
    // Fragment shader for brush rendering with alpha blending
    const char* fragmentShaderSource = R"(
        #version 300 es
        precision mediump float;
        in vec4 v_color;
        out vec4 fragColor;
        
        void main() {
            fragColor = v_color;
        }
    )";
    
    // Compile vertex shader
    GLuint vertexShader = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vertexShader, 1, &vertexShaderSource, nullptr);
    glCompileShader(vertexShader);
    
    // Check compilation status
    GLint success;
    glGetShaderiv(vertexShader, GL_COMPILE_STATUS, &success);
    if (!success) {
        char infoLog[512];
        glGetShaderInfoLog(vertexShader, 512, nullptr, infoLog);
        LOGE("Vertex shader compilation failed: %s", infoLog);
        glDeleteShader(vertexShader);
        return 0;
    }
    
    // Compile fragment shader
    GLuint fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fragmentShader, 1, &fragmentShaderSource, nullptr);
    glCompileShader(fragmentShader);
    
    glGetShaderiv(fragmentShader, GL_COMPILE_STATUS, &success);
    if (!success) {
        char infoLog[512];
        glGetShaderInfoLog(fragmentShader, 512, nullptr, infoLog);
        LOGE("Fragment shader compilation failed: %s", infoLog);
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        return 0;
    }
    
    // Link program
    GLuint program = glCreateProgram();
    glAttachShader(program, vertexShader);
    glAttachShader(program, fragmentShader);
    glLinkProgram(program);
    
    glGetProgramiv(program, GL_LINK_STATUS, &success);
    if (!success) {
        char infoLog[512];
        glGetProgramInfoLog(program, 512, nullptr, infoLog);
        LOGE("Shader program linking failed: %s", infoLog);
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        glDeleteProgram(program);
        return 0;
    }
    
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);
    
    return program;
}

std::vector<StrokePoint> NativeBrushEngine::interpolatePoints(
    const std::vector<StrokePoint>& inputPoints,
    float spacing) {
    
    std::vector<StrokePoint> result;
    if (inputPoints.empty()) {
        return result;
    }
    
    result.push_back(inputPoints[0]);
    
    for (size_t i = 1; i < inputPoints.size(); ++i) {
        const StrokePoint& prev = inputPoints[i - 1];
        const StrokePoint& curr = inputPoints[i];
        
        float dx = curr.x - prev.x;
        float dy = curr.y - prev.y;
        float distance = std::sqrt(dx * dx + dy * dy);
        
        if (distance <= spacing) {
            result.push_back(curr);
            continue;
        }
        
        // Interpolate intermediate points
        int numPoints = static_cast<int>(distance / spacing);
        for (int j = 1; j <= numPoints; ++j) {
            float t = static_cast<float>(j) / (numPoints + 1);
            StrokePoint interpolated;
            interpolated.x = prev.x + dx * t;
            interpolated.y = prev.y + dy * t;
            interpolated.pressure = prev.pressure + (curr.pressure - prev.pressure) * t;
            interpolated.color = prev.color; // Simplified - could interpolate color too
            result.push_back(interpolated);
        }
    }
    
    return result;
}

float NativeBrushEngine::calculateSizeFromPressure(float baseSize, float pressure,
                                                    float pressureToSize) {
    // Linear interpolation between minimum and maximum size based on pressure
    float minSize = baseSize * (1.0f - pressureToSize);
    float maxSize = baseSize * (1.0f + pressureToSize);
    return minSize + (maxSize - minSize) * pressure;
}

float NativeBrushEngine::calculateOpacityFromPressure(float baseOpacity, 
                                                       float pressure,
                                                       float pressureToOpacity) {
    // Modulate opacity based on pressure
    float minOpacity = baseOpacity * (1.0f - pressureToOpacity);
    float maxOpacity = baseOpacity;
    return minOpacity + (maxOpacity - minOpacity) * pressure;
}

void NativeBrushEngine::renderStroke(const std::vector<StrokePoint>& points,
                                      const BrushParams& params,
                                      GLuint targetTextureId) {
    if (!initialized || points.empty()) {
        return;
    }
    
    // Bind target texture
    glBindTexture(GL_TEXTURE_2D, targetTextureId);
    
    // Use shader program
    glUseProgram(shaderProgram);
    
    // Enable blending for smooth brush strokes
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    
    // Interpolate points for smooth stroke
    std::vector<StrokePoint> interpolated = interpolatePoints(points, params.spacing);
    
    // Render each dab
    for (const auto& point : interpolated) {
        float size = calculateSizeFromPressure(params.size, point.pressure, 
                                               params.pressureToSize);
        float opacity = calculateOpacityFromPressure(params.opacity, point.pressure,
                                                     params.pressureToOpacity);
        
        generateDabGeometry(point.x, point.y, size, params.rotation, 
                           opacity, point.color);
    }
    
    // Disable blending
    glDisable(GL_BLEND);
}

void NativeBrushEngine::generateDabGeometry(float x, float y, float size, 
                                            float rotation, float opacity,
                                            uint32_t color) {
    // Extract RGBA components from color (ARGB format)
    float a = ((color >> 24) & 0xFF) / 255.0f * opacity;
    float r = ((color >> 16) & 0xFF) / 255.0f;
    float g = ((color >> 8) & 0xFF) / 255.0f;
    float b = (color & 0xFF) / 255.0f;
    
    // Generate circle dab vertices (simplified - would use textured quad in production)
    const int segments = 16;
    std::vector<float> vertices;
    
    for (int i = 0; i < segments; ++i) {
        float angle1 = 2.0f * M_PI * i / segments;
        float angle2 = 2.0f * M_PI * (i + 1) / segments;
        
        float x1 = x + size * 0.5f * std::cos(angle1);
        float y1 = y + size * 0.5f * std::sin(angle1);
        float x2 = x + size * 0.5f * std::cos(angle2);
        float y2 = y + size * 0.5f * std::sin(angle2);
        
        // Triangle fan from center
        vertices.insert(vertices.end(), {
            x, y, r, g, b, a,
            x1, y1, r, g, b, a,
            x2, y2, r, g, b, a
        });
    }
    
    // Upload to GPU and draw (simplified - would use VBO/VAO in production)
    glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
    glBufferData(GL_ARRAY_BUFFER, vertices.size() * sizeof(float), 
                 vertices.data(), GL_DYNAMIC_DRAW);
    
    // Draw triangles
    glDrawArrays(GL_TRIANGLES, 0, segments * 3);
}

void NativeBrushEngine::dispose() {
    if (shaderProgram != 0) {
        glDeleteProgram(shaderProgram);
        shaderProgram = 0;
    }
    
    if (vertexBuffer != 0) {
        glDeleteBuffers(1, &vertexBuffer);
        vertexBuffer = 0;
    }
    
    if (indexBuffer != 0) {
        glDeleteBuffers(1, &indexBuffer);
        indexBuffer = 0;
    }
    
    initialized = false;
}

// Global engine instance
static std::unique_ptr<NativeBrushEngine> g_engine;

} // namespace artflow

// ============================================================================
// JNI Interface
// ============================================================================

extern "C" {

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    
    // Initialize global engine instance
    artflow::g_engine = std::make_unique<artflow::NativeBrushEngine>();
    
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
JNI_OnUnload(JavaVM* vm, void* reserved) {
    artflow::g_engine.reset();
}

/**
 * Initialize the native brush engine
 */
JNIEXPORT jboolean JNICALL
Java_com_artflow_studio_data_renderer_native_NativeBrushEngine_nativeInitialize(
    JNIEnv* env, jobject thiz) {
    
    if (!artflow::g_engine) {
        artflow::g_engine = std::make_unique<artflow::NativeBrushEngine>();
    }
    
    return artflow::g_engine->initialize() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Render a stroke with given parameters
 */
JNIEXPORT void JNICALL
Java_com_artflow_studio_data_renderer_native_NativeBrushEngine_nativeRenderStroke(
    JNIEnv* env, jobject thiz,
    jfloatArray points, jint pointCount,
    jfloat size, jfloat opacity, jfloat spacing,
    jfloat pressureToSize, jfloat pressureToOpacity,
    jint textureId) {
    
    if (!artflow::g_engine) return;
    
    // Convert Java float array to native StrokePoint array
    jfloat* pointData = env->GetFloatArrayElements(points, nullptr);
    std::vector<artflow::StrokePoint> strokePoints;
    
    for (int i = 0; i < pointCount; ++i) {
        artflow::StrokePoint point;
        point.x = pointData[i * 7 + 0];
        point.y = pointData[i * 7 + 1];
        point.pressure = pointData[i * 7 + 2];
        point.tiltX = pointData[i * 7 + 3];
        point.tiltY = pointData[i * 7 + 4];
        point.color = static_cast<uint32_t>(pointData[i * 7 + 5]);
        point.timestamp = static_cast<int64_t>(pointData[i * 7 + 6]);
        strokePoints.push_back(point);
    }
    
    env->ReleaseFloatArrayElements(points, pointData, JNI_ABORT);
    
    // Set brush parameters
    artflow::BrushParams params;
    params.size = size;
    params.opacity = opacity;
    params.spacing = spacing;
    params.pressureToSize = pressureToSize;
    params.pressureToOpacity = pressureToOpacity;
    
    // Render stroke
    artflow::g_engine->renderStroke(strokePoints, params, 
                                    static_cast<GLuint>(textureId));
}

/**
 * Interpolate stroke points for smoother rendering
 */
JNIEXPORT jfloatArray JNICALL
Java_com_artflow_studio_data_renderer_native_NativeBrushEngine_nativeInterpolatePoints(
    JNIEnv* env, jobject thiz,
    jfloatArray points, jint pointCount, jfloat spacing) {
    
    if (!artflow::g_engine || pointCount == 0) {
        return nullptr;
    }
    
    // Convert input points
    jfloat* pointData = env->GetFloatArrayElements(points, nullptr);
    std::vector<artflow::StrokePoint> inputPoints;
    
    for (int i = 0; i < pointCount; ++i) {
        artflow::StrokePoint point;
        point.x = pointData[i * 7 + 0];
        point.y = pointData[i * 7 + 1];
        point.pressure = pointData[i * 7 + 2];
        inputPoints.push_back(point);
    }
    
    env->ReleaseFloatArrayElements(points, pointData, JNI_ABORT);
    
    // Interpolate
    std::vector<artflow::StrokePoint> result = 
        artflow::g_engine->interpolatePoints(inputPoints, spacing);
    
    // Convert back to Java float array
    jfloatArray resultArray = env->NewFloatArray(result.size() * 7);
    std::vector<jfloat> resultData;
    
    for (const auto& point : result) {
        resultData.push_back(point.x);
        resultData.push_back(point.y);
        resultData.push_back(point.pressure);
        resultData.push_back(point.tiltX);
        resultData.push_back(point.tiltY);
        resultData.push_back(static_cast<jfloat>(point.color));
        resultData.push_back(static_cast<jfloat>(point.timestamp));
    }
    
    env->SetFloatArrayRegion(resultArray, 0, result.size() * 7, resultData.data());
    return resultArray;
}

/**
 * Calculate brush size from pressure
 */
JNIEXPORT jfloat JNICALL
Java_com_artflow_studio_data_renderer_native_NativeBrushEngine_nativeCalculateSize(
    JNIEnv* env, jobject thiz,
    jfloat baseSize, jfloat pressure, jfloat pressureToSize) {
    
    if (!artflow::g_engine) return baseSize;
    
    return artflow::g_engine->calculateSizeFromPressure(
        baseSize, pressure, pressureToSize);
}

/**
 * Calculate brush opacity from pressure
 */
JNIEXPORT jfloat JNICALL
Java_com_artflow_studio_data_renderer_native_NativeBrushEngine_nativeCalculateOpacity(
    JNIEnv* env, jobject thiz,
    jfloat baseOpacity, jfloat pressure, jfloat pressureToOpacity) {
    
    if (!artflow::g_engine) return baseOpacity;
    
    return artflow::g_engine->calculateOpacityFromPressure(
        baseOpacity, pressure, pressureToOpacity);
}

/**
 * Dispose native resources
 */
JNIEXPORT void JNICALL
Java_com_artflow_studio_data_renderer_native_NativeBrushEngine_nativeDispose(
    JNIEnv* env, jobject thiz) {
    
    if (artflow::g_engine) {
        artflow::g_engine->dispose();
    }
}

} // extern "C"
