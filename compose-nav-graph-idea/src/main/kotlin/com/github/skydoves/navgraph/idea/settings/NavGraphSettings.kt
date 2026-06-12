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
package com.github.skydoves.navgraph.idea.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Project-level persistent settings for the NavGraph Graph tool window.
 *
 * Stored per-project in the workspace file so it never pollutes a shared `.idea` checkout. The bean holds
 * only primitives: colors are packed `Int` RGB (the **light** value — the dark half is supplied by
 * [NavGraphTheme]), enums are persisted as their `name` `String`, and everything else is an
 * `Int`/`Float`/`Boolean`.
 */
@Service(Service.Level.PROJECT)
@State(
  name = "NavGraphSettings",
  storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
internal class NavGraphSettings : PersistentStateComponent<NavGraphSettings> {

  // Edges
  /** Edge line + arrowhead color, packed RGB (light theme). */
  var edgeColorRGB: Int = 0x3377D6

  /** Edge stroke width in px. */
  var edgeThickness: Float = 1.5f

  /** Whether to draw the optional per-edge label. */
  var showEdgeLabels: Boolean = true

  /** Edge curve style; default [CurveStyle.CURVED]. */
  var curveStyle: String = CurveStyle.CURVED.name

  // Nodes

  /** Node box width in px. */
  var nodeWidth: Int = 240

  /** Node corner radius in px. */
  var cornerRadius: Float = 14f

  /** Accent color used for the start node + hovered node, packed RGB (light theme). */
  var accentColorRGB: Int = 0x6750A4

  /** Node body fill color, packed RGB (light theme). */
  var nodeFillRGB: Int = 0xFFFFFF

  /** Whether to render the preview thumbnail region. */
  var showThumbnail: Boolean = true

  /** Whether to render the typed UML argument rows. */
  var showArgs: Boolean = true

  /** Whether the start node gets the accent border + ★ glyph. */
  var emphasizeStart: Boolean = true

  // Layout

  /** Horizontal gap between columns in px. */
  var columnGap: Int = 90

  /** Vertical gap between stacked nodes in px. */
  var rowGap: Int = 26

  /** Graph flow direction; default [Direction.LEFT_RIGHT]. */
  var direction: String = Direction.LEFT_RIGHT.name

  /** When the canvas auto-fits to the viewport; default [AutoFit.FIRST_LOAD]. */
  var autoFit: String = AutoFit.FIRST_LOAD.name

  // Theme

  /** How the palette resolves light vs. dark; default [ThemeMode.FOLLOW_IDE]. */
  var themeMode: String = ThemeMode.FOLLOW_IDE.name

  /** Canvas background color, packed RGB (light theme). */
  var backgroundRGB: Int = 0xFBFCFE

  // Behavior

  /** What Refresh does; default [RefreshMode.RUN_GRADLE]. */
  var refreshMode: String = RefreshMode.RUN_GRADLE.name

  /** What a node double-click does; default [DoubleClickAction.NAVIGATE]. */
  var doubleClickAction: String = DoubleClickAction.NAVIGATE.name

  // Export

  /** Device label used for HTML export; `""` = "Auto" (use what the canvas currently shows). */
  var defaultExportDeviceLabel: String = ""

  /** Output directory for HTML export; `""` = the project base path. */
  var exportOutputDir: String = ""

  /** Output file name for HTML export. */
  var exportFileName: String = "nav-graph.html"

  // Multi-app

  /**
   * Id (the app/root module's Gradle project path) of the last-selected navigation scope, when a repo holds
   * more than one independent app. `""` = none remembered → the tool window picks the first scope. Stale ids
   * (module removed/renamed) fall back silently to the first scope.
   */
  var selectedScopeId: String = ""

  override fun getState(): NavGraphSettings = this

  override fun loadState(state: NavGraphSettings) {
    XmlSerializerUtil.copyBean(state, this)
  }

  companion object {
    fun getInstance(project: Project): NavGraphSettings = project.service()
  }
}

/** Edge rendering style. [CURVED] = cubic Bézier; [STRAIGHT] = a single straight segment. */
internal enum class CurveStyle { CURVED, STRAIGHT }

/** Graph flow axis. [LEFT_RIGHT] = depth grows rightward; [TOP_BOTTOM] = depth grows downward. */
internal enum class Direction { LEFT_RIGHT, TOP_BOTTOM }

/** When the canvas auto-fits: [FIRST_LOAD], [EVERY_REFRESH], or [NEVER]. */
internal enum class AutoFit { FIRST_LOAD, EVERY_REFRESH, NEVER }

/** Palette resolution. [FOLLOW_IDE] swaps with the IDE theme; [FORCE_LIGHT]/[FORCE_DARK] pin it. */
internal enum class ThemeMode { FOLLOW_IDE, FORCE_LIGHT, FORCE_DARK }

/** What Refresh does. [RUN_GRADLE] re-runs `generateNavGraph`; [READ_EXISTING] only reloads files. */
internal enum class RefreshMode { RUN_GRADLE, READ_EXISTING }

/** What a node double-click does. [NAVIGATE] jumps to source; [NONE] disables it. */
internal enum class DoubleClickAction { NAVIGATE, NONE }

/**
 * Parses this string as enum [E], falling back to [default] if it is blank or not a valid constant — so a
 * stale/garbage persisted value (e.g. a renamed enum constant) can never throw at render time.
 */
internal inline fun <reified E : Enum<E>> String.toEnumOr(default: E): E =
  enumValues<E>().firstOrNull { it.name == this } ?: default
