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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color

/**
 * Locks the zero-visual-regression contract: every default in [NavGraphSettings] must equal the
 * constant the canvas used before settings existed, and [NavGraphTheme] must keep the original
 * dark halves so an un-customized project renders exactly as before. Also guards the
 * enum-fallback and force-theme behavior.
 */
class NavGraphSettingsTest {

  private val defaults = NavGraphSettings()

  @Test
  fun defaultsMatchOriginalCanvasConstants() {
    // Edges
    assertEquals(0x3377D6, defaults.edgeColorRGB) // EDGE light half
    assertEquals(1.5f, defaults.edgeThickness) // EDGE_STROKE
    assertTrue(defaults.showEdgeLabels)
    assertEquals(CurveStyle.CURVED.name, defaults.curveStyle)

    // Nodes
    assertEquals(240, defaults.nodeWidth) // BOX_W
    assertEquals(14f, defaults.cornerRadius) // ARC
    assertEquals(0x6750A4, defaults.accentColorRGB) // ACCENT light half
    assertEquals(0xFFFFFF, defaults.nodeFillRGB) // NODE_BG light half
    assertTrue(defaults.showThumbnail)
    assertTrue(defaults.showArgs)
    assertTrue(defaults.emphasizeStart)

    // Layout
    assertEquals(90, defaults.columnGap) // COL_GAP
    assertEquals(26, defaults.rowGap) // ROW_GAP
    assertEquals(Direction.LEFT_RIGHT.name, defaults.direction)
    assertEquals(AutoFit.FIRST_LOAD.name, defaults.autoFit)

    // Theme
    assertEquals(ThemeMode.FOLLOW_IDE.name, defaults.themeMode)
    assertEquals(0xFBFCFE, defaults.backgroundRGB) // BG light half

    // Behavior + export
    assertEquals(RefreshMode.RUN_GRADLE.name, defaults.refreshMode)
    assertEquals(DoubleClickAction.NAVIGATE.name, defaults.doubleClickAction)
    assertEquals("", defaults.defaultExportDeviceLabel)
    assertEquals("", defaults.exportOutputDir)
    assertEquals("nav-graph.html", defaults.exportFileName)
  }

  @Test
  fun followIdeThemePreservesOriginalLightAndDarkHalves() {
    val theme = NavGraphTheme(defaults)
    // FOLLOW_IDE → JBColor(lightFromSettings, originalDark). Assert both halves match the
    // original constants.
    assertColorPair(theme.background, light = 0xFBFCFE, dark = Color(0x2B, 0x2D, 0x30))
    assertColorPair(theme.nodeBg, light = 0xFFFFFF, dark = Color(0x3C, 0x3F, 0x41))
    assertColorPair(theme.accent, light = 0x6750A4, dark = Color(0x9A, 0x82, 0xDB))
    assertColorPair(theme.edge, light = 0x3377D6, dark = Color(0x5A, 0x9B, 0xE6))
    // Fixed (non-customizable) colors keep their original light+dark pair too.
    assertColorPair(theme.border, light = 0xD6D9DE, dark = Color(0x4E, 0x51, 0x57))
    assertColorPair(theme.title, light = 0x202124, dark = Color(0xDF, 0xE1, 0xE5))
    assertColorPair(theme.argName, light = 0x1A73E8, dark = Color(0x6B, 0xA5, 0xE7))
    assertColorPair(theme.argType, light = 0x188038, dark = Color(0x6F, 0xBF, 0x73))
    assertColorPair(theme.muted, light = 0x80868B, dark = Color(0x8C, 0x8C, 0x8C))
    assertColorPair(theme.thumbBg, light = 0xF1F3F5, dark = Color(0x31, 0x34, 0x38))
  }

  @Test
  fun forceLightAndForceDarkPinBothHalves() {
    val light = NavGraphTheme(NavGraphSettings().apply { themeMode = ThemeMode.FORCE_LIGHT.name })
    // both halves = settings light
    assertColorPair(light.edge, light = 0x3377D6, dark = Color(0x3377D6))

    val dark = NavGraphTheme(NavGraphSettings().apply { themeMode = ThemeMode.FORCE_DARK.name })
    val edgeDark = Color(0x5A, 0x9B, 0xE6)
    // both halves = original dark
    assertColorPair(dark.edge, light = edgeDark.rgb and 0xFFFFFF, dark = edgeDark)
  }

  @Test
  fun themeGeometryMirrorsSettings() {
    val theme = NavGraphTheme(
      NavGraphSettings().apply {
        nodeWidth = 300
        cornerRadius = 8f
        columnGap = 120
        rowGap = 40
        edgeThickness = 3f
        direction = Direction.TOP_BOTTOM.name
        curveStyle = CurveStyle.STRAIGHT.name
      },
    )
    assertEquals(300, theme.boxW)
    assertEquals(8.0, theme.arc, 0.0)
    assertEquals(120, theme.colGap)
    assertEquals(40, theme.rowGap)
    assertEquals(3f, theme.edgeStroke.lineWidth)
    assertEquals(Direction.TOP_BOTTOM, theme.direction)
    assertEquals(CurveStyle.STRAIGHT, theme.curveStyle)
  }

  @Test
  fun toEnumOrFallsBackOnGarbage() {
    assertEquals(CurveStyle.CURVED, "not-a-real-value".toEnumOr(CurveStyle.CURVED))
    assertEquals(Direction.TOP_BOTTOM, "".toEnumOr(Direction.TOP_BOTTOM))
    // valid value parses
    assertEquals(ThemeMode.FORCE_DARK, "FORCE_DARK".toEnumOr(ThemeMode.FOLLOW_IDE))
  }

  @Test
  fun loadStateCopiesAllFields() {
    val source = NavGraphSettings().apply {
      edgeColorRGB = 0x112233
      nodeWidth = 321
      curveStyle = CurveStyle.STRAIGHT.name
      exportFileName = "g.html"
    }
    val target = NavGraphSettings()
    target.loadState(source)
    assertEquals(0x112233, target.edgeColorRGB)
    assertEquals(321, target.nodeWidth)
    assertEquals(CurveStyle.STRAIGHT.name, target.curveStyle)
    assertEquals("g.html", target.exportFileName)
    assertFalse(target === source)
  }

  private fun assertColorPair(jb: JBColor, light: Int, dark: Color) {
    // JBColor exposes both halves via the (light, dark) it was built from; verify by toggling
    // the global flag.
    JBColor.setDark(false)
    assertEquals("light half", light and 0xFFFFFF, jb.rgb and 0xFFFFFF)
    JBColor.setDark(true)
    assertEquals("dark half", dark.rgb and 0xFFFFFF, jb.rgb and 0xFFFFFF)
    JBColor.setDark(false)
  }
}
