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
    inferEdges.set(true)  // default
    baselineFile.set(layout.projectDirectory.file("nav/app.nav"))  // default: nav/<module>.nav
    failOnNavChange.set(true)  // default
    allowMissingBaseline.set(false)  // default
    lintEnabled.set(true)  // default
    failOnNavLint.set(false)  // default
    lintOnCheck.set(false)  // default
    baselineIncludesInferred.set(false)  // default
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
    implementation("com.github.skydoves:compose-nav-graph-annotations:0.2.1")
    ksp("com.github.skydoves:compose-nav-graph-ksp:0.2.1")
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

## `inferEdges`

**Type:** `Boolean` · **Default:** `true`

Whether to **infer** transitions from your navigation call sites. KSP reads declarations only, it cannot see inside
`entry<Home> { … backStack.add(Feed) }`, so without this every transition has to be written out as a `@NavEdge`.
With it on, the `inferNavEdges` task scans this module's Kotlin sources, resolves each call against the routes
already in the graph, and adds what it finds as an inferred transition, drawn **dashed** in the IDE and in every
export so it's never mistaken for one you declared.

```kotlin
navgraph {
    inferEdges.set(false) // only transitions declared with @NavEdge
}
```

A reference that doesn't resolve to a route already in the graph is dropped, so inference never invents a
destination, and an explicit `@NavEdge` always wins over an inferred duplicate of the same transition (your label
and intent are kept). See [Inferred transitions](annotations.md#inferred-transitions) for what it can and can't read.

## `inferNavCalls`

**Type:** `Set<String>` · **Default:** `add`, `addAll`, `set`, `setAll`, `navigate`, `replaceAll`, `replace`, `push`

The method names [`inferEdges`](#inferedges) treats as "this navigates". Matching is by method name only, never by
receiver, so both `backStack.add(Feed)` and a custom wrapper's `navigator.add(Feed)` are found without configuration.
The defaults are seeded as an explicit value, so `add` extends them rather than replacing them. Add to this only if
your app navigates through a differently named method:

```kotlin
navgraph {
    inferNavCalls.add("openScreen")
}
```

The route check is what keeps unrelated calls out (`uriHandler.openUri(URLs.TWITTER)` never matches a route), so a
broader set is safe.

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

## `baselineIncludesInferred`

**Type:** `Boolean` · **Default:** `false`

Whether [inferred transitions](#inferedges) are written into the committed `.nav` baseline.

Off by default so turning edge inference on **never breaks an existing `navCheck`**: the baseline keeps recording
exactly the `@NavEdge`s you declared, while the graph and the exports show the inferred ones too. Upgrading from
0.2.x doesn't move a single line of your committed baseline.

Set it to `true` to hold the *inferred* graph to review as well. Then a transition disappearing from your code fails
`navCheck` the same way a deleted `@NavEdge` does, at the cost of one `navDump` whenever the inferred set changes:

```kotlin
navgraph {
    baselineIncludesInferred.set(true)
}
```

Inferred lines are marked so the two kinds stay distinguishable in a diff:

```
edge Home -> Feed
edge Home -> Settings  (inferred)
```

## `lintEnabled`

**Type:** `Boolean` · **Default:** `true`

Whether the `navLint` task is registered. See [Nav Lint](lint.md).

## `failOnNavLint`

**Type:** `Boolean` · **Default:** `false`

Whether `navLint` fails the build on a finding. Warns by default so adding lint to an existing project reports
without blocking; turn it on in CI once the findings are down to zero.

```kotlin
navgraph {
    failOnNavLint.set(true)
}
```

## `lintOnCheck`

**Type:** `Boolean` · **Default:** `false`

Whether `navLint` runs as part of `check`.

Off by default because lint reads the **aggregated** graph, which is the only input that can tell a route another
module owns from a genuinely unbound one. Gating `check` on it would make `check` build every dependency module's
graph, thumbnail renders included — which is exactly why `navCheck` reads the render-free manifest instead. Prefer
`./gradlew check navLint` as two CI steps; turn this on when the build has no dependency modules to drag in.

## `navLintDisabledRules`

**Type:** `Set<String>` · **Default:** empty

`navLint` rule ids to skip: `unreachable`, `no-start`, `multiple-starts`, `unbound-route`.

```kotlin
navgraph {
    navLintDisabledRules.add("unbound-route")
}
```

## `navLintIgnoredRoutes`

**Type:** `Set<String>` · **Default:** empty

Route FQNs `navLint` reports nothing for, from any rule. For a destination left unwired on purpose — one reached
only by a deep link, or a screen still being built.

```kotlin
navgraph {
    navLintIgnoredRoutes.add("com.app.DeepLinkOnly")
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
