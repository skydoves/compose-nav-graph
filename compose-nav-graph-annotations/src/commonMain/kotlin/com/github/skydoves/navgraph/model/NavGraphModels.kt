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
@file:OptIn(ExperimentalSerializationApi::class)

package com.github.skydoves.navgraph.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The contract artifact: the companion (`compose-nav-graph-ksp` + `compose-nav-graph-gradle`) emits this as
 * `build/navgraph/nav-graph.json`, and the IntelliJ plugin (`compose-nav-graph-idea`) reads it. Both sides
 * depend on `compose-nav-graph-annotations` and share these exact `@Serializable` types — no hand-parsing.
 *
 * **Forward/backward compatibility (the plugin ships separately and may lag the library):** the
 * reader's `Json` MUST be configured `ignoreUnknownKeys = true` + `coerceInputValues = true` so a
 * newer manifest (extra fields, future [EdgeConfidence] constants) does not crash an older plugin.
 * [navVersion] and [schemaVersion] are `@EncodeDefault(ALWAYS)` so they are ALWAYS present for the
 * reader's version handshake (without that, defaults are omitted and a reader fills in its OWN
 * default, defeating the check). Bump [schemaVersion] only for an *incompatible* change (a removed/
 * repurposed field); additive fields with defaults are compatible. The plugin should warn (not
 * crash) when `schemaVersion` exceeds the version it was built against.
 *
 * @property navVersion the navigation model this graph was extracted from (`"navgraph"`).
 * @property schemaVersion incremented only on incompatible schema changes; see above.
 * @property nodes one per destination route class.
 * @property edges navigation transitions between nodes (keyed by [NavNode.id]).
 */
@Serializable
public data class NavGraph(
  @EncodeDefault(EncodeDefault.Mode.ALWAYS) val navVersion: String = "navgraph",
  @EncodeDefault(EncodeDefault.Mode.ALWAYS) val schemaVersion: Int = SCHEMA_VERSION,
  val nodes: List<NavNode> = emptyList(),
  val edges: List<NavGraphEdge> = emptyList(),
) {
  public companion object {
    /** Current [NavGraph.schemaVersion]. */
    public const val SCHEMA_VERSION: Int = 1
  }
}

/**
 * One destination node.
 *
 * @property id the canonical, unique identifier: the route class's fully-qualified name. This is
 *   the key [NavGraphEdge.from]/[NavGraphEdge.to] reference — simple names collide across modules,
 *   so they are never used as keys.
 * @property route the display label: the route class's simple name (e.g. `"Profile"`). Not unique.
 * @property module the producing module (e.g. a Gradle path `:feature:profile`); null for a
 *   single-module project. Used to namespace thumbnails and resolve sources when merging modules.
 * @property clickTargetFqn the click target — the FQN of the `@NavDestination` composable (null if
 *   no `@NavDestination` marks this route's screen).
 * @property sourceFile path of the file declaring the click-target composable (a fast-path hint; the
 *   plugin should fall back to resolving [clickTargetFqn] when this path doesn't exist on its host).
 * @property sourceLine 1-based line of the click-target composable in [sourceFile]; null if unknown.
 * @property args the destination's typed arguments (its serializable properties).
 * @property previews every `@Preview` linked to this route via `@NavPreview`; the plugin renders the
 *   one with [NavPreviewRef.primary] (falling back to the first).
 * @property start whether this is the start destination (`@NavGraphRoot`).
 */
@Serializable
public data class NavNode(
  val id: String,
  val route: String,
  val module: String? = null,
  val clickTargetFqn: String? = null,
  val sourceFile: String? = null,
  val sourceLine: Int? = null,
  val args: List<NavArg> = emptyList(),
  val previews: List<NavPreviewRef> = emptyList(),
  val start: Boolean = false,
)

/**
 * One navigation argument, derived from a route class's serializable properties via a KSP declaration
 * read that honors `@Transient` (excluded) and `@SerialName` (renamed).
 * navgraph has no `NavType`; the property's serializable type IS the contract.
 *
 * @property name the argument name (its `@SerialName`, if any, else the property name).
 * @property type the rendered Kotlin type's fully-qualified name WITHOUT type arguments, e.g.
 *   `"kotlin.String"`, `"kotlin.Int"`, `"com.app.Section"`. (Generic arguments go in [typeArguments].)
 * @property typeArguments fully-qualified generic type arguments, e.g. `["com.app.Tag"]` for
 *   `List<Tag>`; empty for non-generic types.
 * @property nullable whether the argument type is nullable.
 * @property optional whether a default value exists (the value itself is not statically recoverable).
 * @property enum whether [type] is an enum class (the plugin may render enum args differently).
 */
@Serializable
public data class NavArg(
  val name: String,
  val type: String,
  val typeArguments: List<String> = emptyList(),
  val nullable: Boolean = false,
  val optional: Boolean = false,
  val enum: Boolean = false,
)

/**
 * A `@Preview` linked to a route via `@NavPreview`, plus its rendered thumbnail.
 *
 * @property previewName the `@Preview` function's name.
 * @property previewFqn the function's fully-qualified (Kotlin) name — the stable join key the render step
 *   uses to attach the rendered PNG (simple names collide across files).
 * @property previewMethodFqn the function's **JVM** method FQN as the standalone Layoutlib renderer expects
 *   it: a top-level `@Preview` is hosted on its file facade class, so this is `<pkg>.<File>Kt.<fn>` (e.g.
 *   `com.x.HomeScreenKt.HomeScreenPreview`), or `<EnclosingClassFqn>.<fn>` for a member preview. Distinct
 *   from [previewFqn] (which is `<pkg>.<fn>`, missing the `…Kt` facade). Only KSP can derive it (it needs
 *   the containing file name), so it is recorded here for the `layoutlib` backend.
 * @property thumbnail relative path to the rendered PNG (e.g. `"thumbs/Profile.png"`); null until
 *   `compose-nav-graph-gradle` renders it.
 * @property previewParameters the function's `@PreviewParameter` arguments (empty for a param-less preview).
 *   The device-free renderer needs each provider to instantiate the composable's sample value — without them a
 *   parameterized `@NavPreview` (very common in real apps) can't render and yields a blank/failed thumbnail.
 * @property primary whether this is the canonical thumbnail for the route (`@NavPreview.primary`).
 * @property locale the `@Preview(locale = …)` resource qualifier (e.g. `"ko"`, `"fr-rFR"`) the function's
 *   `@Preview` declares, directly or via a multipreview meta-annotation; null when none is declared. Both
 *   render backends apply it so the thumbnail matches what Android Studio's preview shows. Additive since
 *   schema 1 (absent in older manifests → null).
 */
@Serializable
public data class NavPreviewRef(
  val previewName: String,
  val previewFqn: String? = null,
  val previewMethodFqn: String? = null,
  val previewParameters: List<NavPreviewParam> = emptyList(),
  val thumbnail: String? = null,
  val primary: Boolean = false,
  val locale: String? = null,
)

/**
 * A `@PreviewParameter` argument of a `@NavPreview` function: the [provider] class FQN supplies the sample
 * value the device-free renderer instantiates the composable with (the first value — thumbnails render one).
 *
 * @property name the parameter's name.
 * @property provider the `PreviewParameterProvider` subclass FQN passed to `@PreviewParameter(provider = …)`.
 */
@Serializable
public data class NavPreviewParam(val name: String, val provider: String)

/**
 * One transition between destinations. [from]/[to] are [NavNode.id]s (fully-qualified route names).
 *
 * @property from source node id (qualified route name).
 * @property to destination node id (qualified route name).
 * @property args names of arguments passed across this transition (best-effort; may be empty).
 * @property label optional human-readable label (e.g. the triggering action).
 * @property confidence how this edge was determined.
 */
@Serializable
public data class NavGraphEdge(
  val from: String,
  val to: String,
  val args: List<String> = emptyList(),
  val label: String? = null,
  val confidence: EdgeConfidence = EdgeConfidence.ANNOTATED,
)

/**
 * How a [NavGraphEdge] was determined. Currently only [ANNOTATED] is emitted. Adding a constant is a
 * forward-incompatible change for older readers unless they use `coerceInputValues = true`
 * (see [NavGraph]); bump [NavGraph.schemaVersion] when constants change.
 */
@Serializable
public enum class EdgeConfidence {
  /** From an explicit `@NavEdge` annotation. */
  ANNOTATED,

  /** Statically inferred from `backStack.add(...)` call sites. */
  INFERRED,

  /** Observed at runtime from the live back stack (future). */
  RUNTIME,
}
