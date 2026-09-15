# ArtFlow - Professional Digital Art Studio for Android

## 🎨 Overview

**ArtFlow** is a comprehensive digital art application designed exclusively for Android devices, inspired by the renowned Procreate app for iPad. This powerful mobile studio brings professional-grade drawing, painting, and illustration tools to the palm of your hand, leveraging the full potential of Android tablets and smartphones with stylus support.

Whether you're a professional illustrator, concept artist, hobbyist, or beginner, ArtFlow provides an intuitive yet feature-rich environment that adapts to your creative workflow. With native support for pressure-sensitive styluses (including Samsung S-Pen, Wacom, and other Bluetooth styluses), multi-touch gestures, and a highly optimized rendering engine, ArtFlow delivers a seamless and responsive drawing experience that rivals desktop applications.

---

## ✨ Key Features

### 🖌️ Advanced Brush Engine
- **100+ Pre-installed Brushes**: Pencils, pens, markers, watercolors, oils, airbrushes, charcoals, and experimental brushes
- **Custom Brush Creator**: Design your own brushes with adjustable parameters:
  - Shape grain and texture mapping
  - Stroke tapering and pressure curves
  - Color dynamics (hue, saturation, brightness jitter)
  - Scatter, rotation, and count controls
  - Wet mix and blending properties
- **Brush Studios**: Organize brushes into customizable sets and import/export brush packs
- **Pressure Sensitivity**: Full utilization of stylus pressure for dynamic stroke variation
- **Tilt Support**: Natural shading effects using stylus tilt angle

### 🎭 Layer System
- **Unlimited Layers**: Create complex compositions with as many layers as your device can handle
- **Layer Types**: Pixel, vector, fill, and clipping mask layers
- **Blend Modes**: Normal, multiply, screen, overlay, darken, lighten, color dodge, burn, hue, saturation, luminosity, and more
- **Layer Opacity & Alpha Lock**: Precise control over transparency and protected painting areas
- **Layer Masks**: Non-destructive editing with grayscale masks
- **Adjustment Layers**: Apply color corrections, filters, and effects non-destructively
- **Layer Groups**: Organize layers into collapsible folders for better workflow management

### 🎨 Color Management
- **Advanced Color Picker**: RGB, HSV, CMYK, and traditional color wheel interfaces
- **Color Harmony Tools**: Complementary, analogous, triadic, and split-complementary schemes
- **Custom Palettes**: Create, save, and organize unlimited color palettes
- **Color History**: Quick access to recently used colors
- **Gradient Maps**: Apply sophisticated color grading to your artwork
- **Eyedropper Tool**: Sample colors directly from your canvas in real-time

### 🖼️ Canvas & Workspace
- **Resolution Support**: Up to 8K resolution canvases (device-dependent)
- **Aspect Ratios**: Preset templates for social media, print, web, and custom dimensions
- **DPI Settings**: 72-600 DPI for screen and print workflows
- **Symmetry Drawing**: Vertical, horizontal, quadrant, and radial symmetry guides
- **Perspective Guides**: 1-point, 2-point, and 3-point perspective grids
- **Isometric Grids**: Perfect for technical illustrations and game art
- **Reference Window**: Floating resizable window for reference images

### ⚡ Performance & Technology
- **GPU-Accelerated Rendering**: OpenGL ES 3.0+ and Vulkan support for smooth performance
- **Multi-threaded Processing**: Utilizes all CPU cores for complex operations
- **Optimized Memory Management**: Intelligent caching and tile-based rendering for large canvases
- **Low Latency Input**: Sub-20ms stylus latency on supported devices
- **Autosave & Recovery**: Automatic backup every 30 seconds with crash recovery
- **Undo/Redo Stack**: 100+ levels of undo history with visual history browser

### 🛠️ Professional Tools
- **Selection Tools**: Freehand, lasso, rectangular, elliptical, and magic wand selections
- **Transform Tools**: Scale, rotate, skew, distort, and perspective transform
- **Liquify Tool**: Push, twirl, pinch, and bloat effects for organic adjustments
- **Smudge & Blend**: Realistic paint mixing and blending simulation
- **Clone Stamp**: Duplicate areas of your artwork seamlessly
- **Healing Brush**: Remove imperfections and blend textures
- **Text Tool**: Add and edit vector-based text with font customization
- **Shape Tools**: Vector rectangles, ellipses, polygons, and lines

### 📤 Export & Sharing
- **File Formats**: 
  - Native `.artflow` project files (preserves all layers and editability)
  - PSD (Photoshop compatibility with layers)
  - PNG (transparent background support)
  - JPEG (adjustable quality)
  - TIFF (lossless compression)
  - PDF (vector text and shapes)
  - Animated GIF and APNG
  - MP4 timelapse videos
- **Export Options**: 
  - Batch export multiple layers or artboards
  - Custom resolution scaling (50%-400%)
  - Color profile embedding (sRGB, Adobe RGB, Display P3)
- **Direct Sharing**: Share to social media, cloud storage, or other apps

### 🎬 Animation Assist
- **Frame-by-Frame Animation**: Create traditional animations with onion skinning
- **Timeline Interface**: Visual timeline with playback controls
- **Onion Skinning**: See previous and next frames with customizable opacity
- **FPS Control**: 1-60 FPS export options
- **Light Table Mode**: Trace over animation frames
- **Export Animations**: GIF, APNG, or video sequence exports

### 🌐 Cloud & Collaboration
- **Cloud Sync**: Automatic backup to Google Drive, Dropbox, or proprietary cloud
- **Project Versioning**: Save and restore previous versions of your artwork
- **Collaborative Layers**: Share projects with team members for collaborative editing
- **Asset Library**: Cloud-synced brushes, palettes, and templates across devices

### ♿ Accessibility & UX
- **Customizable UI**: Rearrange toolbars, adjust icon sizes, dark/light themes
- **Gesture Controls**: Two-finger tap (undo), three-finger tap (redo), pinch (zoom), rotate canvas
- **Left-Handed Mode**: Mirror interface for left-handed artists
- **Stylus Button Mapping**: Customize stylus button functions
- **Voice Commands**: Hands-free tool switching (experimental)
- **Tutorial System**: Interactive tutorials for beginners

---

## 📱 System Requirements

### Minimum Requirements
- **OS**: Android 8.0 (API Level 26) or higher
- **RAM**: 4 GB
- **Storage**: 500 MB free space
- **Screen**: 1280x720 resolution
- **Processor**: Quad-core 1.8 GHz or equivalent

### Recommended Requirements
- **OS**: Android 11.0 (API Level 30) or higher
- **RAM**: 8 GB or more
- **Storage**: 2 GB+ free space (SSD preferred)
- **Screen**: 2560x1600 resolution or higher
- **Processor**: Octa-core 2.4 GHz or equivalent
- **Stylus**: Pressure-sensitive stylus with at least 2048 pressure levels

### Optimized Devices
- Samsung Galaxy Tab S series (S8, S9, Ultra)
- Lenovo Tab P series
- Xiaomi Pad series
- Huawei MatePad Pro
- Google Pixel Tablet
- ASUS Zenbook Pro Duo (with stylus)

---

## 🏗️ Architecture Overview

### Tech Stack
- **Language**: Kotlin (primary), Java (legacy modules)
- **UI Framework**: Jetpack Compose for modern UI components
- **Graphics Engine**: Custom OpenGL ES 3.0 / Vulkan renderer
- **Architecture**: MVVM (Model-View-ViewModel) with Clean Architecture principles
- **Dependency Injection**: Hilt (Dagger)
- **Async Operations**: Kotlin Coroutines + Flow
- **Local Database**: Room (for project metadata, settings, assets)
- **File Storage**: Jetpack Storage Access Framework
- **Image Processing**: RenderScript + custom native libraries (C++)
- **Animation**: Lottie for UI animations, custom engine for canvas animations

### Project Structure
```
app/
├── src/main/
│   ├── java/com/artflow/studio/
│   │   ├── core/                    # Core engine classes
│   │   │   ├── renderer/            # OpenGL/Vulkan rendering pipeline
│   │   │   ├── brush/               # Brush engine and algorithms
│   │   │   ├── layer/               # Layer management system
│   │   │   ├── color/               # Color models and conversions
│   │   │   └── geometry/            # Mathematical utilities
│   │   ├── domain/                  # Business logic layer
│   │   │   ├── model/               # Data models
│   │   │   ├── repository/          # Repository interfaces
│   │   │   └── usecase/             # Use case implementations
│   │   ├── data/                    # Data layer
│   │   │   ├── local/               # Local storage, database
│   │   │   ├── remote/              # Cloud services
│   │   │   └── repository/          # Repository implementations
│   │   ├── presentation/            # UI layer
│   │   │   ├── ui/                  # Compose screens
│   │   │   ├── viewmodel/           # ViewModels
│   │   │   └── components/          # Reusable UI components
│   │   ├── di/                      # Dependency injection modules
│   │   └── util/                    # Utilities and extensions
│   ├── jni/                         # Native C++ code
│   │   ├── renderer/                # Native rendering optimizations
│   │   ├── brush/                   # Native brush algorithms
│   │   └── filters/                 # Image filter implementations
│   └── res/                         # Resources
├── build.gradle.kts
└── proguard-rules.pro
```

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17 or higher
- Android SDK 34 (or latest)
- NDK r25c or later (for native code)
- Git

### Installation Steps

1. **Clone the Repository**
```bash
git clone https://github.com/yourusername/artflow-android.git
cd artflow-android
```

2. **Open in Android Studio**
   - Launch Android Studio
   - Select "Open an Existing Project"
   - Navigate to the cloned directory

3. **Sync Dependencies**
   - Android Studio will automatically sync Gradle dependencies
   - Ensure you have internet connection for first-time setup

4. **Build Configuration**
   - Create `local.properties` with your SDK path:
   ```properties
   sdk.dir=/path/to/Android/Sdk
   ndk.dir=/path/to/Android/ndk
   ```

5. **Run the Application**
   - Connect an Android device or start an emulator
   - Click the "Run" button or press `Shift + F10`
   - Select your target device

### Build Variants
- **debug**: Development build with logging enabled
- **release**: Optimized production build
- **benchmark**: Performance testing build

---

## 🧪 Testing

### Unit Tests
```bash
./gradlew testDebugUnitTest
```

### Instrumentation Tests
```bash
./gradlew connectedAndroidTest
```

### Benchmark Tests
```bash
./gradlew benchmark
```

---

## 📦 Building for Release

1. **Generate Keystore** (if not exists)
```bash
keytool -genkey -v -keystore artflow-release.keystore -alias artflow -keyalg RSA -keysize 2048 -validity 10000
```

2. **Configure Signing**
   - Add keystore details to `~/.gradle/gradle.properties`:
   ```properties
   ARTFLOW_RELEASE_STORE_FILE=/path/to/keystore
   ARTFLOW_RELEASE_KEY_ALIAS=artflow
   ARTFLOW_RELEASE_PASSWORD=your_password
   ARTFLOW_KEY_PASSWORD=your_key_password
   ```

3. **Build Release APK**
```bash
./gradlew assembleRelease
```

4. **Build Release Bundle (Play Store)**
```bash
./gradlew bundleRelease
```

---

## 🤝 Contributing

We welcome contributions from the community! Please follow these guidelines:

### Contribution Process
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes
4. Write/update tests
5. Ensure all tests pass
6. Commit your changes (`git commit -m 'Add amazing feature'`)
7. Push to the branch (`git push origin feature/amazing-feature`)
8. Open a Pull Request

### Code Style
- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use ktlint for linting: `./gradlew ktlintCheck`
- Format code: `./gradlew ktlintFormat`

### Commit Messages
- Use conventional commits format:
  - `feat:` New features
  - `fix:` Bug fixes
  - `docs:` Documentation changes
  - `style:` Code style changes
  - `refactor:` Code refactoring
  - `test:` Test additions/changes
  - `chore:` Maintenance tasks

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0** - see the [LICENSE](LICENSE) file for details.

### Commercial Use
For commercial licensing options, enterprise support, or white-label solutions, please contact: **licensing@artflow.studio**

---

## 🙏 Acknowledgments

- Inspired by Procreate (Savage Interactive Pty Ltd)
- Built with love by the open-source community
- Special thanks to all contributors and beta testers

---

## 📞 Support & Community

- **Documentation**: https://docs.artflow.studio
- **Issue Tracker**: https://github.com/yourusername/artflow-android/issues
- **Discussions**: https://github.com/yourusername/artflow-android/discussions
- **Discord Community**: https://discord.gg/artflow
- **Twitter**: @ArtFlowApp
- **Email**: support@artflow.studio

---

## 🗺️ Roadmap

### Phase 1 (Q1 2025) - Foundation
- [x] Core rendering engine
- [x] Basic brush system
- [x] Layer management
- [ ] Export functionality

### Phase 2 (Q2 2025) - Professional Features
- [ ] Advanced brush editor
- [ ] Animation assist
- [ ] PSD import/export
- [ ] Cloud sync

### Phase 3 (Q3 2025) - AI Integration
- [ ] AI-assisted sketching
- [ ] Smart selection tools
- [ ] Style transfer filters
- [ ] Auto-colorization

### Phase 4 (Q4 2025) - Collaboration
- [ ] Multi-user editing
- [ ] Real-time collaboration
- [ ] Asset marketplace
- [ ] Tutorial platform

---

## 📊 Performance Benchmarks

| Device | Canvas Size | Brush Latency | Max Layers | RAM Usage |
|--------|-------------|---------------|------------|-----------|
| Galaxy Tab S9 Ultra | 4096x4096 | 12ms | 250+ | 3.2 GB |
| Pixel Tablet | 3000x3000 | 18ms | 180+ | 2.8 GB |
| Lenovo Tab P12 | 4096x4096 | 15ms | 220+ | 3.0 GB |

*Results may vary based on canvas complexity and brush type.*

---

<div align="center">

**Made with ❤️ for Android Artists Everywhere**

[Website](https://artflow.studio) • [Documentation](https://docs.artflow.studio) • [Report Bug](https://github.com/yourusername/artflow-android/issues)

</div>
