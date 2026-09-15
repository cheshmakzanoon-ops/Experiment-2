/**
 * ArtFlow Native Brush Engine - Shared declarations
 *
 * Data structures shared between the native brush engine translation units
 * (native_brush_engine.cpp, brush_algorithms.cpp, ...).
 */

#ifndef ARTFLOW_BRUSH_NATIVE_BRUSH_ENGINE_H
#define ARTFLOW_BRUSH_NATIVE_BRUSH_ENGINE_H

#include <cstdint>
#include <vector>

namespace artflow {

/**
 * Represents a single point in a stroke with pressure and tilt data
 */
struct StrokePoint {
    float x;
    float y;
    float pressure;
    float tiltX;
    float tiltY;
    float azimuth;
    uint32_t color;  // ARGB format
    int64_t timestamp;

    StrokePoint() : x(0), y(0), pressure(1.0f), tiltX(0), tiltY(0),
                    azimuth(0), color(0xFF000000), timestamp(0) {}

    StrokePoint(float _x, float _y, float _pressure, uint32_t _color)
        : x(_x), y(_y), pressure(_pressure), tiltX(0), tiltY(0),
          azimuth(0), color(_color), timestamp(0) {}
};

/**
 * Brush parameters for controlling stroke rendering
 */
struct BrushParams {
    float size;              // Brush size in pixels
    float opacity;           // Opacity 0.0 - 1.0
    float spacing;           // Spacing between dabs
    float scatter;           // Scatter amount
    int count;               // Number of dabs per interval
    float rotation;          // Brush rotation in degrees
    float taperStart;        // Taper at stroke start
    float taperEnd;          // Taper at stroke end
    float pressureToSize;    // Pressure affects size
    float pressureToOpacity; // Pressure affects opacity
    float smoothing;         // Stroke smoothing amount
    float flow;              // Paint flow rate
    bool hasTexture;         // Whether brush uses texture

    BrushParams() : size(20.0f), opacity(1.0f), spacing(0.1f), scatter(0.0f),
                    count(1), rotation(0), taperStart(0), taperEnd(0),
                    pressureToSize(0.5f), pressureToOpacity(0.3f),
                    smoothing(0.5f), flow(1.0f), hasTexture(false) {}
};

} // namespace artflow

#endif // ARTFLOW_BRUSH_NATIVE_BRUSH_ENGINE_H
