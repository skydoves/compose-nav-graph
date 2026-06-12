# KotlinConf: Navigation 3 + Compose HotSwan demo

A demo that wires the [`com.github.skydoves.navgraph`](https://github.com/skydoves/compose-nav-graph) Gradle plugin and
[Compose HotSwan](https://hotswan.dev) into a real **Kotlin Multiplatform** app: JetBrains'
**[KotlinConf app](https://github.com/JetBrains/kotlinconf-app)**.

> Source forked from **[JetBrains/kotlinconf-app](https://github.com/JetBrains/kotlinconf-app)** (Apache 2.0).
> Only the navigation annotations and the two plugins were added. The app itself is untouched.

## The navigation graph

The navgraph plugin reads `@NavDestination` / `@NavEdge` / `@NavPreview` annotations and renders the whole
app's navigation graph, with a device free Layoutlib thumbnail of every screen, right in the IDE. Even
though the UI is **Compose Multiplatform** shared code (a KMP module has no Android resources of its own),
the plugin renders each screen by reusing the consuming Android app's linked resources, automatically.

**26 nodes · 36 edges · 0 isolated · 26/26 thumbnails**: schedule, sessions, speakers, the venue maps,
news, the privacy/settings flows, and more, all edge connected.

## How navgraph is wired

End user setup is intentionally minimal: **applying the plugin auto wires `compose-nav-graph-annotations` (into
`commonMain`) + the `compose-nav-graph-ksp` processor (`kspAndroid`)**, so the shared module declares the plugins:

```kotlin
plugins {
    id("com.google.devtools.ksp") version "<matches your Kotlin version>"
    id("com.github.skydoves.navgraph") version "0.1.0"
}
```

Screens are annotated with `@NavDestination` + `@NavEdge`, and their `@Preview`s with `@NavPreview`:

```kotlin
@NavDestination(route = ScheduleScreen::class)
@NavEdge(to = SessionScreen::class)
@Composable
fun ScheduleScreen(/* … */) { /* … */ }

@NavPreview(route = ScheduleScreen::class, primary = true)
@Preview
@Composable
fun ScheduleScreenPreview() { /* … */ }
```

The `com.github.skydoves.navgraph` artifacts resolve from `mavenLocal()`; publish them with
`./gradlew publishToMavenLocal` from the [compose-nav-graph](https://github.com/skydoves/compose-nav-graph) repo first.

## Compose HotSwan

This sample is also set up for **[Compose HotSwan](https://hotswan.dev)**: hot reload your Composables on
a running device or emulator without losing navigation state. Setup guide:
**[hotswan.dev/install](https://hotswan.dev/install)**.

- The compiler plugin (`com.github.skydoves.compose.hotswan.compiler` `1.3.4`) is applied on the shared
  and Android app modules.
- The on device preview runner ships as a debug only dependency on `:app:androidApp`:

  ```kotlin
  debugImplementation("com.github.skydoves.compose.hotswan:preview:1.3.4")
  ```

## Build & run

```bash
./gradlew :app:androidApp:assembleDebug
```

Open the project in Android Studio with the navgraph IDE plugin installed to explore the navigation graph.

---

Original application: **[JetBrains/kotlinconf-app](https://github.com/JetBrains/kotlinconf-app)**.
© JetBrains s.r.o., licensed under Apache 2.0. This fork adds only the Navigation 3 + HotSwan demo wiring.
The Kodee mascot, the JetBrains Sans typeface, and the KotlinConf branding remain trademarks and brand
assets of JetBrains s.r.o., included unmodified from the upstream app.
