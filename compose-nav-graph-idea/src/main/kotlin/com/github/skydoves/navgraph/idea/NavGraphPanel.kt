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

import com.github.skydoves.navgraph.idea.model.NavGraphDto
import com.github.skydoves.navgraph.idea.model.NavNodeDto
import com.github.skydoves.navgraph.idea.settings.NavGraphSettings
import com.github.skydoves.navgraph.idea.settings.NavGraphSettingsConfigurable
import com.github.skydoves.navgraph.idea.settings.NavGraphSettingsListener
import com.github.skydoves.navgraph.idea.settings.RefreshMode
import com.github.skydoves.navgraph.idea.settings.toEnumOr
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
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
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBCardLayout
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import java.nio.file.Path
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Hosts the native [NavGraphCanvas] in the tool window. **Refresh runs the Gradle
 * `generateNavGraph` task** (re-renders thumbnails + re-extracts the graph) and then reloads —
 * so the preview actually updates after code edits. A device combo reframes the nodes. No JCEF.
 */
internal class NavGraphPanel(private val project: Project, parentDisposable: Disposable) {

  val component: JComponent
  private val canvas = NavGraphCanvas(
    project,
    onNodeActivated = { node -> NavGraphNavigator.navigate(project, node) },
    onAddTransition = { from, to -> addTransition(from, to) },
    onAddDestination = { addDestination() },
    onWireUp = { node -> wireUp(node) },
  )

  // Host the Java2D [canvas] and the Swing setup-guide empty state as two cards. The canvas
  // can't carry a real clickable link, so when no graph is found we flip to the "empty" card
  // (a true Swing panel) instead.
  private val cardLayout = JBCardLayout()
  private val cards = JPanel(cardLayout).apply {
    add(canvas, CARD_GRAPH)
    add(NavGraphSetupGuidePanel(), CARD_EMPTY)
  }

  /**
   * The graph currently shown — "Add destination" anchors a new NavKey to an existing sibling
   * from it.
   */
  private var currentGraph: NavGraphDto? = null

  /**
   * Guards [scopeCombo]'s listener while its items are rebuilt in [applyScopes] (else the
   * rebuild fires it).
   */
  private var suppressScopeEvents = false

  /**
   * "Project:" selector — picks which independent app's graph to render. Hidden for single-app
   * repos.
   */
  private val scopeCombo = ComboBox<NavGraphReader.LoadedScope>().apply {
    toolTipText = "Which app's navigation graph to show " +
      "(this repo has more than one independent app)"
    renderer = SimpleListCellRenderer.create<NavGraphReader.LoadedScope>("") { it?.name ?: "" }
    addActionListener {
      if (suppressScopeEvents) return@addActionListener
      (selectedItem as? NavGraphReader.LoadedScope)?.let { scope ->
        NavGraphSettings.getInstance(project).selectedScopeId = scope.id
        currentGraph = scope.graph
        canvas.setGraph(scope.graph, scope.thumbs)
      }
    }
  }
  private val scopePanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
    isOpaque = false
    isVisible = false
    add(JLabel("Project:"))
    add(scopeCombo)
  }

  init {
    val root = SimpleToolWindowPanel(true, true)

    val group = DefaultActionGroup().apply {
      add(RefreshAction())
      add(ExportAction())
      addSeparator()
      add(SettingsAction())
    }
    val actionToolbar = ActionManager.getInstance().createActionToolbar("NavGraph", group, true)
    actionToolbar.targetComponent = canvas

    val deviceCombo = ComboBox(NavGraphCanvas.DEVICES.toTypedArray()).apply {
      toolTipText = "Device frame for the screen thumbnails"
      renderer = GroupedDeviceRenderer()
      selectedItem = canvas.currentDevice // honor the default-device setting
      addActionListener { (selectedItem as? NavGraphCanvas.Device)?.let { canvas.setDevice(it) } }
    }
    val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 2)).apply {
      isOpaque = false
      add(JLabel("Device:"))
      add(deviceCombo)
    }
    val leftPanel = JPanel(BorderLayout()).apply {
      isOpaque = false
      add(actionToolbar.component, BorderLayout.WEST)
      // the app selector sits right of the actions; shown only when >1 app
      add(scopePanel, BorderLayout.CENTER)
    }
    val header = JPanel(BorderLayout()).apply {
      add(leftPanel, BorderLayout.WEST)
      add(right, BorderLayout.EAST)
    }
    root.toolbar = header
    root.setContent(cards)
    component = root

    // Repaint live when the user applies settings. Scoped to the tool-window content Disposable,
    // so the subscription dies with the tool window (no leak across project close / window
    // dispose).
    project.messageBus.connect(parentDisposable).subscribe(
      NavGraphSettingsListener.TOPIC,
      NavGraphSettingsListener { canvas.onSettingsChanged() },
    )
    reload()
  }

  fun reload() {
    cardLayout.show(cards, CARD_GRAPH) // the transient "Loading…" message renders on the canvas
    canvas.showMessage("Loading…")
    ApplicationManager.getApplication().executeOnPooledThread {
      val loaded = NavGraphReader.loadScopes(project)
      ApplicationManager.getApplication().invokeLater { applyScopes(loaded) }
    }
  }

  /**
   * Rebuild the app selector from the freshly-loaded scopes and render the remembered (or
   * first) one.
   */
  private fun applyScopes(loaded: List<NavGraphReader.LoadedScope>) {
    if (loaded.isEmpty()) {
      // drop any stale items so a later load is clean
      withSuppressedScopeEvents { scopeCombo.removeAllItems() }
      scopePanel.isVisible = false
      cardLayout.show(cards, CARD_EMPTY)
      return
    }
    val rememberedId = NavGraphSettings.getInstance(project).selectedScopeId
    val selected = loaded.firstOrNull { it.id == rememberedId } ?: loaded.first()

    // Rebuild items with the listener muted (else the rebuild fires it), then reveal the
    // selector only when there's a real choice (>1 app).
    withSuppressedScopeEvents {
      scopeCombo.removeAllItems()
      loaded.forEach { scopeCombo.addItem(it) }
      scopeCombo.selectedItem = selected
    }

    scopePanel.isVisible = loaded.size > 1
    currentGraph = selected.graph
    cardLayout.show(cards, CARD_GRAPH)
    canvas.setGraph(selected.graph, selected.thumbs)
  }

  /**
   * Runs [block] with the scope-combo listener muted, always restoring the flag (even if Swing
   * throws).
   */
  private inline fun withSuppressedScopeEvents(block: () -> Unit) {
    suppressScopeEvents = true
    try {
      block()
    } finally {
      suppressScopeEvents = false
    }
  }

  /**
   * Refresh per the user's [RefreshMode]: re-run Gradle (default) or just reload the last
   * output files.
   */
  private fun refresh() {
    when (NavGraphSettings.getInstance(project).refreshMode.toEnumOr(RefreshMode.RUN_GRADLE)) {
      RefreshMode.RUN_GRADLE -> regenerate()
      RefreshMode.READ_EXISTING -> reload()
    }
  }

  /**
   * Re-run the Gradle `generateNavGraph` task, then reload the manifest (the curve shows in the
   * Build view).
   */
  private fun regenerate() {
    val basePath = project.basePath ?: return reload()
    val gradle = ProjectSystemId("GRADLE")
    val settings = ExternalSystemTaskExecutionSettings().apply {
      externalProjectPath = basePath
      taskNames = listOf("generateNavGraph")
      externalSystemIdString = gradle.id
    }
    cardLayout.show(cards, CARD_GRAPH) // the transient "Running …" message renders on the canvas
    canvas.showMessage("Running  generateNavGraph …")
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

  // F4: Map → Code scaffolding (canvas gestures write navgraph boilerplate into the source)

  /**
   * Drag a node's handle (or menu "Add transition from here") onto another node → add
   * `@NavEdge` on the source's `@NavDestination` screen, or — for an orphan source with no
   * screen — on its `NavKey` class. Then regenerate.
   */
  private fun addTransition(from: NavNodeDto, to: NavNodeDto) {
    val targetFqn = to.id.ifBlank { return }
    val sourceFqn = (from.clickTargetFqn ?: from.id).ifBlank { return }
    // Already linked? The manifest is authoritative for what's in the source → no-op with
    // feedback (and the writer is independently idempotent, covering the brief window before a
    // regenerate updates the manifest).
    if (currentGraph?.edges?.any { it.from == from.id && it.to == to.id } == true) {
      notifyGraph(
        "Transition ${from.route} → ${to.route} already exists.",
        NotificationType.INFORMATION,
      )
      return
    }
    val onClass = from.clickTargetFqn == null
    try {
      KotlinNavWriter.addEdge(project, sourceFqn, onClass, targetFqn, to.route) { ok ->
        if (ok) {
          notifyGraph(
            "Transition ${from.route} → ${to.route}.  Regenerating…",
            NotificationType.INFORMATION,
          )
          refresh()
        } else {
          notifyGraph(
            "Couldn't add the transition — ${from.route}'s source wasn't found " +
              "or the edit failed.",
            NotificationType.WARNING,
          )
        }
      }
    } catch (_: LinkageError) {
      notifyGraph(
        "Add transition needs the Kotlin plugin, which isn't loaded in this IDE.",
        NotificationType.WARNING,
      )
    }
  }

  /**
   * Right-click an orphan node (a NavKey with no `@NavDestination`) → scaffold its screen
   * composable.
   */
  private fun wireUp(node: NavNodeDto) {
    val keyFqn = node.id.ifBlank { return }
    try {
      KotlinNavWriter.addDestinationForKey(project, keyFqn, node.route) { ok ->
        if (ok) {
          notifyGraph(
            "Scaffolded ${node.route}Screen for “${node.route}”.  Regenerating…",
            NotificationType.INFORMATION,
          )
          refresh()
        } else {
          notifyGraph(
            "Couldn't wire up “${node.route}” — its NavKey wasn't found or the edit failed.",
            NotificationType.WARNING,
          )
        }
      }
    } catch (_: LinkageError) {
      notifyGraph(
        "Wire up needs the Kotlin plugin, which isn't loaded in this IDE.",
        NotificationType.WARNING,
      )
    }
  }

  /**
   * Right-click the empty canvas → ask for a name, then scaffold a new NavKey +
   * `@NavDestination` screen.
   */
  private fun addDestination() {
    val graph = currentGraph
    val reference = graph?.nodes?.firstOrNull { it.start } ?: graph?.nodes?.firstOrNull()
    if (graph == null || reference == null || reference.id.isBlank()) {
      notifyGraph(
        "Add a destination once the graph has at least one NavKey to anchor the new one.",
        NotificationType.WARNING,
      )
      return
    }
    val existingRoutes = graph.nodes.mapTo(HashSet()) { it.route }
    val name = Messages.showInputDialog(
      project,
      "New destination name (a NavKey type, e.g. Dashboard):",
      "Add Destination",
      null,
      "",
      object : InputValidator {
        override fun checkInput(input: String?): Boolean = input != null &&
          input.matches(Regex("[A-Z][A-Za-z0-9_]*")) && input !in existingRoutes
        override fun canClose(input: String?): Boolean = checkInput(input)
      },
    )?.trim()?.takeIf { it.isNotEmpty() } ?: return
    try {
      KotlinNavWriter.createDestination(project, name, reference.id) { ok ->
        if (ok) {
          notifyGraph(
            "Created “$name” + ${name}Screen.  Regenerating…",
            NotificationType.INFORMATION,
          )
          refresh()
        } else {
          notifyGraph(
            "Couldn't create “$name” — no editable NavKey was found to anchor it.",
            NotificationType.WARNING,
          )
        }
      }
    } catch (_: LinkageError) {
      notifyGraph(
        "Add destination needs the Kotlin plugin, which isn't loaded in this IDE.",
        NotificationType.WARNING,
      )
    }
  }

  /** Post a balloon in the "NavGraph Graph" notification group (registered in plugin.xml). */
  private fun notifyGraph(content: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("NavGraph Graph")
      .createNotification("NavGraph Graph", content, type)
      .notify(project)
  }

  /**
   * PNG (static image) vs HTML (interactive) export — each maps to its Gradle task +
   * save-dialog extension.
   */
  private enum class ExportFormat(
    val ext: String,
    val task: String,
    val hint: String,
    val defaultName: String,
  ) {
    PNG("png", "exportNavGraphImage", "Save the graph as a PNG image", "nav-graph.png"),
    HTML("html", "exportNavGraphHtml", "Save the interactive HTML graph", "nav-graph.html"),
  }

  /**
   * Ask for a destination, run the matching Gradle export task (framing for the selected
   * device), then open the result. Both renderers live in `compose-nav-graph-gradle`.
   */
  private fun exportGraph(format: ExportFormat) {
    val basePath = project.basePath ?: return
    // Run the export in the module of the SELECTED scope (the graph the user is actually
    // looking at), not the repo root — otherwise a multi-app repo exports whichever sibling app
    // Gradle happens to pick.
    val targetPath = (scopeCombo.selectedItem as? NavGraphReader.LoadedScope)?.id ?: basePath
    val prefs = NavGraphSettings.getInstance(project)
    val outDir = prefs.exportOutputDir.ifBlank { basePath }
    val descriptor =
      FileSaverDescriptor("Export Navigation Graph", format.hint, *arrayOf(format.ext))
    val target = FileChooserFactory.getInstance()
      .createSaveFileDialog(descriptor, project)
      .save(Path.of(File(outDir).takeIf { it.isDirectory }?.path ?: basePath), format.defaultName)
      ?.file ?: return

    val device = prefs.defaultExportDeviceLabel
      .takeIf { it.isNotBlank() }
      ?.let { label -> NavGraphCanvas.DEVICES.firstOrNull { it.label == label } }
      ?: canvas.currentDevice
    // Quote the path: ExternalSystem splits scriptParameters on unquoted whitespace, so a
    // destination with a space (e.g. "~/My Drive/…") would otherwise be truncated into stray
    // task names.
    val params = buildString {
      append("-Pnavgraph.export.out=\"").append(target.absolutePath).append('"')
      if (!device.isAuto) {
        append(" -Pnavgraph.export.device=").append(device.w).append('x').append(device.h)
      }
    }

    val gradle = ProjectSystemId("GRADLE")
    val settings = ExternalSystemTaskExecutionSettings().apply {
      externalProjectPath = targetPath
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
      "Refresh the graph (Run generateNavGraph or reload, per settings)",
      AllIcons.Actions.Refresh,
    ) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) = refresh()
  }

  private inner class ExportAction :
    AnAction(
      "Export…",
      "Export the navigation graph as a PNG image or an interactive HTML file",
      AllIcons.ToolbarDecorator.Export,
    ) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
      val popup = JBPopupFactory.getInstance()
        .createPopupChooserBuilder(listOf("PNG image", "Interactive HTML"))
        .setTitle("Export navigation graph as…")
        .setItemChosenCallback { choice ->
          exportGraph(if (choice == "PNG image") ExportFormat.PNG else ExportFormat.HTML)
        }
        .createPopup()
      // Anchor under the clicked toolbar button (the action's own component) instead of a
      // best-guess position.
      val source = e.inputEvent?.component
      if (source != null) {
        popup.showUnderneathOf(source)
      } else {
        popup.showInBestPositionFor(e.dataContext)
      }
    }
  }

  private inner class SettingsAction :
    AnAction(
      "Settings…",
      "Configure the NavGraph Graph appearance and behavior",
      AllIcons.General.Settings,
    ) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
      ShowSettingsUtil.getInstance()
        .showSettingsDialog(project, NavGraphSettingsConfigurable::class.java)
    }
  }

  private companion object {
    /** [JBCardLayout] keys: the Java2D graph/canvas vs. the Swing setup-guide empty state. */
    const val CARD_GRAPH = "graph"
    const val CARD_EMPTY = "empty"
  }
}

/**
 * Renders the device combo with a small group header (Android phone / iPhone / iPad …) above
 * the first entry of each group, so the long multiplatform list reads as sections instead of
 * one flat run.
 */
internal class GroupedDeviceRenderer : ListCellRenderer<NavGraphCanvas.Device> {
  private val delegate = DefaultListCellRenderer()

  override fun getListCellRendererComponent(
    list: JList<out NavGraphCanvas.Device>,
    value: NavGraphCanvas.Device?,
    index: Int,
    isSelected: Boolean,
    cellHasFocus: Boolean,
  ): Component {
    val cell = delegate
      .getListCellRendererComponent(list, value?.label, index, isSelected, cellHasFocus) as JLabel
    cell.border = JBUI.Borders.empty(2, 8)
    // index < 0 is the combo's collapsed display — show only the label, never a header.
    if (index < 0 || value == null) return cell

    val devices = NavGraphCanvas.DEVICES
    val firstOfGroup = index == 0 || devices.getOrNull(index - 1)?.group != value.group
    if (!firstOfGroup) return cell

    return JPanel(BorderLayout()).apply {
      isOpaque = false
      add(
        JBLabel(value.group).apply {
          font = font.deriveFont(Font.BOLD, font.size2D - 1f)
          foreground = JBColor.GRAY
          border = JBUI.Borders.empty(5, 8, 1, 8)
        },
        BorderLayout.NORTH,
      )
      add(cell, BorderLayout.CENTER)
    }
  }
}
