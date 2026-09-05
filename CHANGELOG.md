# Compose Navigation Graph - Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Transitions inferred from your navigation call sites**: KSP reads declarations only — it cannot see inside `entry<Home> { … backStack.add(Feed) }` — so until now every transition had to be hand-written as a `@NavEdge`. The new `inferNavEdges` task scans the module's Kotlin sources for navigation calls inside an `entry<Route> { }` / `composable<Route> { }` block or a `@NavDestination` composable body, resolves each target against the routes already in the graph, and adds what it finds as `EdgeConfidence.INFERRED` — drawn **dashed** in the IDE, the HTML/PNG exports, and Mermaid so it is never mistaken for a declared edge. Matching is by method name rather than receiver, so a custom wrapper (`navigator.add(Detail(id))`) works with only the plugin applied. A reference that does not resolve to a known route is dropped, so inference can never invent a destination, and an explicit `@NavEdge` always wins over an inferred duplicate. On by default (`navgraph { inferEdges }`); inferred transitions stay **out of the `.nav` baseline** unless `navgraph { baselineIncludesInferred = true }`, so upgrading does not move a single line of a committed baseline.
- **Visual `navDiff` for pull requests** (`exportNavDiffHtml` / `exportNavDiffImage`): renders the graph coloured against the committed `.nav` baseline — added in green, removed as a red dashed ghost node that keeps the arguments it used to declare, changed in amber — plus a legend and counts. It compares the exact lines `navCheck` compares, so the picture and the build can never disagree. `-Pnavgraph.diff.base=<path.nav>` compares against a different baseline.
- **Mermaid export** (`exportNavGraphMermaid`): writes `build/navgraph/nav-graph.mmd`, a `flowchart` that GitHub markdown renders natively, so the navigation graph can live in a README and be regenerated on every build. Declared transitions draw `-->`, inferred ones `-.->`. Options: `-Pnavgraph.export.direction=LR|TB|RL|BT`, `-Pnavgraph.export.mermaid.markdown=true` (wraps it in a fence), and `-Pnavgraph.export.mermaid.out=<path>`.
- **IDE: "Show inferred transitions"** setting (Settings → NavGraph → Edges) to hide the dashed edges and see only what `@NavEdge` declares. Dashed rendering needs this release of the IDE plugin; 0.2.1 reads the same manifest but draws inferred transitions solid.

## [0.2.1] - 2026-07-04

### Fixed
- **Preview thumbnails render at the `@Preview` size regardless of the app's AGP R-class form** ([#13](https://github.com/skydoves/compose-nav-graph/issues/13), [#24](https://github.com/skydoves/compose-nav-graph/pull/24)): every ComposeView-backed preview loads `androidx.customview.poolingcontainer.R$id` at render, and that class ships only in the app's AAPT2-linked R.jar. The render classpath matched that jar under `compile_and_runtime_r_class_jar` only, so an app whose linked R lives under `compile_and_runtime_not_namespaced_r_class_jar` (a different AGP config or version) lost it, and every preview fell back to a fixed portrait render with the `@Preview` size ignored. Both forms are now matched, so device-free thumbnails render correctly again.

## [0.2.0] - 2026-06-24

### Added
- **Scope the graph by feature package** ([#19](https://github.com/skydoves/compose-nav-graph/issues/19)): a single-module app that organizes its screens by feature package can now view and export the navigation graph one feature at a time. The Gradle export tasks accept `-Pnavgraph.export.package=<prefix>` to export only the destinations under a package (matched by route or screen FQN, keeping internal edges and dropping cross-feature ones), and the IDE tool window adds a "Feature:" selector (auto-discovered from the package layout) that slices the graph and scopes the export to match what is on screen.

### Fixed
- **KMP feature modules render thumbnails via the transitively reachable app** ([#20](https://github.com/skydoves/compose-nav-graph/issues/20)): a feature module reached only indirectly by the `com.android.application` module (through one or more intermediate modules) now finds that app for its render classpath, so its shared previews are rendered instead of skipped.

## [0.1.2] - 2026-06-17

### Added
- **`@Preview` size in thumbnails** ([#13](https://github.com/skydoves/compose-nav-graph/issues/13)): the KSP processor now captures `@Preview`'s `widthDp`, `heightDp`, and `device` (declared directly or via a multipreview meta-annotation), and the Layoutlib renderer renders each thumbnail at that size, so landscape, tablet, and custom-sized previews match what Android Studio shows.

### Fixed
- **Kotlin Multiplatform thumbnails render device free** ([#10](https://github.com/skydoves/compose-nav-graph/issues/10)): the plugin auto-adds the consumer's own Compose `ui-tooling` (which provides `ComposeViewAdapter`) to the KMP Android render classpath, so shared previews render instead of a "ComposeViewAdapter" placeholder. The renderer also now treats a missing `ComposeViewAdapter` as a render failure (reading the renderer's `missingClasses`) rather than accepting the placeholder as a successful thumbnail.
- **IDE: full module path in the Project selector** ([#15](https://github.com/skydoves/compose-nav-graph/issues/15)): nested feature modules that share a final segment (e.g. `feature:name:impl` and `feature:name:sample`) now show their Gradle path instead of an ambiguous `impl` / `sample`.
- **IDE: nested-module discovery** ([#14](https://github.com/skydoves/compose-nav-graph/issues/14)): when the Gradle model is unavailable, the tool window's fallback scan now walks nested modules instead of only the project root's direct children.

## [0.1.1] - 2026-06-13

### Added
- **`@Preview(locale = …)` support for thumbnails** ([#7](https://github.com/skydoves/compose-nav-graph/issues/7)): the KSP processor now captures the preview's locale qualifier (declared directly or via a multipreview meta-annotation) into the manifest, and both render backends apply it (Layoutlib through the renderer's preview params, Robolectric through a composition-scoped configuration-context override), so localized previews render with the same resources Android Studio shows.
- **`navgraph { robolectricApplication }`** ([#6](https://github.com/skydoves/compose-nav-graph/pull/6), thanks [@pesjak](https://github.com/pesjak)): point the Robolectric render at a minimal test-only `Application` (emitted as `@Config(application = …)` on the generated render test), so apps that initialize device-backed SDKs in `Application.onCreate` can render thumbnails.

### Fixed
- **Gradle configuration cache compatibility** ([#4](https://github.com/skydoves/compose-nav-graph/pull/4), thanks [@pesjak](https://github.com/pesjak)): the render wiring no longer captures `TaskProvider`s in the unit-test task's `onlyIf` spec, so `generateNavGraph` and the export tasks run with the configuration cache enabled (no more `--no-configuration-cache`).
- **Single-module Compose Multiplatform apps** ([#5](https://github.com/skydoves/compose-nav-graph/pull/5), thanks [@pesjak](https://github.com/pesjak)): a module that is both KMP and `com.android.application` (the KMP wizard default) now renders thumbnails from its own linked resources instead of skipping them.
- The generated `navgraph.version` resource (which pins the auto-wired `compose-nav-graph-annotations` / `compose-nav-graph-ksp` / `compose-nav-graph-testing` versions) is regenerated when `VERSION_NAME` changes instead of staying stale.

## [0.1.0] - 2026-06-01

### Added
- **Navigation graph annotations** (`compose-nav-graph-annotations`): `@NavDestination(route)` marks the top level composable that renders a destination (the click target), `@NavEdge(to, from, label)` declares a navigation transition between routes (repeatable), `@NavPreview(route, primary)` links a `@Preview` to the route it depicts so its render becomes the node thumbnail, and `@NavGraphRoot(route)` marks the start destination. Multiplatform (`commonMain`) and refactor safe.
- **Gradle plugin** (`com.github.skydoves.navgraph`) with a KSP processor (`compose-nav-graph-ksp`) that **statically extracts the nav graph** (nodes, typed arguments, and `@NavEdge` transitions) from annotations at compile time, with no runtime reflection on your navigation code.
- **`generateNavGraph` task**: extracts the graph, renders thumbnails (on Android), merges every module's contribution, and writes `build/navgraph/nav-graph.json` (plus per node thumbnail PNGs).
- **Device free Layoutlib thumbnails**: each `@NavPreview` screen is rendered to a PNG through the Android **Layoutlib** / `compose-preview-renderer` pipeline, with **no emulator and no connected device** required.
- **Kotlin Multiplatform and multi module support**: the graph is extracted per module and merged across the whole app; KMP modules use the common metadata / Android KSP pass and degrade gracefully to structure only (thumbnail-less) extraction where rendering isn't available.
- **IntelliJ / Android Studio plugin** (`compose-nav-graph-idea`): a **NavGraph Graph** tool window that displays the whole app's flow graph (merged across modules) with screen thumbnails, typed UML style argument rows, double click to source, and a drag to connect "add transition" gesture that writes a `@NavEdge` back into your code. Available on the JetBrains Marketplace.
- **`.nav` baseline via `navDump` / `navCheck`**: a committed, human readable `.nav` snapshot of your navigation graph (modeled on `apiDump` / `apiCheck`) so navigation changes are reviewable in pull requests; `navCheck` is wired into the `check` task and fails the build on unreviewed drift.
- **`exportNavGraphHtml` / `exportNavGraphImage` tasks**: render the graph to a self contained interactive HTML page (`nav-graph.html`) or a static PNG (`nav-graph.png`) for docs, PRs, and design reviews.
- **Auto dependency wiring**: applying the Gradle plugin automatically adds `compose-nav-graph-annotations` and `compose-nav-graph-ksp` at the matching version (`navgraph { autoDependencies = false }` to opt out), so a consumer only needs to apply the plugin (and KSP).
- **`navgraph { }` DSL**: `renderThumbnails`, `variant`, `autoDependencies`, `baselineFile`, `failOnNavChange`, and `allowMissingBaseline` configuration options.

## Legend

- **Added** - New features
- **Changed** - Changes in existing functionality
- **Deprecated** - Soon-to-be removed features
- **Removed** - Removed features
- **Fixed** - Bug fixes
- **Improved** - Enhancements to existing features
- **Security** - Security-related changes
- **Breaking Changes** - Breaking changes requiring migration

## Links

- [GitHub Repository](https://github.com/skydoves/compose-nav-graph)
- [Issue Tracker](https://github.com/skydoves/compose-nav-graph/issues)
- [Documentation](https://github.com/skydoves/compose-nav-graph/blob/main/README.md)
