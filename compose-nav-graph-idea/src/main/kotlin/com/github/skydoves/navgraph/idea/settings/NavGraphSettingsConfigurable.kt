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

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColorPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.awt.Color
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import kotlin.reflect.KMutableProperty0

/**
 * Settings UI for the NavGraph Graph tool window — appears in **Settings → Tools → NavGraph Graph**.
 *
 * Bound fields (booleans, ints, enums, strings) bind directly with the Kotlin UI DSL. Two field kinds have no
 * stock DSL binding and are synced manually in [reset]/[isModified]/[apply]:
 *  - **Colors** → a [ColorPanel] cell (the settings store the packed-RGB **light** value).
 *  - **Floats** → a [JSpinner] over a [SpinnerNumberModel], so the step/precision stays explicit.
 *
 * On [apply] we also publish [NavGraphSettingsListener.TOPIC] so an open tool window repaints live.
 */
internal class NavGraphSettingsConfigurable(private val project: Project) :
  BoundConfigurable("NavGraph Graph") {

  private val settings: NavGraphSettings = NavGraphSettings.getInstance(project)

  // Color pickers (manual sync)
  private lateinit var edgeColorPanel: ColorPanel
  private lateinit var accentColorPanel: ColorPanel
  private lateinit var nodeFillPanel: ColorPanel
  private lateinit var backgroundPanel: ColorPanel

  /** (picker, current packed-RGB getter, packed-RGB setter) for every color, to drive reset/isModified/apply. */
  private val colorBindings: List<ColorBinding> by lazy {
    listOf(
      ColorBinding(edgeColorPanel, { settings.edgeColorRGB }, { settings.edgeColorRGB = it }),
      ColorBinding(accentColorPanel, { settings.accentColorRGB }, { settings.accentColorRGB = it }),
      ColorBinding(nodeFillPanel, { settings.nodeFillRGB }, { settings.nodeFillRGB = it }),
      ColorBinding(backgroundPanel, { settings.backgroundRGB }, { settings.backgroundRGB = it }),
    )
  }

  // Float spinners (manual sync)
  private lateinit var edgeThicknessSpinner: JSpinner
  private lateinit var cornerRadiusSpinner: JSpinner

  /** (spinner, current-value getter, setter) for every float field. */
  private val floatBindings: List<FloatBinding> by lazy {
    listOf(
      FloatBinding(edgeThicknessSpinner, {
        settings.edgeThickness
      }, { settings.edgeThickness = it }),
      FloatBinding(cornerRadiusSpinner, { settings.cornerRadius }, { settings.cornerRadius = it }),
    )
  }

  override fun createPanel(): DialogPanel = panel {
    group("Edges") {
      row("Edge color:") {
        edgeColorPanel = ColorPanel()
        cell(edgeColorPanel).comment("Color of navigation arrows (light theme value)")
      }
      row("Edge thickness:") {
        edgeThicknessSpinner =
          floatSpinner(settings.edgeThickness, min = 0.5, max = 6.0, step = 0.5)
        cell(edgeThicknessSpinner).comment("Stroke width of edges, in px")
      }
      row {
        checkBox("Show edge labels")
          .bindSelected(settings::showEdgeLabels)
          .comment("Draw the optional transition label on labeled edges")
      }
      enumRow("Curve style:", settings::curveStyle, CurveStyle.CURVED) {
        when (it) {
          CurveStyle.CURVED -> "Curved (Bézier)"
          CurveStyle.STRAIGHT -> "Straight"
        }
      }.comment("Curved = smooth cubic Bézier; Straight = a direct segment")
    }

    group("Nodes") {
      row("Node width:") {
        spinner(160..400, 10)
          .bindIntValue(settings::nodeWidth)
          .comment("Width of each screen box, in px")
      }
      row("Corner radius:") {
        cornerRadiusSpinner = floatSpinner(settings.cornerRadius, min = 0.0, max = 24.0, step = 1.0)
        cell(cornerRadiusSpinner).comment("Roundness of node corners, in px")
      }
      row("Accent color:") {
        accentColorPanel = ColorPanel()
        cell(
          accentColorPanel,
        ).comment("Border color of the start / hovered node (light theme value)")
      }
      row("Node fill color:") {
        nodeFillPanel = ColorPanel()
        cell(nodeFillPanel).comment("Background of each node box (light theme value)")
      }
      row {
        checkBox("Show thumbnail")
          .bindSelected(settings::showThumbnail)
          .comment("Render the rendered @Preview image region of each node")
      }
      row {
        checkBox("Show arguments")
          .bindSelected(settings::showArgs)
          .comment("Render the typed argument rows below each node's route")
      }
      row {
        checkBox("Emphasize start destination")
          .bindSelected(settings::emphasizeStart)
          .comment("Give the start node the accent border and a ★ glyph")
      }
    }

    group("Layout") {
      row("Column gap:") {
        spinner(40..200, 5)
          .bindIntValue(settings::columnGap)
          .comment("Horizontal spacing between depth columns, in px")
      }
      row("Row gap:") {
        spinner(8..80, 2)
          .bindIntValue(settings::rowGap)
          .comment("Vertical spacing between stacked nodes, in px")
      }
      enumRow("Direction:", settings::direction, Direction.LEFT_RIGHT) {
        when (it) {
          Direction.LEFT_RIGHT -> "Left to right"
          Direction.TOP_BOTTOM -> "Top to bottom"
        }
      }.comment("Axis the graph flows along")
      enumRow("Auto-fit:", settings::autoFit, AutoFit.FIRST_LOAD) {
        when (it) {
          AutoFit.FIRST_LOAD -> "On first load"
          AutoFit.EVERY_REFRESH -> "On every refresh"
          AutoFit.NEVER -> "Never"
        }
      }.comment("When the view auto-zooms to frame the whole graph")
    }

    group("Theme") {
      enumRow("Theme mode:", settings::themeMode, ThemeMode.FOLLOW_IDE) {
        when (it) {
          ThemeMode.FOLLOW_IDE -> "Follow IDE"
          ThemeMode.FORCE_LIGHT -> "Always light"
          ThemeMode.FORCE_DARK -> "Always dark"
        }
      }.comment("Follow IDE swaps light/dark with the IDE theme; the others pin it")
      row("Background color:") {
        backgroundPanel = ColorPanel()
        cell(backgroundPanel).comment("Canvas background (light theme value)")
      }
    }

    group("Behavior") {
      enumRow("Refresh action:", settings::refreshMode, RefreshMode.RUN_GRADLE) {
        when (it) {
          RefreshMode.RUN_GRADLE -> "Run generateNavGraph"
          RefreshMode.READ_EXISTING -> "Reload existing files"
        }
      }.comment("Whether Refresh regenerates the graph via Gradle or just reloads the last output")
      enumRow("Double-click:", settings::doubleClickAction, DoubleClickAction.NAVIGATE) {
        when (it) {
          DoubleClickAction.NAVIGATE -> "Navigate to source"
          DoubleClickAction.NONE -> "Do nothing"
        }
      }.comment("What double-clicking a node does")
    }

    group("Export") {
      row("Default export device:") {
        textField()
          .bindText(settings::defaultExportDeviceLabel)
          .align(AlignX.FILL)
          .comment("Device label for HTML export; empty = Auto (use the device the canvas shows)")
      }
      row("Export output directory:") {
        textField()
          .bindText(settings::exportOutputDir)
          .align(AlignX.FILL)
          .comment("Directory for the exported HTML; empty = project base path")
      }
      row("Export file name:") {
        textField()
          .bindText(settings::exportFileName)
          .align(AlignX.FILL)
          .comment("File name of the exported HTML")
      }
    }
  }

  override fun reset() {
    super.reset()
    colorBindings.forEach { it.panel.selectedColor = Color(it.get()) }
    floatBindings.forEach { it.spinner.value = it.get().toDouble() }
  }

  override fun isModified(): Boolean {
    if (super.isModified()) return true
    if (colorBindings.any {
        it.panel.selectedColor?.rgbLow() != (it.get() and 0xFFFFFF)
      }
    ) {
      return true
    }
    if (floatBindings.any { it.spinner.floatValue() != it.get() }) return true
    return false
  }

  override fun apply() {
    super.apply()
    colorBindings.forEach { b -> b.panel.selectedColor?.let { b.set(it.rgbLow()) } }
    floatBindings.forEach { it.set(it.spinner.floatValue()) }
    project.messageBus.syncPublisher(NavGraphSettingsListener.TOPIC).settingsChanged()
  }

  /** Holds a [ColorPanel] together with the packed-RGB getter/setter it syncs with. */
  private class ColorBinding(val panel: ColorPanel, val get: () -> Int, val set: (Int) -> Unit)

  /** Holds a float [JSpinner] together with the getter/setter it syncs with. */
  private class FloatBinding(val spinner: JSpinner, val get: () -> Float, val set: (Float) -> Unit)
}

/**
 * Adds a `"label:" [combo]` row whose combo lists every constant of enum [E] (rendered via [render]) and is
 * bound to a `String` settings property holding the enum's [Enum.name]. A stale/garbage persisted value falls
 * back to [default] (via [toEnumOr]). Returns the [Row] so the caller can chain `.comment(...)`.
 */
private inline fun <reified E : Enum<E>> Panel.enumRow(
  label: String,
  property: KMutableProperty0<String>,
  default: E,
  crossinline render: (E) -> String,
): Row = row(label) {
  comboBox(
    enumValues<E>().toList(),
    SimpleListCellRenderer.create("") { value: E? -> value?.let(render) ?: "" },
  ).bindItem(
    { property.get().toEnumOr(default) },
    { property.set((it ?: default).name) },
  )
}

/** Builds a float spinner with the given range/step, seeded at [value]. */
private fun floatSpinner(value: Float, min: Double, max: Double, step: Double): JSpinner =
  JSpinner(SpinnerNumberModel(value.toDouble(), min, max, step))

/** A [ColorPanel] only stores RGB (no alpha); compare/store on the low 24 bits to match the packed settings. */
private fun Color.rgbLow(): Int = rgb and 0xFFFFFF

private fun JSpinner.floatValue(): Float = (value as Number).toFloat()
