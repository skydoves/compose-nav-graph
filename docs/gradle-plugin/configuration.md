# Configuration

The Gradle plugin is configured through the `navgraph { }` block in your module's `build.gradle.kts`. Every option has a sensible default, so the block is optional. You only add it to change rendering, pin a variant, manage dependencies yourself, tune cross-module aggregation, or tune the `.nav` baseline behavior.

```kotlin
navgraph {
    renderThumbnails.set(true)  // default
    renderBackend.set(RenderBackend.AUTO)  // default
    robolectricApplication.set("")  // default: the app's real Application
    variant.set("")  // default: auto detect
    autoDependencies.set(true)  // default
    aggregate.set(true)  // default
    baselineFile.set(layout.projectDirectory.file("nav/app.nav"))  // default: nav/<module>.nav
    failOnNavChange.set(true)  // default
    allowMissingBaseline.set(false)  // default
    galleryEnabled.set(true)  // default
    galleryRenderBackend.set(RenderBackend.AUTO)  // default
    galleryAggregate.set(true)  // default
}
```

## `renderThumbnails`

**Type:** `Boolean` · **Default:** `true`

Whether to render `@NavPreview` thumbnails via the device free renderer on Android. Set it to `false` when you only want the graph shape, for **structure only** extraction: nodes, edges, and arguments, but no screenshots. Structure only is faster (there's nothing to render), and the graph schema and IDE reader already handle thumbnail-less nodes.

```kotlin
navgraph {
    renderThumbnails.set(false) // structure only, no render
}
```

!!! note "KMP without Android"

    Thumbnail rendering requires an Android target. Kotlin Multiplatform modules without Android are always structure only, regardless of this flag.

## `renderBackend`

**Type:** `RenderBackend` · **Default:** `RenderBackend.AUTO`

Which backend renders `@NavPreview` thumbnails:

- **`AUTO`** (default): device free Layoutlib first, then Robolectric for any preview Layoutlib failed to render.
- **`LAYOUTLIB`**: Layoutlib only. Fast, but some Compose Multiplatform screens may not render.
- **`ROBOLECTRIC`**: Robolectric only. A full Android runtime that renders complex Compose Multiplatform screens.

The Robolectric backends run on the Android unit test classpath; the plugin auto adds the `compose-nav-graph-testing` runtime and Robolectric for you.

```kotlin
navgraph {
    renderBackend.set(RenderBackend.ROBOLECTRIC)
}
```

## `robolectricApplication`

**Type:** `String` · **Default:** `""` (the app's real `Application`)

The fully qualified name of the `Application` class the **Robolectric** render boots. By default Robolectric runs your real `Application.onCreate`, which crashes the render when it initializes SDKs that need a device or Play services (billing, push, analytics). Point this at a minimal test only `Application` — typically in the unit test source set, doing at most DI setup — to render previews without that init:

```kotlin
navgraph {
    robolectricApplication.set("com.example.app.RenderApplication")
}
```

```kotlin
// src/test/kotlin (or src/androidUnitTest/kotlin for KMP)
class RenderApplication : Application()
```

The value is emitted as `@Config(application = …)` on the generated render test; the sdk and qualifiers stay inherited from the base test class.

## `variant`

**Type:** `String` · **Default:** `""` (auto detect)

The Android **variant** to extract from. The default (blank) auto detects the first `…DebugKotlin` KSP variant, so a flavored project usually works without setting this. Pin it only when you want a specific flavor's graph:

```kotlin
navgraph {
    variant.set("demoDebug") // for a flavored app/library
}
```

This is ignored for Kotlin Multiplatform modules, which use the common metadata / Android KSP pass.

## `autoDependencies`

**Type:** `Boolean` · **Default:** `true`

Whether the plugin auto adds its own `compose-nav-graph-annotations` (as `implementation`) and `compose-nav-graph-ksp` (as `ksp`) at the plugin's version, so a consumer only needs to apply the plugin. Set it to `false` to declare those dependencies yourself:

```kotlin
navgraph {
    autoDependencies.set(false)
}

dependencies {
    implementation("com.github.skydoves:compose-nav-graph-annotations:0.1.0")
    ksp("com.github.skydoves:compose-nav-graph-ksp:0.1.0")
}
```

!!! note "KSP is always yours to apply"

    Even with `autoDependencies = true`, you must still apply the KSP Gradle plugin (`com.google.devtools.ksp`) yourself, because its version is tied to your Kotlin version and the navgraph plugin can't choose it for you.

## `aggregate`

**Type:** `Boolean` · **Default:** `true`

Whether this module **aggregates** the nav graphs of its dependency modules into its own combined graph. KSP runs per module, so each feature module's graph only sees its own `@NavDestination`s, and a cross module `@NavEdge` target shows as a stub without a preview. With aggregation on, the `aggregateNavGraph` task merges every dependency module's nav graph and thumbnails with this module's own, reuniting each stub with the real node from the owning module.

An umbrella module (e.g. `:app`) that depends on every feature gets the whole app's graph; a single module app just gets its own. Set it to `false` to restrict this module's graph to its own destinations:

```kotlin
navgraph {
    aggregate.set(false)
}
```

This applies to plain Android modules; Kotlin Multiplatform modules use their own merging.

## `baselineFile`

**Type:** `RegularFileProperty` · **Default:** `<projectDir>/nav/<module>.nav`

The committed `.nav` baseline file used by `navDump` and `navCheck`. Override it to change where the snapshot lives:

```kotlin
navgraph {
    baselineFile.set(layout.projectDirectory.file("nav/app.nav"))
}
```

See [Nav Baseline](baseline.md) for the full workflow.

## `failOnNavChange`

**Type:** `Boolean` · **Default:** `true`

Whether `navCheck` **fails the build** when the current graph drifts from the committed baseline. This is the right default for CI, where unreviewed navigation changes shouldn't merge. Set it to `false` to switch to warning only mode (the task still reports the drift, but the build succeeds):

```kotlin
navgraph {
    // Strict on CI, warning-only locally
    failOnNavChange.set(System.getenv("CI") == "true")
}
```

## `allowMissingBaseline`

**Type:** `Boolean` · **Default:** `false`

Whether a missing `.nav` baseline is a **skip** instead of a failure. By default, running `navCheck` before you've created a baseline fails. Set this to `true` to let the check pass silently when no baseline exists yet, useful during initial adoption:

```kotlin
navgraph {
    allowMissingBaseline.set(true)
}
```

## `galleryEnabled`

**Type:** `Boolean` · **Default:** `true`

Whether to register the **preview gallery** tasks (`generatePreviewGallery`, `exportPreviewGalleryHtml`, `exportPreviewGalleryImage`). The gallery discovers every `@Preview` composable in the module, not only the `@NavPreview` ones, renders each to a thumbnail with the same engine as the nav graph, and exports a self-contained HTML grouped by module and package. The tasks are on demand only, never wired into `generateNavGraph` or `check`, so they cost nothing unless run. Set it to `false` to not register them:

```kotlin
navgraph {
    galleryEnabled.set(false)
}
```

## `galleryRenderBackend`

**Type:** `RenderBackend` · **Default:** `RenderBackend.AUTO`

Which backend renders the preview gallery thumbnails. Independent of [`renderBackend`](#renderbackend) (which drives the nav graph), so the graph and the larger gallery can use different backends:

```kotlin
navgraph {
    galleryRenderBackend.set(RenderBackend.LAYOUTLIB)
}
```

## `galleryAggregate`

**Type:** `Boolean` · **Default:** `true`

Whether the preview gallery aggregates the galleries of this module's dependency modules into one combined gallery. Mirrors [`aggregate`](#aggregate): an umbrella `:app` gets every module's previews grouped by module; a single module app just gets its own. Plain Android modules only.

```kotlin
navgraph {
    galleryAggregate.set(false)
}
```
