# Compose Navigation Graph - Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **`@Preview(locale = …)` support for thumbnails** ([#7](https://github.com/skydoves/compose-nav-graph/issues/7)): the KSP processor now captures the preview's locale qualifier (declared directly or via a multipreview meta-annotation) into the manifest, and both render backends apply it (Layoutlib through the renderer's preview params, Robolectric through a composition-scoped configuration-context override), so localized previews render with the same resources Android Studio shows.

### Fixed
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
