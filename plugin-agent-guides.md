# Compose Navigation Graph Plugin Agent Guide

This is a complete playbook for an agent that needs to apply the Compose Navigation Graph plugin to a
new Android or Kotlin Multiplatform project, going from nothing to an exported PNG and HTML of the
navigation graph. Read it once top to bottom, then follow the steps in order. Every rule here came
from applying the plugin to real apps, so treat the pitfalls section as required reading, not as an
appendix.

The plugin id is `com.github.skydoves.navgraph`. Apply the latest published version. All commands assume a
zsh shell.

---

## 1. What the plugin produces

The plugin reads a small set of annotations and generates, under the module's build directory:

* `build/navgraph/nav-graph.json`: the graph model (nodes, edges, previews, thumbnail paths).
* `build/navgraph/thumbs/`: one PNG per rendered preview.
* `build/navgraph/nav-graph.png`: one static image of the whole graph.
* `build/navgraph/nav-graph.html`: one interactive page with every thumbnail embedded as base64, so
  that single file is the whole shareable deliverable.

Each thumbnail is a real Compose screen rendered without an emulator.

---

## 2. The annotation model

You describe the graph with `NavKey` from AndroidX navigation3 plus four annotations from
`compose-nav-graph-annotations`. The plugin does not read your real navigation library (Voyager, Compose
Navigation, fragments, Decompose). You write this annotated model alongside it. Writing it is a one
time setup step per project.

Destinations are a sealed `NavKey`. Each implementer's serializable properties become its navigation
arguments, which is exactly what the processor extracts.

```kotlin
import androidx.navigation3.runtime.NavKey
import com.github.skydoves.navgraph.annotations.NavGraphRoot
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppKey : NavKey

@NavGraphRoot                                  // the single start destination
@Serializable
data object Home : AppKey

@Serializable
data object Feed : AppKey

@Serializable
data class Profile(val userId: String) : AppKey   // userId becomes a typed nav argument
```

Screens carry the click target and the edges:

```kotlin
import com.github.skydoves.navgraph.annotations.NavDestination
import com.github.skydoves.navgraph.annotations.NavEdge

@NavEdge(to = Feed::class)                      // repeatable: one screen can point at many destinations
@NavEdge(to = Settings::class, label = "settings")
@NavDestination(route = Home::class)            // this composable is Home's click target
@Composable
fun HomeScreen() { /* the screen, or a representative stand in (see section 6) */ }
```

Previews link a `@Preview` to a destination so its render becomes that node's thumbnail:

```kotlin
import androidx.compose.ui.tooling.preview.Preview
import com.github.skydoves.navgraph.annotations.NavPreview

@NavPreview(route = Home::class, primary = true)
@Preview(showBackground = true)
@Composable
internal fun HomePreview() {
  AppPreviewTheme { HomeScreen() }
}
```

The four annotations in one sentence: `@NavGraphRoot` marks the one start destination on its NavKey,
`@NavDestination(route = X::class)` names a screen the click target for X, `@NavEdge(to = Y::class)`
adds an outgoing transition, and `@NavPreview(route = X::class)` turns a `@Preview` into X's thumbnail.

For working examples of every module shape, read the in repo samples: `samples/sample` (plain Android),
`samples/sample-kotlinconf` (Kotlin Multiplatform), and `samples/sample-nowinandroid` (a multi module app).

---

## 3. Scout the project before touching anything

Ground every later decision by reading first:

1. Module layout. The include lines in `settings.gradle.kts` tell you which module holds the screens.
   Record that module path, for example `:app` or `:composeApp`. Every gradle task in section 8 is
   prefixed with it.
2. The build file of that module. The plugins block, plus the Kotlin version and the AGP version
   (usually in `gradle/libs.versions.toml`). Note whether the KSP plugin, the Compose setup, and the
   Kotlin serialization plugin are already applied.
3. The navigation library and the dependency injection library (Koin, Hilt, Injekt, Dagger, Metro).
   Any DI at all means the previews must be DI free (section 6).
4. The real screens, so your destinations and previews look authentic.

Write down the exact Kotlin version. You will need a matching KSP version in the next step.

---

## 4. Plugin setup

The module you apply navgraph to must already be a Compose module, and it must apply the Kotlin
serialization plugin, because the model you write is `@Serializable` Compose code. Every app set up so
far already had Compose. If the target module does not, set Compose up first (the Compose compiler
plugin, `material3`, and the Compose preview tooling). Apply the serialization plugin at your Kotlin
version if it is missing:

```kotlin
plugins {
  id("org.jetbrains.kotlin.plugin.serialization") version "<your Kotlin version>"
}
```

### 4.1 Repositories

The plugin and its artifacts resolve from Maven Central, which a normal Android project already lists.
Make sure `mavenCentral()` is present where plugins resolve and where dependencies resolve in
`settings.gradle.kts`:

```kotlin
pluginManagement {
  repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
  repositories { google(); mavenCentral() }
}
```

### 4.2 Apply KSP, the most common setup blocker

The graph is generated by a KSP processor, so the module must apply the KSP plugin. The navgraph plugin
cannot apply it for you, because the KSP version is tied to your exact Kotlin version.

```kotlin
plugins {
  id("com.google.devtools.ksp") version "<the release that matches your Kotlin version>"
}
```

Find the matching version on Maven Central, or copy what a project on the same Kotlin version uses.
The in repo `samples/sample` is a good reference: it pins Kotlin `2.3.21` together with KSP `2.3.9`. If the
project already applies KSP, you skip this and reuse the existing version.

### 4.3 Apply the navgraph plugin

```kotlin
plugins {
  id("com.github.skydoves.navgraph") version "<latest>"
}
```

By default the plugin wires `compose-nav-graph-annotations` and the `compose-nav-graph-ksp` processor for you, and it adds
`compose-nav-graph-testing` as well whenever the Robolectric backend can run, that is the AUTO or ROBOLECTRIC
backend on an Android module with thumbnails enabled. You declare none of those yourself. To turn this
off and declare them by hand, set `navgraph { autoDependencies.set(false) }`.

### 4.4 Add the navigation3 runtime

`NavKey` lives in AndroidX navigation3, which the plugin does not pull in. Add it to the module:

```kotlin
implementation("androidx.navigation3:navigation3-runtime:1.1.2")
```

For a Kotlin Multiplatform common source set the navigation3 coordinate and version can differ, so
match what the Kotlin Multiplatform samples use rather than copying the Android version here.

### 4.5 Restrict nodes to your own annotations

`ksp { ... }` is a top level block in the module build file:

```kotlin
ksp { arg("navgraph.annotatedOnly", "true") }
```

Without this, every concrete NavKey on the classpath becomes a node. With it, only declarations you
annotated become nodes, which keeps the graph clean.

### 4.6 Local SDK

If a build reports `SDK location not found`, create `local.properties` with `sdk.dir` pointing at the
Android SDK on this machine (for example `sdk.dir=/Users/<you>/Library/Android/sdk`). The file is
gitignored.

---

## 5. Choose destinations and edges

Aim for a graph that reads like the real app. Ten to fifteen destinations with ten or more edges is a
good target. Pick the screens a person actually moves between:

* The home or root screen. Mark its NavKey with `@NavGraphRoot`.
* The main sections.
* A detail screen and whatever it opens.
* Settings and a few notable sub screens.

Model the edges on the real flow: home opens a detail, a detail opens a child, a section opens search,
and so on. Give edges a short `label` when the transition has a name worth showing.

---

## 6. The representative preview rule, the single most important step

Real screens almost always read a ViewModel and a dependency injection container. A bare `@Preview`
of a real screen crashes, because that container is not running during a headless render. So you write
representative previews: small self contained composables that look like the screen but reach for
nothing outside Compose.

Rules for a representative preview:

* No dependency injection, no ViewModel, no repository, no injected field.
* No network and no image loading. Use a solid color or a gradient as a placeholder for covers and
  avatars.
* A plain `MaterialTheme`. Define a tiny theme wrapper so thumbnails are deterministic, and match the
  app palette so the graph feels like the real product.
* Build a handful of reusable stub composables (a top bar, a list row, a card, a cover grid) and
  assemble each screen from them.

A minimal template:

```kotlin
private val AppLightColors = lightColorScheme(primary = Color(0xFF3F51B5))

@Composable
fun AppPreviewTheme(content: @Composable () -> Unit) =
  MaterialTheme(colorScheme = AppLightColors, content = content)

@NavEdge(to = Detail::class)
@NavDestination(route = Home::class)
@Composable
fun HomeScreen() {
  Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
    Text("Home", style = MaterialTheme.typography.headlineSmall)
    // a few stub rows or cards, no DI, no network
  }
}

@NavPreview(route = Home::class, primary = true)
@Preview
@Composable
internal fun HomePreview() = AppPreviewTheme { HomeScreen() }
```

The thumbnail does not need the real screen. It needs to be recognizable.

---

## 7. Pick the render backend

Set it on the module:

```kotlin
navgraph { renderBackend.set(com.github.skydoves.navgraph.gradle.RenderBackend.ROBOLECTRIC) }
```

| Backend | What it does | Use when |
| --- | --- | --- |
| AUTO (default) | Layoutlib renders without a device first, then any preview it could not draw is rendered under Robolectric instead | Most projects. The safe default. |
| LAYOUTLIB | Device free only, fastest | Plain Compose or AndroidX screens that render cleanly |
| ROBOLECTRIC | A full Android runtime under Robolectric | Every Compose Multiplatform app, where Layoutlib cannot draw the screens |

Guidance:

* Plain Android with simple representative previews can stay on AUTO. Layoutlib renders quickly.
* A Compose Multiplatform app (DroidKaigi, KotlinConf, and similar) should set ROBOLECTRIC directly.
  AUTO also works, but Layoutlib cannot draw those screens, so it spends about a minute stalling on
  them before Robolectric takes over.

For a flavored app, also pin the variant so the plugin knows which one to extract:

```kotlin
navgraph { variant.set("demoDebug") }
```

To extract structure only with no rendering, set `navgraph { renderThumbnails.set(false) }`.

---

## 8. Run the gradle tasks

The shell is zsh, where `$PIPESTATUS` is empty (it is `$pipestatus[1]`, one indexed). Never pipe gradle
through tail or head to read the exit code. Run it without a pipe, send output to a log file inside the
project, capture the exit code, then grep the log.

```
./gradlew :app:generateNavGraph :app:exportNavGraphImage :app:exportNavGraphHtml --console=plain > navgraph-build.log 2>&1; echo "exit=$?"
```

Replace `:app` with the module that applies navgraph, for example `:composeApp` or `:app-shared`. Then grep
`navgraph-build.log` for `BUILD SUCCESSFUL` and for the line that prints the node and thumbnail counts. The
two export tasks already depend on generation, so `generateNavGraph` matters on its own only when you
want the graph without an image or page. The export tasks write:

* `build/navgraph/nav-graph.png`
* `build/navgraph/nav-graph.html`

A large app can take several minutes on the first run, so use a generous timeout. Optional gradle
properties on the export tasks: `-Pnavgraph.export.device=WxH` frames the thumbnails, `-Pnavgraph.export.scale=N`
sets a hi dpi scale, and `-Pnavgraph.export.out=<path>` redirects the output file.

---

## 9. Verify before you call it done

1. Read `build/navgraph/nav-graph.json`. Report the node count, the thumbnail count, and every node
   without a thumbnail.
2. For each node without a thumbnail, find the cause. A destination that has no `@NavPreview` shows as
   "no preview" by design. A node whose render failed is a bug, so read the render log.
3. Open the PNG with the image reader and confirm it shows real rendered screens, not blank frames. A
   run of identically sized blank thumbnails means the render failed silently, so investigate it (the
   usual cause is a preview that was not DI free, or a Compose Multiplatform screen that needed
   ROBOLECTRIC).

Only after the pixels look right is the task done.

---

## 10. Multi module apps and aggregation

The processor runs per module, so each module sees only its own annotations. In a multi module app a
feature module's graph shows cross module edge targets as no preview stubs.

Aggregation solves this and is on by default. An umbrella module, usually `:app`, which depends on every
feature, merges each dependency module's graph together with its own into one combined graph. Every stub
is then replaced by the real node and thumbnail from the module that declares it. You write no extra
config. Applying the plugin is enough. The combined graph lands in `build/navgraph-aggregated/`, and the
export tasks read it. A single module app simply gets its own graph, since it has nothing to merge.

You have two ways to model a multi module project:

* Put the whole representative model in one module. This is the simplest and gives one graph. Most
  setups did this.
* Spread destinations across feature modules the way the real app does, then let the umbrella module
  aggregate them.

To force a module's graph to its own destinations only, set `navgraph { aggregate.set(false) }`. Kotlin
Multiplatform modules are excluded from aggregation automatically and always use their own graph.

---

## 11. Common blockers and their fixes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `SDK location not found` | no local.properties | create it with `sdk.dir` |
| `@Serializable` does not resolve, or no serializer is generated | the Kotlin serialization plugin is not applied | apply `org.jetbrains.kotlin.plugin.serialization` at your Kotlin version |
| `@Composable` or `@Preview` does not resolve | the module is not set up for Compose | apply the Compose compiler plugin and add material3 plus the Compose preview tooling |
| KSP task missing, or an empty `nav-graph.json` | the KSP plugin is not applied, or its version does not match Kotlin | apply `com.google.devtools.ksp` at the version that matches your Kotlin version |
| thumbnails are blank and all the same size | a preview crashed because it was not DI free, or a runtime class was missing | make every preview DI free; for a Compose Multiplatform app set ROBOLECTRIC |
| the plugin cannot be resolved | `mavenCentral()` is missing from the plugin repositories | add it to `pluginManagement { repositories }` |
| Compose Multiplatform screens never render under Layoutlib | Layoutlib cannot draw those screens | set the backend to ROBOLECTRIC |
| a minSdk merge conflict from a transitive library | a dependency wants a higher minSdk than the consumer | add `tools:overrideLibrary` to the unit test or debug manifest |
| a flavored app picks the wrong variant or none | auto detection is ambiguous | set `navgraph { variant.set("...") }` |
| an incompatible compiler plugin breaks the build | for example an old hot reload compiler plugin | disable that plugin on the module |

---

## 12. What has been validated

The plugin has produced correct graphs across all of these, so a new project that matches one of them
is on a proven path:

* Module shape: plain Android, Kotlin Multiplatform with Android, and a multi module app with an
  aggregating umbrella.
* Build: AGP 8 and AGP 9, Kotlin through 2.3.21, flavored apps.
* Dependency injection: Hilt, Koin, Injekt, Metro. In every case the previews were representative and
  DI free.
* Rendering: Layoutlib for simpler screens, Robolectric for Compose Multiplatform.

---

## 13. Quick checklist

* [ ] target module is a Compose module and applies the Kotlin serialization plugin
* [ ] `mavenCentral()` in the plugin and dependency repositories
* [ ] KSP plugin applied at a version that matches Kotlin
* [ ] navgraph plugin applied at the latest published version
* [ ] `androidx.navigation3:navigation3-runtime` dependency added
* [ ] `ksp { arg("navgraph.annotatedOnly", "true") }`
* [ ] one `@NavGraphRoot`, destinations with `@NavDestination`, edges with `@NavEdge`
* [ ] representative DI free previews with `@NavPreview`
* [ ] render backend chosen (ROBOLECTRIC for Compose Multiplatform)
* [ ] `variant` set for a flavored app
* [ ] `generateNavGraph`, `exportNavGraphImage`, `exportNavGraphHtml` all run green
* [ ] `nav-graph.json` counts checked and the PNG pixel verified
