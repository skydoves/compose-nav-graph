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

import com.github.skydoves.navgraph.idea.settings.NavGraphSettings
import com.github.skydoves.navgraph.idea.settings.NavGraphSettingsConfigurable
import com.github.skydoves.navgraph.idea.settings.NavGraphSettingsListener
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The "Previews" tab: the native [NavGraphCanvas] in **gallery mode** — every `@Preview` rendered to a box,
 * grouped by module, with the SAME zoom (wheel / bottom-right buttons), device-frame combo, box rendering, and
 * **Export / Settings** actions as the nav graph. **Refresh runs the Gradle `generatePreviewGallery` task**;
 * Export runs `exportPreviewGalleryImage` / `exportPreviewGalleryHtml`. Thumbnails are shared with the graph via
 * [NavGraphPreviewThumbnails]. No JCEF.
 */
internal class PreviewGalleryPanel(private val project: Project, parentDisposable: Disposable) {

  val component: JComponent

  private val canvas = NavGraphCanvas(
    project,
    onNodeActivated = { node -> NavGraphNavigator.navigate(project, node) },
  ).apply { setGalleryMode(true) }

  /** The module to run the export tasks in (the aggregated/umbrella module), set on each [reload]. */
  private var exportProjectPath: String? = null

  init {
    val root = SimpleToolWindowPanel(true, true)

    val group = DefaultActionGroup().apply {
      add(RefreshAction())
      add(ExportAction())
      addSeparator()
      add(SettingsAction())
    }
    val actionToolbar = ActionManager.getInstance().createActionToolbar(
      "NavGraphPreviewGallery",
      group,
      true,
    )
    actionToolbar.targetComponent = canvas

    val deviceCombo = ComboBox(NavGraphCanvas.DEVICES.toTypedArray()).apply {
      toolTipText = "Device frame for the preview thumbnails"
      renderer = GroupedDeviceRenderer()
      selectedItem = canvas.currentDevice
      addActionListener { (selectedItem as? NavGraphCanvas.Device)?.let { canvas.setDevice(it) } }
    }
    val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 2)).apply {
      isOpaque = false
      add(JLabel("Device:"))
      add(deviceCombo)
    }
    val header = JPanel(BorderLayout()).apply {
      add(actionToolbar.component, BorderLayout.WEST)
      add(right, BorderLayout.EAST)
    }
    root.toolbar = header
    root.setContent(canvas)
    component = root

    // Repaint live when the user applies settings (theme / box width / device …), shared with the graph tab.
    project.messageBus.connect(parentDisposable).subscribe(
      NavGraphSettingsListener.TOPIC,
      NavGraphSettingsListener { canvas.onSettingsChanged() },
    )
    reload()
  }

  /** Read the last gallery output off-EDT, then render it on the canvas (mirrors [NavGraphPanel.reload]). */
  fun reload() {
    canvas.showMessage("Loading…")
    ApplicationManager.getApplication().executeOnPooledThread {
      val loaded = PreviewGalleryReader.load(project)
      ApplicationManager.getApplication().invokeLater { apply(loaded) }
    }
  }

  private fun apply(loaded: PreviewGalleryReader.Loaded) {
    exportProjectPath = loaded.exportProjectPath
    if (loaded.graph.nodes.isEmpty()) {
      canvas.showMessage("No previews yet — click Refresh to run generatePreviewGallery.")
    } else {
      canvas.setGraph(loaded.graph, loaded.thumbs)
    }
  }

  /** Re-run the Gradle `generatePreviewGallery` task, then reload (progress shows in the Build tool window). */
  private fun regenerate() {
    val basePath = project.basePath ?: return reload()
    val gradle = ProjectSystemId("GRADLE")
    val settings = ExternalSystemTaskExecutionSettings().apply {
      externalProjectPath = basePath
      taskNames = listOf("generatePreviewGallery")
      externalSystemIdString = gradle.id
    }
    canvas.showMessage("Running  generatePreviewGallery …")
    ExternalSystemUtil.runTask(
      settings,
      DefaultRunExecutor.EXECUTOR_ID,
      project,
      gradle,
      object : TaskCallback {
        override fun onSuccess() {
          ApplicationManager.getApplication().invokeLater { reload() }
        }

        override fun onFailure() {
          ApplicationManager.getApplication().invokeLater { reload() }
        }
      },
      ProgressExecutionMode.IN_BACKGROUND_ASYNC,
    )
  }

  /** PNG (static grid) vs HTML (interactive) export — each maps to its Gradle task + save-dialog extension. */
  private enum class ExportFormat(
    val ext: String,
    val task: String,
    val hint: String,
    val defaultName: String,
  ) {
    PNG(
      "png",
      "exportPreviewGalleryImage",
      "Save the preview gallery as a PNG image",
      "preview-gallery.png",
    ),
    HTML(
      "html",
      "exportPreviewGalleryHtml",
      "Save the interactive HTML gallery",
      "preview-gallery.html",
    ),
  }

  /** Ask for a destination, run the matching gallery export task in the umbrella module, then open the result. */
  private fun exportGallery(format: ExportFormat) {
    val basePath = project.basePath ?: return
    val targetModule = exportProjectPath ?: basePath
    val prefs = NavGraphSettings.getInstance(project)
    val outDir = prefs.exportOutputDir.ifBlank { basePath }
    val descriptor =
      FileSaverDescriptor("Export Preview Gallery", format.hint, *arrayOf(format.ext))
    val target = FileChooserFactory.getInstance()
      .createSaveFileDialog(descriptor, project)
      .save(Path.of(File(outDir).takeIf { it.isDirectory }?.path ?: basePath), format.defaultName)
      ?.file ?: return

    // Quote the path: ExternalSystem splits scriptParameters on unquoted whitespace.
    val params = "-Pnavgraph.gallery.out=\"${target.absolutePath}\""
    val gradle = ProjectSystemId("GRADLE")
    val settings = ExternalSystemTaskExecutionSettings().apply {
      externalProjectPath = targetModule
      taskNames = listOf(format.task)
      externalSystemIdString = gradle.id
      scriptParameters = params
    }
    ExternalSystemUtil.runTask(
      settings,
      DefaultRunExecutor.EXECUTOR_ID,
      project,
      gradle,
      object : TaskCallback {
        override fun onSuccess() {
          ApplicationManager.getApplication().invokeLater {
            if (target.isFile) BrowserUtil.browse(target)
          }
        }

        override fun onFailure() = Unit // the Gradle failure surfaces in the Build tool window
      },
      ProgressExecutionMode.IN_BACKGROUND_ASYNC,
    )
  }

  private inner class RefreshAction :
    AnAction(
      "Refresh",
      "Render every @Preview (Run generatePreviewGallery) and reload the gallery",
      AllIcons.Actions.Refresh,
    ) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) = regenerate()
  }

  private inner class ExportAction :
    AnAction(
      "Export…",
      "Export the preview gallery as a PNG image or an interactive HTML file",
      AllIcons.ToolbarDecorator.Export,
    ) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
      val popup = JBPopupFactory.getInstance()
        .createPopupChooserBuilder(listOf("PNG image", "Interactive HTML"))
        .setTitle("Export preview gallery as…")
        .setItemChosenCallback { choice ->
          exportGallery(if (choice == "PNG image") ExportFormat.PNG else ExportFormat.HTML)
        }
        .createPopup()
      val source = e.inputEvent?.component
      if (source !=
        null
      ) {
        popup.showUnderneathOf(source)
      } else {
        popup.showInBestPositionFor(e.dataContext)
      }
    }
  }

  private inner class SettingsAction :
    AnAction(
      "Settings…",
      "Configure the NavGraph Graph / preview appearance and behavior",
      AllIcons.General.Settings,
    ) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
      ShowSettingsUtil.getInstance().showSettingsDialog(
        project,
        NavGraphSettingsConfigurable::class.java,
      )
    }
  }
}
