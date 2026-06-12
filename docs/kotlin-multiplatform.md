# Kotlin Multiplatform

Compose Navigation Graph supports **Kotlin Multiplatform** out of the box. The annotations are a KMP library that lives in
your `commonMain` source set (published for `android`, `jvm`, `iosArm64` / `iosSimulatorArm64` / `iosX64`, `js`, and
`wasmJs`), and the Gradle plugin detects your module's shape and wires the right KSP pass for you. There is no
KMP specific configuration: you apply the same two plugins as on a plain Android module.

```kotlin
// shared/build.gradle.kts
plugins {
    id("com.google.devtools.ksp") version "<matching your Kotlin version>"
    id("com.github.skydoves.navgraph") version "0.1.0"
}
```

The plugin adds `compose-nav-graph-annotations` to `commonMain` automatically, so `@NavDestination`, `@NavEdge`,
`@NavPreview`, and `@NavGraphRoot` are usable from shared code right away.

## How module detection works

The plugin recognizes three module shapes and picks the extraction pass accordingly:

| Module shape | Extraction pass | Thumbnails |
|---|---|---|
| Plain Android | the debug variant's KSP pass | Yes |
| KMP **with** an Android target | the Android compilation's KSP pass (`kspAndroidMain` on the new `androidLibrary {}` DSL, the legacy per variant pass otherwise) | Yes |
| KMP **without** an Android target (iOS/JS/wasm only) | the common metadata KSP pass | Structure only |

Detection happens automatically, including the new `com.android.kotlin.multiplatform.library` (`androidLibrary {}`)
DSL, where the module namespace is also resolved for you.

## Thumbnails for shared Compose Multiplatform screens

Rendering needs Android, but a KMP shared module has **no Android resources of its own**. The plugin solves this by
reusing the **consuming Android app's** linked resources: when it finds an Android app that depends on your shared
module, the renderer runs against that app's fully merged Compose Multiplatform resources (the app's own, the shared
module's, and every transitive dependency's). Your `composeResources` images, fonts, and strings all show up in the
thumbnails.

Two render backends cooperate (see [`renderBackend`](gradle-plugin/configuration.md#renderbackend)):

- **Layoutlib** (device free, fast): the same engine Android Studio uses for `@Preview`. Most screens render here.
- **Robolectric** (full Android runtime): complex Compose Multiplatform screens that Layoutlib can't draw fall back
  here automatically when `renderBackend` is `AUTO` (the default).

!!! tip "Keep previews render friendly"

    The renderer instantiates your `@NavPreview` composables without a running app, so previews should stay
    **stateless**: render plain UI with mock data, and avoid view models, real network or media players, and other
    runtime only machinery inside preview bodies. This is the same rule that makes Android Studio previews reliable.

## Structure only graphs

A KMP module with no Android target (an iOS/JS/wasm only project) still gets a full navigation graph: nodes, typed
arguments, start destination, and transitions, extracted from the common metadata pass. Only the thumbnails are
skipped, and the IDE plugin and exports render thumbnail-less nodes gracefully. You can also opt into the same
behavior anywhere with `navgraph { renderThumbnails.set(false) }` when you only care about the graph's shape.

## Multi module aggregation

Cross module aggregation works the same as on Android: each module (shared KMP or Android) emits its own
`nav-graph.json`, and the app module merges its own graph with every dependency's into one picture. See
[Configuration](gradle-plugin/configuration.md#aggregate) for the `aggregate` switch.

## A real world example

[`samples/sample-kotlinconf/`](https://github.com/skydoves/compose-nav-graph/tree/main/samples/sample-kotlinconf)
applies the plugin to JetBrains' **KotlinConf app**, a production grade Compose Multiplatform project (Android, iOS,
desktop, and web targets, multi module). The shared `:app:shared` module declares the two plugins, screens are
annotated in `commonMain`, and the result is the complete conference app flow: **26 screens, 36 transitions, and a
rendered thumbnail for every screen**, including screens built entirely from Compose Multiplatform resources.

The committed export of that graph is in
[`nav-results/`](https://github.com/skydoves/compose-nav-graph/tree/main/nav-results), if you want to see the output
without building anything.
