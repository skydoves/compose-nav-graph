/*
 * Designed and developed by 2026 skydoves (Jaewoong Eum)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.skydoves.navgraph.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/**
 * Configures the `navgraph { }` DSL. Today this drives the `.nav` baseline tasks (`navDump` / `navCheck`).
 *
 * ```
 * navgraph {
 *   baselineFile.set(layout.projectDirectory.file("nav/app.nav"))
 *   failOnNavChange.set(true)
 *   allowMissingBaseline.set(false)
 *   renderThumbnails.set(false) // structure-only (no render)
 * }
 * ```
 */
public abstract class NavGraphExtension {
  /** The committed `.nav` baseline. Default: `<projectDir>/nav/<module>.nav`. */
  public abstract val baselineFile: RegularFileProperty

  /** Whether `navCheck` fails the build when the graph drifts from the baseline (default `true`; `false` → warn). */
  public abstract val failOnNavChange: Property<Boolean>

  /** Whether a missing baseline is a skip instead of a failure (default `false`). */
  public abstract val allowMissingBaseline: Property<Boolean>

  /** Whether the `navLint` task is registered (default `true`). */
  public abstract val lintEnabled: Property<Boolean>

  /** Whether `navLint` fails the build on a finding (default `false`; `false` → warn). Turn it on in CI. */
  public abstract val failOnNavLint: Property<Boolean>

  /**
   * Whether `navLint` runs as part of `check` (default `false`).
   *
   * Off by default because lint reads the **aggregated** graph, so gating `check` on it makes `check` build every
   * dependency module's graph — including their thumbnail renders, which in turn narrows those modules' unit-test
   * tasks to the render test alone. `navCheck` stays in `check` precisely because it reads the render-free
   * manifest instead. Prefer running `navLint` as its own CI step; turn this on only when the build has no
   * dependency modules to drag in, or when that cost is acceptable.
   */
  public abstract val lintOnCheck: Property<Boolean>

  /**
   * `navLint` rule ids to skip: `no-start`, `multiple-starts`, `unreachable`, `unbound-route` (default empty).
   */
  public abstract val navLintDisabledRules: SetProperty<String>

  /**
   * Route FQNs `navLint` reports nothing for (default empty), for a destination left unwired on purpose — one
   * reached only by a deep link, or a screen still being built.
   */
  public abstract val navLintIgnoredRoutes: SetProperty<String>

  /**
   * Whether to **infer** transitions from navigation call sites (default `true`). KSP reads declarations only — it
   * cannot see inside `entry<Home> { … backStack.add(Feed) }` — so without this every transition has to be written
   * out as a `@NavEdge`. With it on, the plugin scans this module's Kotlin sources, resolves each call against the
   * routes already in the graph, and adds what it finds as `EdgeConfidence.INFERRED`: drawn dashed in the IDE and
   * in the exports, so an inferred transition is never mistaken for a declared one.
   *
   * A reference that does not resolve to a known route is dropped — inference never invents a destination — and an
   * explicit `@NavEdge` always wins over an inferred duplicate of the same transition.
   */
  public abstract val inferEdges: Property<Boolean>

  /**
   * Whether inferred transitions are written into the committed `.nav` baseline (default `false`).
   *
   * Off by default so turning [inferEdges] on never breaks an existing `navCheck`: the baseline keeps recording
   * exactly the `@NavEdge`s you declared, while the graph and the exports show the inferred ones too. Set `true` to
   * hold the *inferred* graph to review as well — then a transition disappearing from the code fails `navCheck`,
   * at the cost of one `navDump` when the heuristic's result changes.
   */
  public abstract val baselineIncludesInferred: Property<Boolean>

  /**
   * The method names [inferEdges] treats as "this navigates" (default `add`, `addAll`, `set`, `setAll`,
   * `navigate`, `replaceAll`, `replace`, `push`).
   *
   * Matching is by method name only, never by receiver, so both `backStack.add(Feed)` and a custom wrapper's
   * `navigator.add(Feed)` are found. Add to this only if your app navigates through a differently named method;
   * the route-set check is what keeps unrelated calls out, so a broader set is safe.
   */
  public abstract val inferNavCalls: SetProperty<String>

  /**
   * Whether to render `@NavPreview` thumbnails via the device-free **Layoutlib** renderer on Android (default
   * `true`). Set `false` for **structure-only** extraction — nodes + edges + args, no screenshots — when you only
   * want the graph shape (faster; nothing to render). The graph schema + IDE reader already handle thumbnail-less
   * nodes. KMP-without-Android is always structure-only regardless.
   */
  public abstract val renderThumbnails: Property<Boolean>

  /**
   * Which [RenderBackend] renders `@NavPreview` thumbnails (default [RenderBackend.AUTO]). The Robolectric
   * backends run on the Android unit-test classpath; the plugin auto-adds the `compose-nav-graph-testing` runtime +
   * Robolectric and sets `testOptions.unitTests.isIncludeAndroidResources`. No third-party screenshot library
   * is used — capture is `View.draw` under Robolectric's native graphics.
   */
  public abstract val renderBackend: Property<RenderBackend>

  /**
   * The fully-qualified name of the `android.app.Application` class the **Robolectric** render boots, e.g.
   * `"com.example.RenderApplication"` (default blank — the consumer's real Application from the merged manifest).
   * Robolectric runs the real `Application.onCreate`, which crashes the render when it initializes SDKs that
   * need a device or Play services (billing, push, analytics, …). Point this at a minimal test-only Application
   * (typically in the unit-test source set, doing at most DI setup) to render previews without that init. Emitted
   * as `@Config(application = …)` on the generated render test; sdk/qualifiers stay inherited from the base class.
   */
  public abstract val robolectricApplication: Property<String>

  /**
   * The Android **variant** to extract from, e.g. `"debug"` (default) or, for a flavored app/library,
   * `"demoDebug"`. Blank (the default) means *auto-detect* the first `…DebugKotlin` KSP variant — so a flavored
   * project usually just works without setting this. Set it only to pin a specific flavor's graph. Ignored for
   * Kotlin-Multiplatform modules (they use the common-metadata / android KSP pass).
   */
  public abstract val variant: Property<String>

  /**
   * Whether the plugin auto-adds its own `compose-nav-graph-annotations` (implementation) and `compose-nav-graph-ksp` (ksp) dependencies
   * at the plugin's own version, so a consumer only needs to apply the plugin (default `true`). The KSP plugin
   * (`com.google.devtools.ksp`) must still be applied — its version is tied to your Kotlin version, so navgraph
   * can't apply it for you. Set `false` to declare the two dependencies yourself.
   */
  public abstract val autoDependencies: Property<Boolean>

  /**
   * Whether this module **aggregates** the nav graphs of its dependency modules into its own
   * combined graph (default `true` — most apps are multi-module). navgraph's KSP runs per module,
   * so each feature module's graph only sees its own `@NavDestination`s; a cross-module
   * `@NavEdge` target shows as a no-preview stub. With aggregation on, the plugin merges every
   * dependency module's nav-graph + thumbnails with this module's own, re-uniting each stub
   * with the real node from the owning module. An umbrella (e.g. `:app`) that depends on every
   * feature gets the whole app's graph; a single-module app just gets its own. Set `false` to
   * restrict this module's graph to its own destinations. Plain-Android only (KMP uses its own).
   */
  public abstract val aggregate: Property<Boolean>

  /**
   * Whether to register the **Preview Gallery** tasks (default `true`). The gallery discovers EVERY `@Preview`
   * composable in the module — not only the `@NavPreview` ones — renders each to a thumbnail (reusing the same
   * Layoutlib + Robolectric engine as the nav graph) and exports a self-contained HTML grouped by package and
   * module. Its tasks (`generatePreviewGallery`, `exportPreviewGalleryHtml`) are on-demand only — never wired
   * into `generateNavGraph`/`check`, so they cost nothing unless run. Set `false` to not register them.
   */
  public abstract val galleryEnabled: Property<Boolean>

  /**
   * Which [RenderBackend] renders the preview gallery thumbnails (default [RenderBackend.AUTO]). Independent of
   * [renderBackend] (which drives the nav graph), so the graph and the larger gallery can use different backends.
   */
  public abstract val galleryRenderBackend: Property<RenderBackend>

  /**
   * Whether the preview gallery aggregates the galleries of this module's dependency modules into one combined
   * gallery (default `true`). Mirrors [aggregate]: an umbrella `:app` gets every module's previews grouped by
   * module; a single-module app just gets its own. Plain-Android only.
   */
  public abstract val galleryAggregate: Property<Boolean>
}

/** The backend that renders `@NavPreview` thumbnails. See [NavGraphExtension.renderBackend]. */
public enum class RenderBackend {
  /** Layoutlib device-free, then Robolectric for any preview Layoutlib failed to render. */
  AUTO,

  /** Device-free Layoutlib only — fast, but some Compose Multiplatform screens may not render. */
  LAYOUTLIB,

  /** Robolectric only — a full Android runtime that renders complex Compose Multiplatform screens. */
  ROBOLECTRIC,
}
