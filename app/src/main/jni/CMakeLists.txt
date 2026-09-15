# ArtFlow Studio - Native Brush Engine (C++)
# Optimized for high-performance drawing operations

cmake_minimum_required(VERSION 3.18.1)
project("artflow-native")

# Enable C++17
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add native brush library
add_library(
    artflow-brush
    SHARED
    brush/native_brush_engine.cpp
    brush/brush_algorithms.cpp
    renderer/gl_renderer_native.cpp
    filters/blur_filter.cpp
    filters/sharpen_filter.cpp
)

# Link against Android logging and OpenGL ES libraries
target_link_libraries(
    artflow-brush
    android
    log
    EGL
    GLESv3
    m
)

# Optimization flags for release builds
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_compile_options(artflow-brush PRIVATE -O3 -ffast-math)
endif()

# Debug symbols for debug builds
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(artflow-brush PRIVATE -g -O0)
endif()

# Include directories
target_include_directories(artflow-brush PRIVATE ${CMAKE_SOURCE_DIR})
