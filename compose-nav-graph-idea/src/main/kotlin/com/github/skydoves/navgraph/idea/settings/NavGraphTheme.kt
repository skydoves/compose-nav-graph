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

import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color

/**
 * Resolves a [NavGraphSettings] bean into the concrete `JBColor`/`BasicStroke`/`Font`/geometry the canvas
 * paints with. Built **fresh on every repaint** (it's cheap — a handful of object allocations), so changing a
 * setting and repainting is all that's needed for it to take effect.
 *
 * ### Theme resolution
 * Settings expose only the **light** half of each color; the dark half lives here as [DarkDefaults]:
 *  - [ThemeMode.FOLLOW_IDE] uses light-from-settings, dark-fixed, swapping with the IDE theme.
 *  - [ThemeMode.FORCE_LIGHT] pins both halves of the `JBColor` to the settings' light value.
 *  - [ThemeMode.FORCE_DARK] pins both halves to the fixed dark value.
 *
 * Colors not exposed as settings ([thumbBg], [argName], [argType], [muted], [title]) use a fixed light+dark
 * pair.
 */
internal class NavGraphTheme(private val settings: NavGraphSettings) {

  private val mode: ThemeMode = settings.themeMode.toEnumOr(ThemeMode.FOLLOW_IDE)

  /**
   * Builds a [JBColor] honoring [mode]: FOLLOW_IDE → `(light, dark)`; FORCE_LIGHT → `(light, light)`;
   * FORCE_DARK → `(dark, dark)`.
   */
  private fun themed(lightRgb: Int, dark: Color): JBColor {
    val light = Color(lightRgb)
    return when (mode) {
      ThemeMode.FOLLOW_IDE -> JBColor(light, dark)
      ThemeMode.FORCE_LIGHT -> JBColor(light, light)
      ThemeMode.FORCE_DARK -> JBColor(dark, dark)
    }
  }

  /** A fixed (non-customizable) color pair, still honoring [mode] for the force variants. */
  private fun themedFixed(light: Color, dark: Color): JBColor = when (mode) {
    ThemeMode.FOLLOW_IDE -> JBColor(light, dark)
    ThemeMode.FORCE_LIGHT -> JBColor(light, light)
    ThemeMode.FORCE_DARK -> JBColor(dark, dark)
  }

  // Colors (customizable light half + fixed dark half)
  val background: JBColor get() = themed(settings.backgroundRGB, DarkDefaults.BG)
  val nodeBg: JBColor get() = themed(settings.nodeFillRGB, DarkDefaults.NODE_BG)
  val accent: JBColor get() = themed(settings.accentColorRGB, DarkDefaults.ACCENT)
  val edge: JBColor get() = themed(settings.edgeColorRGB, DarkDefaults.EDGE)

  // Colors (fixed pairs, not exposed as settings)
  val thumbBg: JBColor get() = themedFixed(LightDefaults.THUMB_BG, DarkDefaults.THUMB_BG)
  val border: JBColor get() = themedFixed(LightDefaults.BORDER, DarkDefaults.BORDER)
  val title: JBColor get() = themedFixed(LightDefaults.TITLE, DarkDefaults.TITLE)
  val argName: JBColor get() = themedFixed(LightDefaults.ARG_NAME, DarkDefaults.ARG_NAME)
  val argType: JBColor get() = themedFixed(LightDefaults.ARG_TYPE, DarkDefaults.ARG_TYPE)
  val muted: JBColor get() = themedFixed(LightDefaults.MUTED, DarkDefaults.MUTED)

  // Strokes
  val edgeStroke: BasicStroke get() = BasicStroke(settings.edgeThickness)

  // Geometry
  val boxW: Int get() = settings.nodeWidth
  val arc: Double get() = settings.cornerRadius.toDouble()
  val colGap: Int get() = settings.columnGap
  val rowGap: Int get() = settings.rowGap

  // Behavioral flags / enums (pass-through, parsed once)
  val showThumbnail: Boolean get() = settings.showThumbnail
  val showArgs: Boolean get() = settings.showArgs
  val emphasizeStart: Boolean get() = settings.emphasizeStart
  val showEdgeLabels: Boolean get() = settings.showEdgeLabels
  val curveStyle: CurveStyle get() = settings.curveStyle.toEnumOr(CurveStyle.CURVED)
  val direction: Direction get() = settings.direction.toEnumOr(Direction.LEFT_RIGHT)

  companion object {
    /** **Light** halves of the colors that are NOT exposed as settings. */
    private object LightDefaults {
      val THUMB_BG = Color(0xF1, 0xF3, 0xF5)
      val BORDER = Color(0xD6, 0xD9, 0xDE)
      val TITLE = Color(0x20, 0x21, 0x24)
      val ARG_NAME = Color(0x1A, 0x73, 0xE8)
      val ARG_TYPE = Color(0x18, 0x80, 0x38)
      val MUTED = Color(0x80, 0x86, 0x8B)
    }

    /** **Dark** halves of every color — the fixed dark fallback. */
    private object DarkDefaults {
      val BG = Color(0x2B, 0x2D, 0x30)
      val NODE_BG = Color(0x3C, 0x3F, 0x41)
      val THUMB_BG = Color(0x31, 0x34, 0x38)
      val BORDER = Color(0x4E, 0x51, 0x57)
      val ACCENT = Color(0x9A, 0x82, 0xDB)
      val TITLE = Color(0xDF, 0xE1, 0xE5)
      val ARG_NAME = Color(0x6B, 0xA5, 0xE7)
      val ARG_TYPE = Color(0x6F, 0xBF, 0x73)
      val MUTED = Color(0x8C, 0x8C, 0x8C)
      val EDGE = Color(0x5A, 0x9B, 0xE6)
    }
  }
}
