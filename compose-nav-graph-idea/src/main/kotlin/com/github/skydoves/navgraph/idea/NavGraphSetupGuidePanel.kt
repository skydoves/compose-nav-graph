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
import java.awt.GridBagLayout
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * The empty-state shown in the "NavGraph Graph" tool window when no navigation graph is found —
 * a brief setup guide with a clickable link to the project. Swapped in for the canvas (a Java2D
 * component that can't host a real clickable link) via the tool window's
 * [com.intellij.ui.JBCardLayout]. Theme-aware: all colors/fonts come from the platform so it
 * tracks light/dark and font-size changes.
 */
internal class NavGraphSetupGuidePanel : JPanel(GridBagLayout()) {

  init {
    // GridBagLayout with no constraints centers its single child both ways → the column floats
    // in the middle.
    val column = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      // Cap the width so the wrapped body text reads as a tidy column instead of stretching
      // edge to edge.
      maximumSize = JBUI.size(420, Int.MAX_VALUE)

      add(
        JBLabel("No navigation graph yet").apply {
          font = JBFont.label().asBold().deriveFont(JBFont.label().size2D + 2f)
          alignmentX = Component.LEFT_ALIGNMENT
        },
      )
      add(
        JBLabel(
          "<html>Apply the <code>com.github.skydoves.navgraph</code> Gradle plugin " +
            "to your module, then run generateNavGraph for it. " +
            "Click Refresh above to run it now.</html>",
        ).apply {
          foreground = UIUtil.getContextHelpForeground()
          alignmentX = Component.LEFT_ALIGNMENT
          border = JBUI.Borders.empty(8, 0, 12, 0)
        },
      )
      add(
        ActionLink("Setup guide → github.com/skydoves/compose-nav-graph") {
          BrowserUtil.browse("https://github.com/skydoves/compose-nav-graph")
        }.apply {
          alignmentX = Component.LEFT_ALIGNMENT
        },
      )
    }
    add(column)
  }
}
