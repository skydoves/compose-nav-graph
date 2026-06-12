# Now in Android: Navigation 3 + Compose HotSwan demo

A demo that wires the [`com.github.skydoves.navgraph`](https://github.com/skydoves/compose-nav-graph) Gradle plugin and
[Compose HotSwan](https://hotswan.dev) into a real, multi module production app:
Google's **[Now in Android](https://github.com/android/nowinandroid)**.

> Source forked from **[android/nowinandroid](https://github.com/android/nowinandroid)** (Apache 2.0).
> Only the navigation annotations and the two plugins were added. The app itself is untouched.

## The navigation graph

The navgraph plugin reads `@NavDestination` / `@NavEdge` / `@NavPreview` annotations across the feature
modules and renders the whole app's navigation graph, with a device free Layoutlib thumbnail of every
screen, right in the IDE.

Now in Android's five top level destinations, fully connected:

```
ForYou (start) ⇄ Bookmarks ⇄ Interests       bottom-nav tabs (mutually reachable)
        every tab ─→ Search,  every tab ─→ Settings (gear),  every screen ─→ Topic
        Search ─→ Interests,  Interests ─→ detail placeholder,  Topic ─→ Topic (related)
```

**7 nodes · 19 edges · 0 isolated · 7/7 thumbnails.**

| Screen | Route (`NavKey`) | Module |
|--------|------------------|--------|
| For You _(start)_ | `ForYouNavKey` | `:feature:foryou:impl` |
| Saved | `BookmarksNavKey` | `:feature:bookmarks:impl` |
| Interests | `InterestsNavKey` | `:feature:interests:impl` |
| Search | `SearchNavKey` | `:feature:search:impl` |
| Topic | `TopicNavKey` | `:feature:topic:impl` |
| Settings _(dialog)_ | `SettingsNavKey` | `:feature:settings:api` |
| Interests detail pane | `InterestsDetailPlaceholderNavKey` | `:feature:interests:impl` |

## How navgraph is wired

End user setup is intentionally minimal: **applying the plugin auto wires its `compose-nav-graph-annotations` +
`compose-nav-graph-ksp` dependencies**, so a screen module declares the two plugins:

```kotlin
plugins {
    alias(libs.plugins.ksp)    // KSP, its version tracks your Kotlin version
    alias(libs.plugins.navgraph)   // auto adds compose-nav-graph-annotations + compose-nav-graph-ksp at the plugin's version
}
```

Then annotate the screen and its `@Preview`:

```kotlin
@NavGraphRoot                                  // start destination, on ForYou only
@NavDestination(route = ForYouNavKey::class)
@NavEdge(to = TopicNavKey::class, label = "Open topic")
@Composable
fun ForYouScreen(/* … */) { /* … */ }

@NavPreview(route = ForYouNavKey::class, primary = true)
@Preview
@Composable
fun ForYouScreenPopulatedFeed(/* … */) { /* … */ }
```

The `com.github.skydoves.navgraph` artifacts resolve from `mavenLocal()` (added to `settings.gradle.kts`); publish
them with `./gradlew publishToMavenLocal` from the [compose-nav-graph](https://github.com/skydoves/compose-nav-graph)
repo first.

Because each module's KSP emits its own `nav-graph.json`, `:app` keeps it out of the merged APK:

```kotlin
packaging { resources { excludes += "/nav-graph.json" } }
```

The IDE reads each module's graph from its build directory, not the APK, so this is safe.

## Compose HotSwan

This sample is also set up for **[Compose HotSwan](https://hotswan.dev)**: hot reload your Composables on
a running device or emulator without losing navigation state. Setup guide:
**[hotswan.dev/install](https://hotswan.dev/install)**.

- The compiler plugin (`com.github.skydoves.compose.hotswan.compiler` `1.3.4`) is applied on `:app` and
  propagates to every Compose module, so any screen hot reloads.
- The on device preview runner ships as a debug only dependency:

  ```kotlin
  debugImplementation(libs.hotswan.preview)
  ```

## Build & run

```bash
./gradlew :app:assembleDemoDebug    # demo flavor = local static data, no backend needed
```

Open the project in Android Studio with the navgraph IDE plugin installed to explore the navigation graph.

---

Original application: **[android/nowinandroid](https://github.com/android/nowinandroid)**. © The Android
Open Source Project, licensed under Apache 2.0. This fork adds only the Navigation 3 + HotSwan demo wiring.
