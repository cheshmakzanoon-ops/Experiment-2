/**
 * Additional brush algorithms for ArtFlow
 * Implements advanced stroke smoothing and texture mapping
 */

#include "native_brush_engine.h"
#include <cmath>
#include <algorithm>
#include <GLES3/gl3.h>

namespace artflow {

// ============================================================================
// Texture Mapping Constants
// ============================================================================

constexpr int MAX_TEXTURE_SIZE = 2048;
constexpr int TEXTURE_UNIT_BRUSH = GL_TEXTURE0;
constexpr int TEXTURE_UNIT_CANVAS = GL_TEXTURE1;

// ============================================================================
// Texture Coordinate Generation
// ============================================================================

/**
 * Generate texture coordinates for a brush dab with rotation and scale
 * @param x Center X position
 * @param y Center Y position
 * @param size Brush size
 * @param rotation Rotation angle in degrees
 * @param textureScale Texture scale factor
 * @param outVertices Output vertex buffer (x, y, u, v)
 * @param outIndices Output index buffer
 * @return Number of vertices generated
 */
int generateTexturedDab(
    float x, float y, float size, float rotation, float textureScale,
    float* outVertices, unsigned short* outIndices) {
    
    const float halfSize = (size * 0.5f) / textureScale;
    const float cosRot = cosf(rotation * M_PI / 180.0f);
    const float sinRot = sinf(rotation * M_PI / 180.0f);
    
    // Quad corners in local space
    float corners[4][2] = {
        {-halfSize, -halfSize},  // Bottom-left
        { halfSize, -halfSize},  // Bottom-right
        { halfSize,  halfSize},  // Top-right
        {-halfSize,  halfSize}   // Top-left
    };
    
    // Transform and store vertices (x, y, u, v)
    for (int i = 0; i < 4; ++i) {
        float localX = corners[i][0];
        float localY = corners[i][1];
        
        // Apply rotation
        float rotatedX = localX * cosRot - localY * sinRot;
        float rotatedY = localX * sinRot + localY * cosRot;
        
        // Store transformed position
        outVertices[i * 4 + 0] = x + rotatedX;
        outVertices[i * 4 + 1] = y + rotatedY;
        
        // Store texture coordinates (0-1 range)
        outVertices[i * 4 + 2] = (i % 2) / 1.0f;  // u
        outVertices[i * 4 + 3] = (i / 2) / 1.0f;  // v
    }
    
    // Two triangles for the quad
    outIndices[0] = 0; outIndices[1] = 1; outIndices[2] = 2;
    outIndices[3] = 0; outIndices[4] = 2; outIndices[5] = 3;
    
    return 4;  // 4 vertices
}

// ============================================================================
// Stroke Smoothing Algorithms
// ============================================================================

/**
 * Kulter-based stroke smoothing algorithm
 * Interpolates between input points for smoother strokes
 * 
 * @param inputPoints Raw input points from stylus
 * @param outputPoints Smoothed output points
 * @param smoothingFactor Amount of smoothing (0.0 - 1.0)
 * @param minSpacing Minimum spacing between output points
 */
void smoothStrokeKulter(
    const std::vector<StrokePoint>& inputPoints,
    std::vector<StrokePoint>& outputPoints,
    float smoothingFactor,
    float minSpacing) {
    
    if (inputPoints.empty()) return;
    
    outputPoints.clear();
    outputPoints.push_back(inputPoints[0]);
    
    StrokePoint lastOutput = inputPoints[0];
    StrokePoint target = inputPoints[0];
    float progress = 0.0f;
    
    for (size_t i = 1; i < inputPoints.size(); ++i) {
        const StrokePoint& current = inputPoints[i];
        
        // Calculate distance to target
        float dx = current.x - lastOutput.x;
        float dy = current.y - lastOutput.y;
        float distance = sqrtf(dx * dx + dy * dy);
        
        if (distance < minSpacing) {
            continue;
        }
        
        // Update target
        target = current;
        
        // Interpolate with smoothing
        while (progress < 1.0f) {
            float interpFactor = smoothingFactor;
            
            StrokePoint smoothed;
            smoothed.x = lastOutput.x + (target.x - lastOutput.x) * interpFactor;
            smoothed.y = lastOutput.y + (target.y - lastOutput.y) * interpFactor;
            smoothed.pressure = lastOutput.pressure + (target.pressure - lastOutput.pressure) * interpFactor;
            smoothed.tiltX = lastOutput.tiltX + (target.tiltX - lastOutput.tiltX) * interpFactor;
            smoothed.tiltY = lastOutput.tiltY + (target.tiltY - lastOutput.tiltY) * interpFactor;
            smoothed.color = target.color;
            smoothed.timestamp = target.timestamp;
            
            // Check spacing
            float outDx = smoothed.x - outputPoints.back().x;
            float outDy = smoothed.y - outputPoints.back().y;
            float outDist = sqrtf(outDx * outDx + outDy * outDy);
            
            if (outDist >= minSpacing) {
                outputPoints.push_back(smoothed);
                lastOutput = smoothed;
                progress = 0.0f;
            } else {
                progress += 0.1f;
            }
        }
    }
    
    // Always add the final point
    if (outputPoints.back().x != inputPoints.back().x ||
        outputPoints.back().y != inputPoints.back().y) {
        outputPoints.push_back(inputPoints.back());
    }
}

/**
 * Simple linear interpolation smoothing
 * Faster but less smooth than Kulter algorithm
 */
void smoothStrokeLinear(
    const std::vector<StrokePoint>& inputPoints,
    std::vector<StrokePoint>& outputPoints,
    float smoothingFactor) {
    
    if (inputPoints.size() < 3) {
        outputPoints = inputPoints;
        return;
    }
    
    outputPoints.clear();
    outputPoints.push_back(inputPoints[0]);
    
    for (size_t i = 1; i < inputPoints.size() - 1; ++i) {
        const StrokePoint& prev = inputPoints[i - 1];
        const StrokePoint& curr = inputPoints[i];
        const StrokePoint& next = inputPoints[i + 1];
        
        StrokePoint smoothed;
        smoothed.x = curr.x * (1.0f - smoothingFactor) + 
                     (prev.x + next.x) * 0.5f * smoothingFactor;
        smoothed.y = curr.y * (1.0f - smoothingFactor) + 
                     (prev.y + next.y) * 0.5f * smoothingFactor;
        smoothed.pressure = curr.pressure;
        smoothed.tiltX = curr.tiltX;
        smoothed.tiltY = curr.tiltY;
        smoothed.color = curr.color;
        smoothed.timestamp = curr.timestamp;
        
        outputPoints.push_back(smoothed);
    }
    
    outputPoints.push_back(inputPoints.back());
}

// ============================================================================
// Texture Blending Functions
// ============================================================================

/**
 * Blend texture with solid color using multiply blend
 * @param textureData RGBA texture data
 * @param color Source color (ARGB)
 * @param textureWidth Texture width
 * @param textureHeight Texture height
 * @param outBuffer Output blended buffer
 */
void blendTextureMultiply(
    const unsigned char* textureData,
    uint32_t color,
    int textureWidth,
    int textureHeight,
    unsigned char* outBuffer) {
    
    int totalPixels = textureWidth * textureHeight;
    
    unsigned char r = (color >> 16) & 0xFF;
    unsigned char g = (color >> 8) & 0xFF;
    unsigned char b = color & 0xFF;
    unsigned char a = (color >> 24) & 0xFF;
    
    for (int i = 0; i < totalPixels; ++i) {
        int texIdx = i * 4;
        int outIdx = i * 4;
        
        unsigned char texR = textureData[texIdx + 0];
        unsigned char texG = textureData[texIdx + 1];
        unsigned char texB = textureData[texIdx + 2];
        unsigned char texA = textureData[texIdx + 3];
        
        // Multiply blend
        outBuffer[outIdx + 0] = (r * texR) / 255;
        outBuffer[outIdx + 1] = (g * texG) / 255;
        outBuffer[outIdx + 2] = (b * texB) / 255;
        outBuffer[outIdx + 3] = (a * texA) / 255;
    }
}

/**
 * Apply grayscale texture as alpha mask
 * @param textureData Grayscale texture data
 * @param baseAlpha Base alpha value
 * @param textureWidth Texture width
 * @param textureHeight Texture height
 * @param outAlpha Output alpha buffer
 */
void applyTextureAsAlphaMask(
    const unsigned char* textureData,
    float baseAlpha,
    int textureWidth,
    int textureHeight,
    float* outAlpha) {
    
    int totalPixels = textureWidth * textureHeight;
    
    for (int i = 0; i < totalPixels; ++i) {
        // Use red channel as grayscale value
        float texValue = textureData[i * 4 + 0] / 255.0f;
        outAlpha[i] = texValue * baseAlpha;
    }
}

// ============================================================================
// Wet Paint Mixing Simulation
// ============================================================================

/**
 * Simple wet-on-wet paint mixing
 * Blends new color with existing canvas color based on wetness
 * 
 * @param canvasColor Existing canvas color (RGBA)
 * @param brushColor New brush color (RGBA)
 * @param wetness Wetness factor (0.0 - 1.0)
 * @return Mixed color (RGBA)
 */
uint32_t mixWetPaint(
    uint32_t canvasColor,
    uint32_t brushColor,
    float wetness) {
    
    float r1 = ((canvasColor >> 24) & 0xFF) / 255.0f;
    float g1 = ((canvasColor >> 16) & 0xFF) / 255.0f;
    float b1 = ((canvasColor >> 8) & 0xFF) / 255.0f;
    float a1 = (canvasColor & 0xFF) / 255.0f;
    
    float r2 = ((brushColor >> 24) & 0xFF) / 255.0f;
    float g2 = ((brushColor >> 16) & 0xFF) / 255.0f;
    float b2 = ((brushColor >> 8) & 0xFF) / 255.0f;
    float a2 = (brushColor & 0xFF) / 255.0f;
    
    // Wet paint mixing formula
    float outA = a1 + a2 * wetness * (1.0f - a1);
    float outR = (r1 * a1 * (1.0f - wetness) + r2 * a2 * wetness) / (outA + 0.001f);
    float outG = (g1 * a1 * (1.0f - wetness) + g2 * a2 * wetness) / (outA + 0.001f);
    float outB = (b1 * a1 * (1.0f - wetness) + b2 * a2 * wetness) / (outA + 0.001f);
    
    return ((unsigned int)(outA * 255) << 24) |
           ((unsigned int)(outR * 255) << 16) |
           ((unsigned int)(outG * 255) << 8) |
           ((unsigned int)(outB * 255));
}

} // namespace artflow
