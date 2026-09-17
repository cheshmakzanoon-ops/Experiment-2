# ArtFlow Android App - 50-Phase Development Plan

## Project Overview

This document outlines a comprehensive 50-phase development plan for building **ArtFlow**, a professional-grade digital art application for Android inspired by Procreate. Each phase represents a focused development sprint with specific deliverables, technical requirements, and success criteria.

---

## Architecture Principles

Before beginning Phase 1, understand these core principles:

- **Clean Architecture**: Strict separation between presentation, domain, and data layers
- **MVVM Pattern**: Model-View-ViewModel for UI architecture
- **Dependency Injection**: Hilt for all dependency management
- **Reactive Programming**: Kotlin Flow and StateFlow for reactive data streams
- **GPU Acceleration**: OpenGL ES for rendering. The current renderer uses OpenGL ES 2.0 (`GLSurfaceView` + `GLES20`); ES 3.x and Vulkan are aspirational targets, not implemented.
- **Native Performance**: C++ via JNI for performance-critical operations. The C++ prototype is retained as source but excluded from the application build; the active render path is Kotlin.
- **Test-Driven Development**: Write tests before or alongside implementation
- **Progressive Enhancement**: Build MVP first, then add advanced features

---

## Status of this plan

This document is a **plan**: the checkboxes inside each phase record intent, not progress. The
tables below are the source of truth for what the code actually does, and they are what to trust
when picking up work. Update them when a phase lands or a module gets wired up.

Legend: **✅ wired** — used by the running app · **🟡 partial** — works, but narrower than the
phase describes · **⛔ unreachable** — the code exists but nothing calls it · **⬜ not started**.

| Phases | Scope | Status in this repository |
|--------|-------|---------------------------|
| 1–8 | Setup, DI, architecture, design system, canvas, input, brush, layers | ✅ The Gradle wrapper, build config, dark-first design system, GL canvas, stylus/gesture input, stroke engine and layer stack all work. Strokes are rasterised by `Compositor` + `StrokeRasterizer`; the live layer state is `CanvasRepositoryImpl`. ktlint and detekt are configured (see *Tooling gaps*). |
| 9–16 | Advanced brush params, textures, colour dynamics, blend modes, selection, transform, alpha lock, masks | 🟡 Blend modes, jitter/scatter/taper dynamics, the selection engine and layer masks run in the app. Brush textures are 🟡 partial: paper/canvas/charcoal procedural grains are rendered, with scale/rotation controls; custom import, a user texture library and dual textures remain absent. The custom pressure response has three editable monotone control points. Transform is 🟡 translate only (see phase 14). Masks support reveal/hide painting, selection and layer-alpha sources, and horizontal/vertical/radial gradients, with transactional preview and undo. |
| 17–24 | Smudge, liquify, clone stamp, healing, gradient, paint bucket, text, shapes | ✅ `PixelBrushes` (smudge/clone/heal) and `LiquifyTool` are driven from `ArtFlowCanvasView`, alongside paint bucket, gradient, text and shapes. Shape drawing lives in `ArtFlowCanvasView`; corner radius and boolean ops are ⬜. |
| 25–30 | Adjustment layers, layer groups, reference layers, layer linking, filter layers, smart objects | 🟡 Adjustments (`AdjustmentProcessor`) and filter layers (`ImageFilters`, via `addFilterLayer` / `rasterizeFilterLayer`) are wired, and reference layers exist as a flag plus a label in the layer sheet — though no UI sets the flag. ⬜ Layer groups, layer-linking UI, smart objects. |
| 31–36 | Colour picker, palettes, symmetry, perspective guides, canvas properties, quick menu | ✅ `ColorPanel`, `Palette` with `PaletteCodec` for persistence, `ColorHarmony`, `SymmetryEngine`, `PerspectiveGuide`, `CanvasOperations` and the quick menu. ⬜ Shortcut remapping and stylus-button mapping. |
| 37–42 | Save system, PNG/JPEG export, PSD, PDF, gallery, cloud sync | ✅ Wired: `.artflow` documents (v2: per-layer rasters, frames, timelapse metadata), autosave with crash recovery, PNG/JPEG/WebP/PDF/PSD export and gallery publishing. ⬜ PSD **import**, ⬜ cloud sync. |
| 43–50 | Animation timeline, animation export, timelapse, performance, tutorials, settings, QA, release | 🟡 Timeline, onion skinning and GIF/MP4/frame-sequence export work; settings, help centre and onboarding exist, and device regressions cover storage, rendering, duplication, exports and selected UI flows. ⬜ Timelapse recording, ⬜ analytics/crash reporting, ⬜ benchmark module (`benchmark` is used for a minified launch smoke test, not a performance benchmark), 🟡 broader physical-device and end-to-end coverage. ✅ Signing configuration is validated; production builds can require it with `-PrequireReleaseSigning=true`. CI can build unsigned candidates, which are not approved releases. |

### Known gaps

Nothing in `app/src/main` is known to be unreachable. The prototype layer an earlier revision of
this document listed as dead code — `core/layer`, `core/selection`, `core/transform`,
`core/shape`, `core/brush`, `domain/usecase`, `data/repository/texture`, `data/renderer/native`,
`presentation/ui/components/texture`, the seven Hilt modules that only provided them, and the unused
`domain/model/layer/LayerMask.kt` model — has been deleted. What remains missing is missing
**functionality**, not orphaned code:

- **Layer groups** — `Layer.parentGroupId` exists, but no repository operation or screen sets it.
- **Layer-linking UI** — `CanvasRepositoryImpl` can set and clear `linkGroupId`; nothing calls it.
- **Transform beyond translation** — no rotate, scale, skew, perspective, distortion or snapping.
- **Imported and dual textures** — three procedural grains are wired, but custom texture import,
  a user texture library and dual-texture mixing remain absent. Mask sources and custom pressure
  controls are implemented; their earlier “not implemented” status was stale.
- **Native engine** — the C++ prototype remains unintegrated and is excluded from the app build.
- **PSD import, cloud sync, smart objects, timelapse recording, analytics/crash reporting.**
- **Benchmark module** and a comprehensive physical-device, stylus, GPU and low-memory test campaign.
  Automated coverage now also includes rendering, filling, selections, storage, recovery,
  duplication, exports and selected Compose UI flows.

### Verification and release evidence

The phase tasks below are preserved as the original development goals. Repairs do not turn an
unimplemented phase into a completed one. Check [README.md](README.md) for the active build and
feature set and [docs/RELEASE_READINESS.md](docs/RELEASE_READINESS.md) for actual validation results
and outstanding release work.

The September 2026 reliability work adds JVM rendering/fill/selection/codec regressions and device
coverage for persistence, recovery, GL output, independent project duplication, exports and selected
Compose controls. The old description of only three JVM classes and one launch test is obsolete.
Existing detekt baseline findings remain visible; checks are not disabled to pass new code.

```bash
./gradlew testDebugUnitTest ktlintCheck detekt lintDebug lintRelease
./gradlew assembleDebug bundleRelease
./gradlew connectedDebugAndroidTest
./gradlew bundleRelease -PrequireReleaseSigning=true
```

The last command is for a publisher with a private upload keystore; it must fail without signing
inputs. The first bundle command deliberately permits an unsigned CI candidate. The unused C++
prototype is excluded from the application build, so `-PnoNativeBuild` and an NDK are no longer
needed for the normal application. The daemon budgets 2.5 GB heap, 1 GB metaspace and two workers;
allow at least 6 GB build memory plus emulator memory. CI builds and device tests operate on the
exact revision identified in their reports. The `benchmark` build type is not a benchmark suite.

---

## Phase Breakdown

### 🏗️ Foundation & Setup (Phases 1-8)

#### Phase 1: Project Initialization
**Duration**: 2 days  
**Objective**: Set up the basic project structure and development environment

**Tasks**:
- [ ] Create new Android project with Kotlin
- [ ] Configure minimum SDK (API 26) and target SDK (API 36)
- [ ] Set up Gradle build configuration (build.gradle.kts)
- [ ] Configure build variants (debug, release, benchmark)
- [ ] Initialize Git repository with proper .gitignore
- [ ] Set up project directory structure following Clean Architecture
- [ ] Add basic README.md and CONTRIBUTING.md
- [ ] Configure code style settings (ktlint, detekt)

**Deliverables**:
- Empty Android project with proper structure
- Working build configuration
- Initial Git repository

**Success Criteria**: Project builds successfully, linting passes

---

#### Phase 2: Dependency Configuration
**Duration**: 2 days  
**Objective**: Integrate all required libraries and tools

**Tasks**:
- [ ] Add Jetpack Compose dependencies (UI framework)
- [ ] Configure Hilt for dependency injection
- [ ] Add Room database dependencies
- [ ] Integrate Kotlin Coroutines and Flow
- [ ] Add Coil for image loading
- [ ] Include Timber for logging
- [ ] Set up navigation component
- [ ] Add testing dependencies (JUnit, Mockito, Turbine)
- [ ] Configure NDK for native development
- [ ] Set up OpenGL ES / Vulkan dependencies

**Deliverables**:
- All dependencies integrated
- DI modules configured
- Testing framework ready

**Success Criteria**: All dependencies resolve, app launches without crashes

---

#### Phase 3: Core Architecture Setup
**Duration**: 3 days  
**Objective**: Implement Clean Architecture foundation

**Tasks**:
- [ ] Create domain layer with model classes
- [ ] Define repository interfaces in domain layer
- [ ] Implement use case base classes
- [ ] Set up data layer with repository implementations
- [ ] Create presentation layer structure (UI, ViewModel, Components)
- [ ] Configure Hilt modules for each layer
- [ ] Implement base ViewModel class with error handling
- [ ] Set up coroutine scopes and dispatchers
- [ ] Create dependency injection graph

**Deliverables**:
- Three-layer architecture implemented
- Base classes and interfaces defined
- DI graph fully configured

**Success Criteria**: Can inject dependencies across layers, architecture tests pass

---

#### Phase 4: Design System & UI Components
**Duration**: 4 days  
**Objective**: Build reusable UI component library

**Tasks**:
- [ ] Define color palette and typography system
- [ ] Create theme configuration (dark/light mode)
- [ ] Build button components (primary, secondary, icon)
- [ ] Design slider components for opacity/size controls
- [ ] Implement color picker component
- [ ] Create toolbar and menu components
- [ ] Build layer list item component
- [ ] Design brush preview component
- [ ] Implement canvas view container
- [ ] Create gesture detector wrapper

**Deliverables**:
- Complete design system
- 20+ reusable UI components
- Theme switching functionality

**Success Criteria**: All components render correctly, theme switching works

---

#### Phase 5: Canvas Infrastructure
**Duration**: 5 days  
**Objective**: Create the foundational canvas rendering system

**Tasks**:
- [ ] Design CanvasRenderer interface
- [ ] Implement OpenGL ES surface view
- [ ] Create texture management system
- [ ] Build viewport transformation logic
- [ ] Implement zoom and pan gestures
- [ ] Create canvas state management
- [ ] Set up render loop with vsync
- [ ] Implement double buffering
- [ ] Add canvas initialization from bitmap
- [ ] Create canvas resolution scaler

**Deliverables**:
- Working OpenGL canvas
- Gesture-based zoom/pan
- Basic render loop

**Success Criteria**: Can display blank canvas, zoom/pan at 60 FPS

---

#### Phase 6: Input System
**Duration**: 4 days  
**Objective**: Handle all input methods (touch, stylus, gestures)

**Tasks**:
- [ ] Implement MotionEvent handler
- [ ] Add stylus pressure detection
- [ ] Support stylus tilt angle
- [ ] Create multi-touch gesture recognizer
- [ ] Implement two-finger undo gesture
- [ ] Implement three-finger redo gesture
- [ ] Add pinch-to-zoom gesture
- [ ] Create rotation gesture handler
- [ ] Map stylus button actions
- [ ] Implement palm rejection logic

**Deliverables**:
- Complete input handling system
- Pressure and tilt support
- Gesture recognition

**Success Criteria**: Stylus input registers with pressure, gestures work reliably

---

#### Phase 7: Brush Engine Foundation
**Duration**: 5 days  
**Objective**: Build basic brush rendering capability

**Tasks**:
- [ ] Design Brush abstract class
- [ ] Implement basic stroke algorithm
- [ ] Create pressure-to-size mapping
- [ ] Add pressure-to-opacity mapping
- [ ] Build stroke smoothing (linear interpolation)
- [ ] Implement stroke caching
- [ ] Create brush parameter data class
- [ ] Add basic circle brush shape
- [ ] Implement stroke rendering to texture
- [ ] Create brush preview renderer

**Deliverables**:
- Functional brush engine
- Pressure-sensitive strokes
- Basic brush shapes

**Success Criteria**: Can draw smooth strokes with pressure variation

---

#### Phase 8: Layer System Basics
**Duration**: 5 days  
**Objective**: Implement fundamental layer management

**Tasks**:
- [ ] Design Layer data model
- [ ] Create LayerManager singleton
- [ ] Implement layer stack (add, remove, reorder)
- [ ] Build layer visibility toggle
- [ ] Add layer opacity control
- [ ] Implement layer selection system
- [ ] Create layer thumbnail generator
- [ ] Add layer naming functionality
- [ ] Implement layer duplication
- [ ] Build layer merge (down) function

**Deliverables**:
- Working layer system
- Layer UI panel
- Basic layer operations

**Success Criteria**: Can create/manage multiple layers, see changes in real-time

---

### 🎨 Core Drawing Features (Phases 9-16)

#### Phase 9: Advanced Brush Parameters
**Duration**: 4 days  
**Objective**: Expand brush customization options

**Tasks**:
- [ ] Add brush size jitter
- [ ] Implement opacity jitter
- [ ] Create hue/saturation variation
- [ ] Add scatter parameter
- [ ] Implement brush rotation
- [ ] Add count parameter (multiple dabs)
- [ ] Create spacing control
- [ ] Implement tapering (start/end)
- [ ] Add pressure curve editor
- [ ] Build brush parameter UI

**Deliverables**:
- 10+ brush parameters
- Parameter adjustment UI
- Real-time preview

**Success Criteria**: Brush behavior responds to all parameters

---

#### Phase 10: Brush Textures & Stamps
**Duration**: 4 days  
**Objective**: Enable textured brush strokes

**Tasks**:
- [ ] Implement texture loading system
- [ ] Create texture mapping algorithm
- [ ] Add grain overlay effect
- [ ] Implement stamp-based brushes
- [ ] Create texture blending modes
- [ ] Add texture scale/rotation
- [ ] Implement dual-texture brushes
- [ ] Build texture library manager
- [ ] Create custom texture importer
- [ ] Add texture preview UI

**Deliverables**:
- Texture support in brush engine
- Built-in texture library
- Custom texture import

**Success Criteria**: Brushes can apply textures to strokes

---

#### Phase 11: Color Dynamics
**Duration**: 3 days  
**Objective**: Advanced color behavior in brushes

**Tasks**:
- [ ] Implement color pressure dynamics
- [ ] Add velocity-based color shift
- [ ] Create randomization algorithms
- [ ] Implement gradient stroke feature
- [ ] Add color bleed simulation
- [ ] Create wet-on-wet mixing
- [ ] Build color history tracker
- [ ] Implement eyedropper during stroke
- [ ] Add primary/secondary color blend
- [ ] Create color dynamics UI

**Deliverables**:
- Dynamic color brushes
- Gradient strokes
- Color mixing

**Success Criteria**: Strokes show color variation based on dynamics

---

#### Phase 12: Blend Modes
**Duration**: 4 days  
**Objective**: Implement layer blending algorithms

**Tasks**:
- [ ] Create BlendMode enum
- [ ] Implement Normal blend
- [ ] Add Multiply blend
- [ ] Implement Screen blend
- [ ] Add Overlay blend
- [ ] Create Darken/Lighten blends
- [ ] Implement Color Dodge/Burn
- [ ] Add Hue/Saturation/Color/Luminosity
- [ ] Optimize blend shaders for GPU
- [ ] Build blend mode selector UI

**Deliverables**:
- 12+ blend modes
- GPU-accelerated blending
- UI selector

**Success Criteria**: All blend modes render correctly on layers

---

#### Phase 13: Selection Tools
**Duration**: 5 days  
**Objective**: Enable area selection and manipulation

**Tasks**:
- [ ] Design Selection model
- [ ] Implement freehand selection
- [ ] Add lasso tool
- [ ] Create rectangular selection
- [ ] Implement elliptical selection
- [ ] Add magic wand (color-based)
- [ ] Create selection marching ants animation
- [ ] Implement selection fill
- [ ] Add selection stroke
- [ ] Build selection transform handles

**Deliverables**:
- 5 selection tools
- Selection visualization
- Basic selection operations

**Success Criteria**: Can select areas and perform operations on them

---

#### Phase 14: Transform System
**Duration**: 5 days  
**Objective**: Enable layer/content transformation

**Tasks**:
- [ ] Implement translation transform
- [ ] Add rotation transform
- [ ] Create scale transform
- [ ] Implement skew transform
- [ ] Add perspective transform
- [ ] Create distortion transform
- [ ] Implement transform pivot point
- [ ] Add transform snapping guides
- [ ] Build transform gesture handler
- [ ] Create transform UI overlay

**Deliverables**:
- Full transform toolkit
- Interactive transform handles
- Visual guides

**Success Criteria**: Can transform selected content with precision

---

#### Phase 15: Alpha Lock & Clipping Masks
**Duration**: 3 days  
**Objective**: Non-destructive editing features

**Tasks**:
- [ ] Implement alpha lock per layer
- [ ] Create clipping mask functionality
- [ ] Add visual indicator for locked layers
- [ ] Implement clipping group management
- [ ] Create mask thumbnail preview
- [ ] Add release clipping mask option
- [ ] Implement nested clipping masks
- [ ] Build UI toggles for alpha lock/clipping

**Deliverables**:
- Alpha lock feature
- Clipping masks
- UI indicators

**Success Criteria**: Can paint only on existing pixels or clip to layer below

---

#### Phase 16: Layer Masks
**Duration**: 4 days  
**Objective**: Non-destructive layer masking

**Tasks**:
- [ ] Design Mask data structure
- [ ] Implement grayscale mask rendering
- [ ] Add mask painting functionality
- [ ] Create mask inversion
- [ ] Implement mask linking/unlinking
- [ ] Add mask feathering
- [ ] Create mask density control
- [ ] Implement mask from selection
- [ ] Build mask thumbnail UI
- [ ] Add mask disable/enable toggle

**Deliverables**:
- Full layer mask system
- Mask painting tools
- Visual feedback

**Success Criteria**: Can hide/reveal layer parts non-destructively

---

### 🛠️ Professional Tools (Phases 17-24)

#### Phase 17: Smudge Tool
**Duration**: 4 days  
**Objective**: Realistic paint smudging

**Tasks**:
- [ ] Design smudge algorithm
- [ ] Implement pixel displacement
- [ ] Add pressure-to-strength mapping
- [ ] Create smudge brush variants
- [ ] Implement color picking during smudge
- [ ] Add smudge rate control
- [ ] Create finger smudge mode
- [ ] Implement smudge texture support
- [ ] Build smudge preview
- [ ] Optimize smudge performance

**Deliverables**:
- Smudge tool
- Configurable parameters
- Smooth performance

**Success Criteria**: Can blend colors naturally with smudge tool

---

#### Phase 18: Liquify Tool
**Duration**: 4 days  
**Objective**: Advanced distortion effects

**Tasks**:
- [ ] Implement push distortion
- [ ] Add twirl distortion
- [ ] Create pinch distortion
- [ ] Implement bloat distortion
- [ ] Add reconstruction mode
- [ ] Create brush size/strength controls
- [ ] Implement freeze mask
- [ ] Add thaw mask
- [ ] Build liquify preview mesh
- [ ] Optimize GPU liquify shader

**Deliverables**:
- 5 liquify tools
- Freeze/thaw masks
- Real-time preview

**Success Criteria**: Can distort images with various liquify effects

---

#### Phase 19: Clone Stamp
**Duration**: 3 days  
**Objective**: Content duplication tool

**Tasks**:
- [ ] Implement source point selection
- [ ] Create clone brush algorithm
- [ ] Add aligned/non-aligned modes
- [ ] Implement sample current/all layers
- [ ] Add clone source preview
- [ ] Create multiple clone sources
- [ ] Implement clone rotation/scaling
- [ ] Build clone source UI
- [ ] Add clone stamp opacity control

**Deliverables**:
- Clone stamp tool
- Source management
- Visual feedback

**Success Criteria**: Can duplicate content from one area to another

---

#### Phase 20: Healing Brush
**Duration**: 4 days  
**Objective**: Intelligent content-aware filling

**Tasks**:
- [ ] Implement texture sampling
- [ ] Create color matching algorithm
- [ ] Add edge blending
- [ ] Implement patch-based synthesis
- [ ] Add spot healing variant
- [ ] Create content-aware fill
- [ ] Implement iterative refinement
- [ ] Build healing brush UI
- [ ] Optimize for real-time performance

**Deliverables**:
- Healing brush
- Spot heal tool
- Content-aware fill

**Success Criteria**: Can remove imperfections seamlessly

---

#### Phase 21: Gradient Tool
**Duration**: 3 days  
**Objective**: Gradient creation and editing

**Tasks**:
- [ ] Implement linear gradient
- [ ] Add radial gradient
- [ ] Create angular gradient
- [ ] Implement diamond gradient
- [ ] Add gradient editor UI
- [ ] Create gradient presets
- [ ] Implement gradient transparency
- [ ] Build gradient drag interaction
- [ ] Add gradient along path

**Deliverables**:
- 4 gradient types
- Gradient editor
- Preset library

**Success Criteria**: Can create and apply gradients

---

#### Phase 22: Paint Bucket & Fill
**Duration**: 3 days  
**Objective**: Area filling tools

**Tasks**:
- [ ] Implement flood fill algorithm
- [ ] Add tolerance control
- [ ] Create contiguous/non-contiguous modes
- [ ] Implement pattern fill
- [ ] Add fill layer option
- [ ] Create anti-aliasing for fill
- [ ] Implement fill all layers option
- [ ] Build fill preview
- [ ] Add fill shortcut gesture

**Deliverables**:
- Paint bucket tool
- Pattern fill
- Tolerance control

**Success Criteria**: Can fill enclosed areas with color/pattern

---

#### Phase 23: Text Tool
**Duration**: 4 days  
**Objective**: Vector text support

**Tasks**:
- [ ] Implement text layer type
- [ ] Add font selection
- [ ] Create font size control
- [ ] Implement bold/italic/underline
- [ ] Add text alignment options
- [ ] Create kerning/leading controls
- [ ] Implement text curvature
- [ ] Add text on path
- [ ] Build text editing UI
- [ ] Create rasterize text option

**Deliverables**:
- Text tool
- Font management
- Text editing interface

**Success Criteria**: Can add and edit text on canvas

---

#### Phase 24: Shape Tools
**Duration**: 4 days  
**Objective**: Vector shape creation

**Tasks**:
- [ ] Implement rectangle shape
- [ ] Add ellipse shape
- [ ] Create polygon shape
- [ ] Implement line tool
- [ ] Add shape fill/stroke
- [ ] Create shape corner radius
- [ ] Implement shape Boolean operations
- [ ] Add shape presets
- [ ] Build shape property panel
- [ ] Create vector rasterization

**Deliverables**:
- 5 shape tools
- Shape properties
- Vector support

**Success Criteria**: Can create and edit vector shapes

---

### 🎭 Advanced Layer Features (Phases 25-30)

#### Phase 25: Adjustment Layers
**Duration**: 4 days  
**Objective**: Non-destructive color corrections

**Tasks**:
- [ ] Design adjustment layer type
- [ ] Implement brightness/contrast
- [ ] Add hue/saturation adjustment
- [ ] Create color balance
- [ ] Implement curves adjustment
- [ ] Add levels adjustment
- [ ] Create invert/posterize
- [ ] Implement selective color
- [ ] Build adjustment layer UI
- [ ] Add adjustment stacking

**Deliverables**:
- 8 adjustment types
- Adjustment panel
- Non-destructive workflow

**Success Criteria**: Can apply adjustments without altering original pixels

---

#### Phase 26: Layer Groups
**Duration**: 3 days  
**Objective**: Layer organization

**Tasks**:
- [ ] Implement group layer type
- [ ] Add create/delete group
- [ ] Create nested groups
- [ ] Implement group collapse/expand
- [ ] Add group opacity
- [ ] Create group blend modes
- [ ] Implement move to/from group
- [ ] Build group UI in layer panel
- [ ] Add group selection

**Deliverables**:
- Group functionality
- Nested support
- Layer panel integration

**Success Criteria**: Can organize layers into collapsible groups

---

#### Phase 27: Reference Layer
**Duration**: 2 days  
**Objective**: Copy from reference

**Tasks**:
- [ ] Implement reference layer flag
- [ ] Create copy/paste from reference
- [ ] Add reference window
- [ ] Implement reference resize/reposition
- [ ] Create reference opacity
- [ ] Add multiple references
- [ ] Build reference panel
- [ ] Implement reference import

**Deliverables**:
- Reference layer type
- Reference window
- Copy functionality

**Success Criteria**: Can use layers as reference for tracing

---

#### Phase 28: Layer Linking
**Duration**: 2 days  
**Objective**: Synchronized layer operations

**Tasks**:
- [ ] Implement layer link data structure
- [ ] Add link/unlink functionality
- [ ] Create linked transform
- [ ] Implement linked opacity
- [ ] Add linked visibility
- [ ] Build link visualization
- [ ] Create link groups
- [ ] Add unlink all option

**Deliverables**:
- Layer linking
- Synchronized transforms
- Visual indicators

**Success Criteria**: Linked layers move/transform together

---

#### Phase 29: Filter Layers
**Duration**: 4 days  
**Objective**: Apply filters to layers

**Tasks**:
- [ ] Implement filter layer type
- [ ] Add Gaussian blur
- [ ] Create motion blur
- [ ] Implement sharpen
- [ ] Add noise filter
- [ ] Create chromatic aberration
- [ ] Implement vignette
- [ ] Build filter stack
- [ ] Add filter intensity control
- [ ] Create filter preview

**Deliverables**:
- 6+ filters
- Filter stacking
- Real-time preview

**Success Criteria**: Can apply filters non-destructively

---

#### Phase 30: Smart Objects
**Duration**: 5 days  
**Objective**: Embedded reusable content

**Tasks**:
- [ ] Design smart object structure
- [ ] Implement embed image/file
- [ ] Create smart object editing mode
- [ ] Add non-destructive scaling
- [ ] Implement smart object instances
- [ ] Create relink functionality
- [ ] Add smart object rasterize
- [ ] Build smart object panel
- [ ] Implement nested smart objects
- [ ] Create smart object cache

**Deliverables**:
- Smart objects
- Instance system
- Edit in place

**Success Criteria**: Can embed and reuse content non-destructively

---

### 🎨 Color & Canvas (Phases 31-36)

#### Phase 31: Advanced Color Picker
**Duration**: 3 days  
**Objective**: Comprehensive color selection

**Tasks**:
- [ ] Implement color wheel picker
- [ ] Add RGB sliders
- [ ] Create HSV sliders
- [ ] Implement CMYK view
- [ ] Add color harmony rules
- [ ] Create recent colors history
- [ ] Implement saved palettes
- [ ] Build color picker popup
- [ ] Add color name display
- [ ] Create accessibility modes

**Deliverables**:
- Multiple picker modes
- Color harmonies
- Palette system

**Success Criteria**: Can select colors using various methods

---

#### Phase 32: Color Palettes
**Duration**: 3 days  
**Objective**: Palette management

**Tasks**:
- [ ] Design palette data model
- [ ] Implement create/edit palette
- [ ] Add palette import/export
- [ ] Create palette sharing
- [ ] Implement palette categories
- [ ] Add favorite palettes
- [ ] Create palette preview
- [ ] Build palette panel UI
- [ ] Add ASE file support
- [ ] Implement palette from image

**Deliverables**:
- Palette manager
- Import/export
- Community sharing

**Success Criteria**: Can save, load, and organize color palettes

---

#### Phase 33: Symmetry Tools
**Duration**: 3 days  
**Objective**: Symmetrical drawing

**Tasks**:
- [ ] Implement vertical symmetry
- [ ] Add horizontal symmetry
- [ ] Create quadrant symmetry
- [ ] Implement radial symmetry
- [ ] Add symmetry guide lines
- [ ] Create symmetry offset
- [ ] Implement multiple symmetry axes
- [ ] Build symmetry UI
- [ ] Add symmetry preview
- [ ] Create symmetry presets

**Deliverables**:
- 4 symmetry types
- Guide visualization
- Easy activation

**Success Criteria**: Drawing mirrors across symmetry axes

---

#### Phase 34: Perspective Guides
**Duration**: 4 days  
**Objective**: Perspective drawing assistance

**Tasks**:
- [ ] Implement 1-point perspective
- [ ] Add 2-point perspective
- [ ] Create 3-point perspective
- [ ] Implement vanishing point editing
- [ ] Add perspective grid overlay
- [ ] Create snap to perspective
- [ ] Implement isometric grid
- [ ] Build perspective UI
- [ ] Add multiple perspective guides
- [ ] Create perspective presets

**Deliverables**:
- 3 perspective types
- Editable grids
- Snap functionality

**Success Criteria**: Can draw in accurate perspective

---

#### Phase 35: Canvas Properties
**Duration**: 2 days  
**Objective**: Canvas configuration

**Tasks**:
- [ ] Implement canvas resize
- [ ] Add crop tool
- [ ] Create canvas rotation
- [ ] Implement DPI change
- [ ] Add canvas flip
- [ ] Create trim transparent pixels
- [ ] Implement fit to content
- [ ] Build canvas info panel
- [ ] Add preset sizes
- [ ] Create custom canvas dialog

**Deliverables**:
- Resize/crop tools
- DPI management
- Preset templates

**Success Criteria**: Can modify canvas dimensions and properties

---

#### Phase 36: Quick Menu & Shortcuts
**Duration**: 3 days  
**Objective**: Efficient workflow

**Tasks**:
- [ ] Design quick menu system
- [ ] Implement customizable shortcuts
- [ ] Create gesture shortcuts
- [ ] Add stylus button mapping
- [ ] Implement keyboard shortcuts (external)
- [ ] Create quick access bar
- [ ] Add favorites system
- [ ] Build shortcut editor UI
- [ ] Implement shortcut profiles
- [ ] Create shortcut export/import

**Deliverables**:
- Quick menu
- Customizable shortcuts
- Gesture mapping

**Success Criteria**: Can customize and use shortcuts efficiently

---

### 💾 File Operations (Phases 37-42)

#### Phase 37: Save System
**Duration**: 3 days  
**Objective**: Project persistence

**Tasks**:
- [ ] Design .artflow file format
- [ ] Implement save to internal storage
- [ ] Add save to external storage
- [ ] Create autosave functionality
- [ ] Implement save versioning
- [ ] Add save progress indicator
- [ ] Create recovery system
- [ ] Build save dialog UI
- [ ] Implement auto-backup
- [ ] Add cloud save placeholder

**Deliverables**:
- Native file format
- Autosave
- Recovery system

**Success Criteria**: Projects save and restore completely

---

#### Phase 38: Export PNG/JPEG
**Duration**: 3 days  
**Objective**: Standard image export

**Tasks**:
- [ ] Implement PNG export
- [ ] Add JPEG export with quality
- [ ] Create export dialog
- [ ] Implement transparency handling
- [ ] Add export scaling
- [ ] Create batch export
- [ ] Implement metadata embedding
- [ ] Build export progress
- [ ] Add share after export
- [ ] Create export presets

**Deliverables**:
- PNG/JPEG export
- Quality controls
- Batch processing

**Success Criteria**: Can export artwork in standard formats

---

#### Phase 39: PSD Export/Import
**Duration**: 5 days  
**Objective**: Photoshop compatibility

**Tasks**:
- [ ] Research PSD file specification
- [ ] Implement layer export to PSD
- [ ] Add blend mode mapping
- [ ] Create text layer preservation
- [ ] Implement shape layer export
- [ ] Add PSD import parser
- [ ] Create layer group handling
- [ ] Build import progress UI
- [ ] Test with Adobe Photoshop files
- [ ] Handle PSD limitations

**Deliverables**:
- PSD export
- PSD import
- Compatibility testing

**Success Criteria**: Files open correctly in Photoshop and vice versa

---

#### Phase 40: PDF Export
**Duration**: 3 days  
**Objective**: Vector-friendly export

**Tasks**:
- [ ] Implement PDF document generation
- [ ] Add vector text export
- [ ] Create vector shape export
- [ ] Implement raster layer embedding
- [ ] Add multi-page PDF
- [ ] Create PDF metadata
- [ ] Build PDF export dialog
- [ ] Implement PDF/A compliance
- [ ] Add PDF preview
- [ ] Create print-ready presets

**Deliverables**:
- PDF export
- Vector preservation
- Print-ready output

**Success Criteria**: Exports scalable PDFs with vectors where possible

---

#### Phase 41: Gallery & File Browser
**Duration**: 4 days  
**Objective**: Project management

**Tasks**:
- [ ] Design gallery UI
- [ ] Implement project thumbnails
- [ ] Add project metadata display
- [ ] Create folder organization
- [ ] Implement search functionality
- [ ] Add sort options (date, name, size)
- [ ] Create bulk operations
- [ ] Build project preview
- [ ] Implement recent projects
- [ ] Add favorites system

**Deliverables**:
- Gallery view
- File browser
- Project management

**Success Criteria**: Can browse and manage all projects

---

#### Phase 42: Cloud Sync Integration
**Duration**: 5 days  
**Objective**: Cloud backup and sync

**Tasks**:
- [ ] Integrate Google Drive API
- [ ] Add Dropbox API
- [ ] Implement sync logic
- [ ] Create conflict resolution
- [ ] Add sync status indicator
- [ ] Implement background sync
- [ ] Create manual sync trigger
- [ ] Build cloud settings UI
- [ ] Add selective sync
- [ ] Implement offline mode

**Deliverables**:
- Cloud providers
- Automatic sync
- Conflict handling

**Success Criteria**: Projects sync across devices

---

### 🎬 Animation & Advanced (Phases 43-50)

#### Phase 43: Animation Timeline
**Duration**: 5 days  
**Objective**: Frame-by-frame animation

**Tasks**:
- [ ] Design animation data model
- [ ] Implement timeline UI
- [ ] Add frame creation/deletion
- [ ] Create frame duplication
- [ ] Implement frame reordering
- [ ] Add onion skinning
- [ ] Create playback controls
- [ ] Implement FPS setting
- [ ] Build timeline scrubbing
- [ ] Add loop options

**Deliverables**:
- Timeline interface
- Frame management
- Onion skinning

**Success Criteria**: Can create simple frame animations

---

#### Phase 44: Animation Export
**Duration**: 3 days  
**Objective**: Export animated content

**Tasks**:
- [ ] Implement GIF export
- [ ] Add APNG export
- [ ] Create MP4 video export
- [ ] Implement frame sequence export
- [ ] Add export quality settings
- [ ] Create export progress
- [ ] Implement loop configuration
- [ ] Build animation preview
- [ ] Add audio track support (MP4)
- [ ] Create export presets

**Deliverables**:
- Multiple export formats
- Quality controls
- Preview playback

**Success Criteria**: Animations export and play correctly

---

#### Phase 45: Timelapse Recording
**Duration**: 3 days  
**Objective**: Process recording

**Tasks**:
- [ ] Implement stroke recording
- [ ] Create timelapse encoder
- [ ] Add resolution options
- [ ] Implement speed multiplier
- [ ] Create pause/resume recording
- [ ] Add audio narration
- [ ] Build timelapse player
- [ ] Implement export to video
- [ ] Create share functionality
- [ ] Add watermarking option

**Deliverables**:
- Stroke recording
- Timelapse export
- Playback

**Success Criteria**: Can record and share creation process

---

#### Phase 46: Performance Optimization
**Duration**: 5 days  
**Objective**: Maximize performance

**Tasks**:
- [ ] Profile memory usage
- [ ] Optimize texture memory
- [ ] Implement tile-based rendering
- [ ] Add level-of-detail system
- [ ] Create async operations
- [ ] Optimize brush algorithms
- [ ] Reduce garbage collection
- [ ] Implement lazy loading
- [ ] Add performance monitoring
- [ ] Create low-memory mode

**Deliverables**:
- Optimized rendering
- Memory efficiency
- Performance metrics

**Success Criteria**: 60 FPS on large canvases, minimal lag

---

#### Phase 47: Tutorial System
**Duration**: 4 days  
**Objective**: User onboarding

**Tasks**:
- [ ] Design tutorial framework
- [ ] Create interactive tutorials
- [ ] Implement tooltip system
- [ ] Add video tutorials
- [ ] Create beginner projects
- [ ] Implement progress tracking
- [ ] Add achievement system
- [ ] Build help center
- [ ] Create gesture guide
- [ ] Implement contextual help

**Deliverables**:
- Tutorial system
- Help content
- Onboarding flow

**Success Criteria**: New users can learn basics quickly

---

#### Phase 48: Settings & Preferences
**Duration**: 3 days  
**Objective**: Comprehensive customization

**Tasks**:
- [ ] Implement general settings
- [ ] Add brush defaults
- [ ] Create UI preferences
- [ ] Implement gesture settings
- [ ] Add stylus configuration
- [ ] Create backup settings
- [ ] Implement language selection
- [ ] Build about screen
- [ ] Add reset options
- [ ] Create settings export/import

**Deliverables**:
- Settings screens
- All preferences
- Reset functionality

**Success Criteria**: Users can customize all aspects of the app

---

#### Phase 49: Testing & QA
**Duration**: 7 days  
**Objective**: Ensure quality and stability

**Tasks**:
- [ ] Write unit tests for core logic
- [ ] Create UI tests with Compose
- [ ] Implement integration tests
- [ ] Perform stress testing
- [ ] Conduct memory leak analysis
- [ ] Test on multiple devices
- [ ] Perform beta testing
- [ ] Fix critical bugs
- [ ] Optimize crash reporting
- [ ] Create test documentation

**Deliverables**:
- Test coverage >80%
- Bug fixes
- Stability improvements

**Success Criteria**: App is stable, crashes <0.1%

---

#### Phase 50: Release Preparation
**Duration**: 5 days  
**Objective**: Launch readiness

**Tasks**:
- [ ] Create app icons and assets
- [ ] Write app store description
- [ ] Prepare screenshots
- [ ] Create promotional video
- [ ] Implement analytics
- [ ] Set up crash reporting (Firebase)
- [ ] Configure ProGuard/R8
- [ ] Sign release build
- [ ] Submit to Play Store
- [ ] Create launch plan

**Deliverables**:
- Release build
- Store listing
- Marketing materials

**Success Criteria**: App published and available for download

---

## Technical Specifications

### Performance Targets
- **Canvas Rendering**: 60 FPS minimum
- **Brush Latency**: <20ms on supported devices
- **App Startup**: <2 seconds cold start
- **Memory Usage**: <500MB for typical canvas
- **Crash Rate**: <0.1% sessions

### Supported Devices
- Minimum: Android 8.0 (API 26), 4GB RAM
- Recommended: Android 11+, 8GB RAM, stylus support
- Optimized: Samsung Galaxy Tab S series, Lenovo Tab P series

### Key Technologies
- **Language**: Kotlin 2.0.21
- **UI**: Jetpack Compose
- **Graphics**: OpenGL ES 2.0 through `GLSurfaceView` / `GLES20` (ES 3.x and Vulkan are aspirational)
- **Native**: C++ via JNI/NDK — built, not called
- **DI**: Hilt
- **Async**: Coroutines + Flow
- **Storage**: Room + kotlinx.serialization `.artflow` documents (no DataStore)

---

## Risk Mitigation

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Performance issues on low-end devices | High | High | Implement adaptive quality, LOD system |
| Complex brush algorithms too slow | Medium | High | Move to native C++, GPU shaders |
| PSD compatibility issues | Medium | Medium | Focus on common features, document limitations |
| Memory exhaustion on large canvases | High | High | Tile-based rendering, aggressive caching |
| Stylus latency too high | Medium | High | Direct input pipeline, prediction algorithms |
| Scope creep | High | Medium | Strict phase boundaries, MVP focus |

---

## Success Metrics

### Development Metrics
- All 50 phases completed
- Code coverage >80%
- Zero critical bugs at launch
- Build time <5 minutes

### User Metrics (Post-Launch)
- App rating >4.5 stars
- DAU/MAU ratio >30%
- Session length >15 minutes
- Crash-free sessions >99.9%

---

## Maintenance Plan (Post-Launch)

### Monthly
- Bug fix releases
- Performance optimizations
- New brush packs

### Quarterly
- Major feature updates
- UI/UX improvements
- Device compatibility updates

### Annually
- Architecture review
- Technology stack updates
- Major version release

---

<div align="center">

**ArtFlow Development Plan - 50 Phases to Professional Digital Art**

*Estimated Total Duration: 6-8 months with dedicated team*

</div>
