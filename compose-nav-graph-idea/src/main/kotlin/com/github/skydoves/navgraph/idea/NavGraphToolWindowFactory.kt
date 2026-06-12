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

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Registers the "NavGraph Graph" tool window with three tabs: the native flow-map canvas ("Graph"), the
 * preview gallery ("Previews"), and the author introduction ("Author"). All are non-closeable
 * [com.intellij.ui.content.Content]s so they always render as a fixed set of tabs.
 */
internal class NavGraphToolWindowFactory :
  ToolWindowFactory,
  DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val factory = ContentFactory.getInstance()

    // GitHub shortcut in the tool window title bar, visible from both tabs.
    toolWindow.setTitleActions(listOf(GitHubAction()))

    // Tab 1 — the flow map. Create the Content first so it can serve as the parent Disposable for the
    // graph panel's settings message-bus connection — the subscription then dies when the content is disposed.
    val graphContent = factory.createContent(null, "Graph", false)
    graphContent.component = NavGraphPanel(project, graphContent).component
    graphContent.isCloseable = false
    toolWindow.contentManager.addContent(graphContent)

    // Tab 2 — the preview gallery (the same canvas in gallery mode). The Content is its parent Disposable, so
    // the gallery's settings message-bus connection dies when the content is disposed.
    val galleryContent = factory.createContent(null, "Previews", false)
    galleryContent.component = PreviewGalleryPanel(project, galleryContent).component
    galleryContent.isCloseable = false
    toolWindow.contentManager.addContent(galleryContent)

    // Tab 3 — about the author and their other plugins. Static content, no Disposable needed.
    val authorContent = factory.createContent(AuthorPanel(), "Author", false)
    authorContent.isCloseable = false
    toolWindow.contentManager.addContent(authorContent)
  }
}
