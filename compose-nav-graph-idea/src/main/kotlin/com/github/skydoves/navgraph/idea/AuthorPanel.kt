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
package com.github.skydoves.navgraph.idea

import com.intellij.ide.BrowserUtil
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * The "Author" tab: who built the plugin, plus pointers to the author's other developer-tool
 * plugins. Theme-aware: all colors/fonts come from the platform so it tracks light/dark and
 * font-size changes.
 */
internal class AuthorPanel : JPanel(GridBagLayout()) {

  init {
    val column = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      border = JBUI.Borders.empty(16, 20, 16, 16)
      // Cap the width so the wrapped body text reads as a tidy column instead of stretching
      // edge to edge.
      maximumSize = JBUI.size(460, Int.MAX_VALUE)

      add(
        JBLabel("Author").apply {
          font = JBFont.label().asBold().deriveFont(JBFont.label().size2D + 2f)
          alignmentX = Component.LEFT_ALIGNMENT
        },
      )
      add(
        ActionLink("skydoves (Jaewoong Eum)") {
          BrowserUtil.browse("https://github.com/skydoves/")
        }.apply {
          font = JBFont.label().asBold()
          alignmentX = Component.LEFT_ALIGNMENT
          border = JBUI.Borders.empty(8, 0, 0, 0)
        },
      )

      add(
        JBLabel("Curious about other plugins by this author?").apply {
          font = JBFont.label().asBold()
          alignmentX = Component.LEFT_ALIGNMENT
          border = JBUI.Borders.empty(24, 0, 12, 0)
        },
      )

      add(
        ActionLink("Compose Stability Analyzer") {
          BrowserUtil.browse("https://github.com/skydoves/compose-stability-analyzer")
        }.apply {
          font = JBFont.label().asBold()
          alignmentX = Component.LEFT_ALIGNMENT
        },
      )
      add(
        JBLabel(
          "<html>Analyzes the stability of your composables and visualizes recompositions " +
            "in real time, with inline stability hints, recomposition counts, " +
            "and quick fixes right inside Android Studio.</html>",
        ).apply {
          foreground = UIUtil.getContextHelpForeground()
          alignmentX = Component.LEFT_ALIGNMENT
          border = JBUI.Borders.empty(4, 0, 16, 0)
        },
      )

      add(
        ActionLink("Compose Hot Reload for Android (HotSwan)") {
          BrowserUtil.browse("https://hotswan.dev/")
        }.apply {
          font = JBFont.label().asBold()
          alignmentX = Component.LEFT_ALIGNMENT
        },
      )
      add(
        JBLabel(
          "<html>Hot reloads your Compose code on a real device or emulator in seconds. " +
            "UI changes appear instantly while the app keeps its navigation and runtime state, " +
            "with no reinstall or restart.</html>",
        ).apply {
          foreground = UIUtil.getContextHelpForeground()
          alignmentX = Component.LEFT_ALIGNMENT
          border = JBUI.Borders.empty(4, 0, 16, 0)
        },
      )

      add(
        ActionLink("Dove Letter") {
          BrowserUtil.browse("https://doveletter.dev/")
        }.apply {
          font = JBFont.label().asBold()
          alignmentX = Component.LEFT_ALIGNMENT
        },
      )
      add(
        JBLabel(
          "<html>A daily subscription where you can learn, discuss, and share new knowledge " +
            "about Android, Kotlin, Jetpack Compose, and careers as a developer.</html>",
        ).apply {
          foreground = UIUtil.getContextHelpForeground()
          alignmentX = Component.LEFT_ALIGNMENT
          border = JBUI.Borders.empty(4, 0, 0, 0)
        },
      )
    }

    // Anchor the column to the top-left instead of floating it in the middle.
    val constraints = GridBagConstraints().apply {
      gridx = 0
      gridy = 0
      weightx = 1.0
      weighty = 1.0
      anchor = GridBagConstraints.NORTHWEST
    }
    add(column, constraints)
  }
}
