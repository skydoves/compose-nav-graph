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

import com.github.skydoves.navgraph.idea.model.NavArgDto
import com.github.skydoves.navgraph.idea.model.NavEdgeDto
import com.github.skydoves.navgraph.idea.model.NavGraphDto
import com.github.skydoves.navgraph.idea.model.NavNodeDto
import com.github.skydoves.navgraph.idea.settings.AutoFit
import com.github.skydoves.navgraph.idea.settings.CurveStyle
import com.github.skydoves.navgraph.idea.settings.Direction
import com.github.skydoves.navgraph.idea.settings.DoubleClickAction
import com.github.skydoves.navgraph.idea.settings.NavGraphSettings
import com.github.skydoves.navgraph.idea.settings.NavGraphTheme
import com.github.skydoves.navgraph.idea.settings.toEnumOr
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.JComponent

/**
 * A native Swing/Java2D flow map (the approach of Android Studio's `DesignSurface` — no JCEF,
 * so it works in any IDE/runtime). Nodes = boxes (the rendered `@Preview` at its **true device
 * aspect** + route + UML args); edges = curved arrows. Pan = drag; zoom = wheel or the
 * bottom-right buttons; double-click a node → [onNodeActivated] (→ PSI jump). [setDevice]
 * reframes nodes to a chosen device aspect.
 *
 * All theming/geometry (colors, strokes, fonts, box width, gaps, curve style, layout direction,
 * auto-fit) is read from [NavGraphSettings] through a fresh [NavGraphTheme] built per
 * layout/repaint.
 */
internal class NavGraphCanvas(
  private val project: Project?,
  private val onNodeActivated: (NavNodeDto) -> Unit,
  // Map→Code callbacks (F4). Defaulted so the headless preview test and any other caller still
  // compile; the canvas only DETECTS intent and fires these — all PSI writing happens in the
  // panel/writer, never here.
  private val onAddTransition: (from: NavNodeDto, to: NavNodeDto) -> Unit = { _, _ -> },
  private val onAddDestination: () -> Unit = {},
  private val onWireUp: (NavNodeDto) -> Unit = {},
) : JComponent() {

  /**
   * A display frame. [w]/[h] = 0 means "Auto" — each node uses its own rendered-preview aspect.
   */
  data class Device(val label: String, val group: String, val w: Int, val h: Int) {
    val isAuto: Boolean get() = w == 0 || h == 0
    override fun toString(): String = label
  }

  private class Laid(
    val node: NavNodeDto,
    val x: Int,
    val y: Int,
    val w: Int,
    val thumbH: Int,
    val h: Int,
    val thumb: BufferedImage?,
  )

  /** A preview-gallery section header (a Gradle module path) + how many previews it groups. */
  private class GalleryHeader(val text: String, val count: Int, val x: Int, val y: Int)

  private class ZoomButton(val rect: Rectangle, val icon: Icon, val action: () -> Unit)

  /** Fallback bean (all defaults) for the headless preview test, which has no [Project]. */
  private val fallbackSettings = NavGraphSettings()

  private fun settings(): NavGraphSettings =
    project?.let { NavGraphSettings.getInstance(it) } ?: fallbackSettings

  /**
   * The device the canvas opens on: the one matching `defaultExportDeviceLabel` from settings
   * if set, else `DEVICES.first()` (Auto). The default of `""` therefore yields Auto.
   */
  private fun defaultDevice(): Device {
    val label = settings().defaultExportDeviceLabel
    return DEVICES.firstOrNull { it.label == label } ?: DEVICES.first()
  }

  private var laid: List<Laid> = emptyList()
  private var byId: Map<String, Laid> = emptyMap()
  private var edges: List<NavEdgeDto> = emptyList()
  private var emptyMessage: String? = "Loading…"
  private var lastGraph: NavGraphDto? = null
  private var lastThumbs: Map<String, BufferedImage> = emptyMap()
  private var device: Device = defaultDevice()

  /**
   * Rebuilt from settings before every layout and every repaint — cheap, and keeps the two
   * perfectly in sync.
   */
  private var theme: NavGraphTheme = NavGraphTheme(settings())

  /**
   * The device frame currently selected — read by the HTML export to match what the user sees.
   */
  val currentDevice: Device get() = device

  private var scale = 1.0
  private var tx = MARGIN.toDouble()
  private var ty = MARGIN.toDouble()
  private var needsFit = false
  private var fitted = false // fit only once; Refresh preserves the user's zoom/pan
  private var hoverId: String? = null
  private var lastDrag: Point? = null

  // F4 "Add transition" drag state: the source node whose connector handle was pressed + the
  // live drag point (screen space) for the rubber-band. Non-null wireFrom means a wire is being
  // drawn (pan is suppressed).
  private var wireFrom: Laid? = null
  private var wirePoint: Point? = null
  private var zoomButtons: List<ZoomButton> = emptyList()

  // Preview-gallery mode: lay nodes out as a module-grouped grid (no BFS/edges) with section
  // headers. All gallery-specific behavior is guarded by this flag, so the nav graph is
  // unaffected.
  private var galleryMode = false
  private var galleryHeaders: List<GalleryHeader> = emptyList()

  private val baseFont: Font get() = font ?: DEFAULT_FONT
  private val titleFont: Font get() = baseFont.deriveFont(Font.BOLD, 13f)
  private val argFont = Font(Font.MONOSPACED, Font.PLAIN, 11)
  private val labelFont: Font get() = baseFont.deriveFont(10f)

  init {
    isOpaque = true
    toolTipText = ""
    val mouse = object : MouseAdapter() {
      override fun mousePressed(e: MouseEvent) {
        if (e.isPopupTrigger) {
          showContextMenu(e)
          return
        } // right-click fires on press (macOS)
        val z = zoomButtons.firstOrNull { it.rect.contains(e.point) }
        if (z != null) {
          z.action()
          return
        }
        val handle = handleHit(e.x, e.y)
        if (handle != null) {
          // Start a transition wire from this node's connector handle. Deliberately leave
          // lastDrag null so the pan branch in mouseDragged no-ops — the press began on a
          // handle, not the empty canvas.
          wireFrom = handle
          wirePoint = e.point
          return
        }
        lastDrag = e.point
      }
      override fun mouseReleased(e: MouseEvent) {
        if (e.isPopupTrigger) {
          showContextMenu(e)
          return
        } // right-click fires on release (Linux/Windows)
        val wf = wireFrom
        if (wf != null) {
          wireFrom = null
          wirePoint = null
          repaint()
          nodeAt(e.x, e.y)?.takeIf { it.node.id != wf.node.id }
            ?.let { onAddTransition(wf.node, it.node) }
          return
        }
        lastDrag = null
      }
      override fun mouseDragged(e: MouseEvent) {
        // dragging a transition wire → just track the cursor for the rubber-band
        if (wireFrom != null) {
          wirePoint = e.point
          repaint()
          return
        }
        val p = lastDrag ?: return
        tx += (e.x - p.x)
        ty += (e.y - p.y)
        lastDrag = e.point
        repaint()
      }
      override fun mouseClicked(e: MouseEvent) {
        if (zoomButtons.any { it.rect.contains(e.point) }) return
        val action = settings().doubleClickAction.toEnumOr(DoubleClickAction.NAVIGATE)
        if (action == DoubleClickAction.NONE) return
        if (e.clickCount == 2) {
          nodeAt(e.x, e.y)?.takeIf { it.node.isClickable() }?.let { onNodeActivated(it.node) }
        }
      }
      override fun mouseMoved(e: MouseEvent) {
        val overButton = zoomButtons.any { it.rect.contains(e.point) }
        val n = if (overButton) null else nodeAt(e.x, e.y)?.takeIf { it.node.isClickable() }
        cursor = if (overButton || n != null) {
          Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
          Cursor.getDefaultCursor()
        }
        if (n?.node?.id != hoverId) {
          hoverId = n?.node?.id
          repaint()
        }
      }
      override fun mouseWheelMoved(e: MouseWheelEvent) {
        zoomAround(e.x.toDouble(), e.y.toDouble(), if (e.preciseWheelRotation < 0) 1.1 else 1 / 1.1)
      }
    }
    addMouseListener(mouse)
    addMouseMotionListener(mouse)
    addMouseWheelListener(mouse)
  }

  fun setGraph(graph: NavGraphDto, thumbs: Map<String, BufferedImage>) {
    emptyMessage = null
    lastGraph = graph
    lastThumbs = thumbs
    relayout()
    when (settings().autoFit.toEnumOr(AutoFit.FIRST_LOAD)) {
      // FIRST_LOAD (default): fit once, later refreshes keep the user's zoom/pan.
      AutoFit.FIRST_LOAD -> if (!fitted) {
        needsFit = true
        fitted = true
      }

      AutoFit.EVERY_REFRESH -> {
        needsFit = true
        fitted = true
      }

      AutoFit.NEVER -> fitted = true
    }
    repaint()
  }

  fun showMessage(message: String) {
    emptyMessage = message
    laid = emptyList()
    byId = emptyMap()
    edges = emptyList()
    repaint()
  }

  fun setDevice(d: Device) {
    if (d == device) return
    device = d
    if (lastGraph != null) relayout() // keep the current zoom/pan
    repaint()
  }

  fun fit() {
    needsFit = true
    repaint()
  }

  /**
   * Switch to the preview-gallery layout: a grid of preview boxes grouped by module (no
   * edges/flow), with the same boxes/zoom/device framing as the nav graph.
   */
  fun setGalleryMode(on: Boolean) {
    if (galleryMode == on) return
    galleryMode = on
    if (lastGraph != null) relayout()
    repaint()
  }

  /**
   * Node title: the navigation-target composable's name (e.g. "SearchScreen"), falling back to
   * the route.
   */
  private fun NavNodeDto.displayLabel(): String =
    clickTargetFqn?.substringAfterLast('.')?.ifBlank { null } ?: route

  override fun getToolTipText(e: MouseEvent): String? {
    val n = nodeAt(e.x, e.y) ?: return null
    return if (n.node.isClickable()) {
      "Double-click to open ${n.node.displayLabel()}"
    } else {
      n.node.displayLabel()
    }
  }

  private fun relayout() {
    // refresh geometry (box width, gaps, direction) before laying out
    theme = NavGraphTheme(settings())
    val graph = lastGraph ?: return
    laid = layout(graph, lastThumbs)
    byId = laid.associateBy { it.node.id }
    edges = graph.edges
    hoverId = null
  }

  /**
   * Re-reads the settings: relayout (box width / gaps / direction may have changed), honor an
   * [AutoFit.EVERY_REFRESH] preference, and repaint (colors / strokes / curve style take effect
   * immediately).
   */
  fun onSettingsChanged() {
    if (lastGraph != null) {
      relayout()
      if (settings().autoFit.toEnumOr(AutoFit.FIRST_LOAD) == AutoFit.EVERY_REFRESH) needsFit = true
    } else {
      theme = NavGraphTheme(settings())
    }
    repaint()
  }

  // height/width ratio of a node's preview frame
  private fun thumbHeight(img: BufferedImage?): Int {
    val ratio = when {
      !device.isAuto -> device.h.toDouble() / device.w
      img != null && img.width > 0 -> img.height.toDouble() / img.width
      else -> DEFAULT_RATIO
    }
    return (theme.boxW * ratio).toInt().coerceIn(90, 560)
  }

  // layout: lanes by BFS depth from the start node(s), nodes stacked within a lane
  // LEFT_RIGHT (default): depth → columns on X, stack → Y.
  // TOP_BOTTOM: depth → rows on Y, stack → X (the two axes swap).
  private fun layout(graph: NavGraphDto, thumbs: Map<String, BufferedImage>): List<Laid> {
    if (galleryMode) return galleryLayout(graph, thumbs)
    val boxW = theme.boxW
    val colGap = theme.colGap
    val rowGap = theme.rowGap
    val vertical = theme.direction == Direction.TOP_BOTTOM

    val ids = graph.nodes.map { it.id }.toHashSet()
    val adj = HashMap<String, MutableList<String>>()
    graph.edges.forEach {
      if (it.from in ids && it.to in ids) adj.getOrPut(it.from) { mutableListOf() }.add(it.to)
    }

    // Screens with no transition at all (no in- or out-edge) are pulled out of the flow into a
    // separate bottom section so they don't clutter the connected graph; the rest lay out in
    // BFS lanes.
    val touched = HashSet<String>()
    graph.edges.forEach {
      if (it.from in ids && it.to in ids) {
        touched.add(it.from)
        touched.add(it.to)
      }
    }
    val connectedNodes = graph.nodes.filter { it.id in touched }
    val isolatedNodes = graph.nodes.filter { it.id !in touched }

    val depth = HashMap<String, Int>()
    val queue = ArrayDeque<String>()
    // Seed lanes from the real entry points: marked start(s) plus every in-degree-0 source
    // among connected nodes (so a multi-root merged graph lays out compactly instead of
    // spilling each root into its own lane), else the first connected node.
    val indeg = HashMap<String, Int>().apply { graph.nodes.forEach { put(it.id, 0) } }
    graph.edges.forEach {
      if (it.from in ids && it.to in ids && it.from != it.to) {
        indeg[it.to] = (indeg[it.to] ?: 0) + 1
      }
    }
    val starts = connectedNodes.filter { it.start || indeg[it.id] == 0 }.map { it.id }
      .ifEmpty { connectedNodes.take(1).map { it.id } }
    starts.forEach {
      depth[it] = 0
      queue.add(it)
    }
    while (queue.isNotEmpty()) {
      val id = queue.removeFirst()
      adj[id]?.forEach { t ->
        if (t !in depth) {
          depth[t] = depth.getValue(id) + 1
          queue.add(t)
        }
      }
    }
    var maxDepth = depth.values.maxOrNull() ?: 0
    connectedNodes.forEach {
      if (it.id !in depth) {
        maxDepth++
        depth[it.id] = maxDepth
      }
    }

    val ordered = connectedNodes.sortedBy { depth.getValue(it.id) }

    // Each node's box height depends on its args/thumbnail, so compute it once up front. A
    // hidden thumbnail or hidden args collapses that region so the box stays tight (no empty
    // reserved space).
    val argRows = if (theme.showArgs) 1 else 0
    val box = graph.nodes.associateWith { node ->
      val thumb = thumbs[node.id]
      val th = if (theme.showThumbnail) thumbHeight(thumb) else 0
      // (thumb, thumbH, fullH)
      Triple(thumb, th, th + TITLE_H + node.args.size * argRows * ARG_H + PAD)
    }

    val connectedLaid = if (!vertical) {
      // LEFT_RIGHT placement.
      val colY = HashMap<Int, Int>()
      ordered.map { node ->
        val d = depth.getValue(node.id)
        val (thumb, th, h) = box.getValue(node)
        val x = MARGIN + d * (boxW + colGap)
        val y = MARGIN + (colY[d] ?: 0)
        colY[d] = (colY[d] ?: 0) + h + rowGap
        Laid(node, x, y, boxW, th, h, thumb)
      }
    } else {
      // TOP_BOTTOM: depth advances down the Y axis (each depth-lane's height = the tallest
      // node in it), nodes stack rightward on X. colGap/rowGap keep their "between-lanes /
      // between-stacked" meanings.
      val laneHeight = HashMap<Int, Int>()
      ordered.forEach { node ->
        val d = depth.getValue(node.id)
        laneHeight[d] = maxOf(laneHeight[d] ?: 0, box.getValue(node).third)
      }
      val laneTop = HashMap<Int, Int>()
      for (d in 0..maxDepth) {
        laneTop[d] = if (d == 0) {
          MARGIN
        } else {
          laneTop.getValue(d - 1) + (laneHeight[d - 1] ?: 0) + colGap
        }
      }
      val rowX = HashMap<Int, Int>()
      ordered.map { node ->
        val d = depth.getValue(node.id)
        val (thumb, th, h) = box.getValue(node)
        val x = MARGIN + (rowX[d] ?: 0)
        val y = laneTop.getValue(d)
        rowX[d] = (rowX[d] ?: 0) + boxW + rowGap
        Laid(node, x, y, boxW, th, h, thumb)
      }
    }
    if (isolatedNodes.isEmpty()) return connectedLaid

    // Unconnected screens: a grid below the flow, wrapped to the column count.
    val sectionTop = (connectedLaid.maxOfOrNull { it.y + it.h } ?: MARGIN) + SECTION_GAP
    val perRow = (maxDepth + 1).coerceAtLeast(1)
    var col = 0
    var rowTop = sectionTop
    var rowH = 0
    val isolatedLaid = isolatedNodes.map { node ->
      if (col >= perRow) {
        col = 0
        rowTop += rowH + rowGap
        rowH = 0
      }
      val (thumb, th, h) = box.getValue(node)
      val laid = Laid(node, MARGIN + col * (boxW + colGap), rowTop, boxW, th, h, thumb)
      col++
      rowH = maxOf(rowH, h)
      laid
    }
    return connectedLaid + isolatedLaid
  }

  /**
   * Preview-gallery layout: every node is one `@Preview` box. Group by module and lay each
   * group out as a grid that wraps to the visible width, under a section header. No BFS, no
   * edges — the same boxes / zoom / device framing as the nav graph, arranged as a gallery.
   */
  private fun galleryLayout(graph: NavGraphDto, thumbs: Map<String, BufferedImage>): List<Laid> {
    val boxW = theme.boxW
    val colGap = theme.colGap
    val rowGap = theme.rowGap
    val argRows = if (theme.showArgs) 1 else 0
    // Columns fill the visible width (fall back to a sane default before the component is
    // first sized).
    val avail = (if (width > 100) width else 1200) - MARGIN * 2
    val cols = maxOf(1, (avail + colGap) / (boxW + colGap))

    // Group by module, preserving the reader's stable order; a node with no module shares one
    // bucket.
    val groups = LinkedHashMap<String, MutableList<NavNodeDto>>()
    graph.nodes.forEach {
      val key = it.module?.takeIf { m -> m.isNotBlank() } ?: GALLERY_THIS_MODULE
      groups.getOrPut(key) { mutableListOf() }.add(it)
    }

    val out = ArrayList<Laid>(graph.nodes.size)
    val headers = ArrayList<GalleryHeader>(groups.size)
    var y = MARGIN
    for ((module, nodes) in groups) {
      headers.add(GalleryHeader(module, nodes.size, MARGIN, y))
      y += GALLERY_HEADER_H
      var col = 0
      var rowH = 0
      for (node in nodes) {
        if (col >= cols) {
          col = 0
          y += rowH + rowGap
          rowH = 0
        }
        val thumb = thumbs[node.id]
        val th = if (theme.showThumbnail) thumbHeight(thumb) else 0
        val h = th + TITLE_H + node.args.size * argRows * ARG_H + PAD
        out.add(Laid(node, MARGIN + col * (boxW + colGap), y, boxW, th, h, thumb))
        col++
        rowH = maxOf(rowH, h)
      }
      y += rowH + SECTION_GAP
    }
    galleryHeaders = headers
    return out
  }

  /**
   * Draw the gallery section headers (module path + preview count + a hairline rule) in graph
   * space.
   */
  private fun drawGalleryHeaders(g2: Graphics2D) {
    if (galleryHeaders.isEmpty()) return
    val right = laid.maxOfOrNull { it.x + it.w } ?: return
    for (h in galleryHeaders) {
      g2.color = theme.title
      g2.font = titleFont
      g2.drawString(h.text, h.x, h.y + 15)
      val tw = g2.fontMetrics.stringWidth(h.text)
      g2.color = theme.muted
      g2.font = labelFont
      g2.drawString("${h.count} preview${if (h.count == 1) "" else "s"}", h.x + tw + 8, h.y + 15)
      g2.color = theme.border
      g2.stroke = STROKE1
      g2.drawLine(h.x, h.y + 23, right, h.y + 23)
    }
  }

  override fun paintComponent(g: Graphics) {
    // fresh colors/strokes/geometry every frame; settings take effect live
    theme = NavGraphTheme(settings())
    val g2 = g as Graphics2D
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g2.setRenderingHint(
      RenderingHints.KEY_INTERPOLATION,
      RenderingHints.VALUE_INTERPOLATION_BILINEAR,
    )
    g2.setRenderingHint(
      RenderingHints.KEY_TEXT_ANTIALIASING,
      RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
    )
    g2.color = theme.background
    g2.fillRect(0, 0, width, height)

    val message = emptyMessage
    if (message != null || laid.isEmpty()) {
      paintCentered(
        g2,
        message ?: "No navigation graph found.",
        theme.muted,
        baseFont.deriveFont(13f),
      )
      return
    }
    if (needsFit) {
      fitToView()
      needsFit = false
    }

    val saved = g2.transform
    g2.translate(tx, ty)
    g2.scale(scale, scale)
    val (srcAnchorY, dstAnchorY) = staggerAnchors(edges, byId)
    edges.forEachIndexed { i, e -> drawEdge(g2, e, srcAnchorY[i], dstAnchorY[i]) }
    drawSectionDivider(g2)
    laid.forEach { drawNode(g2, it) }
    if (galleryMode) drawGalleryHeaders(g2)
    // Edge labels last — as pills ON TOP of nodes + arrows — so they're never hidden behind a
    // screen box.
    edges.forEachIndexed { i, e -> drawEdgeLabel(g2, e, srcAnchorY[i], dstAnchorY[i]) }
    g2.transform = saved

    drawWireRubberBand(g2)
    drawZoomButtons(g2)
  }

  /**
   * The in-progress "Add transition" wire, drawn in screen space from the source handle to the
   * cursor.
   */
  private fun drawWireRubberBand(g2: Graphics2D) {
    val wf = wireFrom ?: return
    val wp = wirePoint ?: return
    val sx = ((wf.x + wf.w) * scale + tx).toInt()
    val sy = ((wf.y + wf.h / 2.0) * scale + ty).toInt()
    g2.color = theme.accent
    g2.stroke = STROKE2
    g2.drawLine(sx, sy, wp.x, wp.y)
    g2.fill(Ellipse2D.Double(wp.x - 3.0, wp.y - 3.0, 6.0, 6.0))
    g2.stroke = STROKE1
  }

  private fun drawNode(g2: Graphics2D, n: Laid) {
    val arc = theme.arc
    val box = RoundRectangle2D.Double(
      n.x.toDouble(),
      n.y.toDouble(),
      n.w.toDouble(),
      n.h.toDouble(),
      arc,
      arc,
    )
    g2.color = theme.nodeBg
    g2.fill(box)

    if (theme.showThumbnail && n.thumbH > 0) {
      val savedClip = g2.clip
      g2.clip(box)
      g2.clipRect(n.x, n.y, n.w, n.thumbH) // rounded-top preview region
      val thumb = n.thumb
      if (thumb != null && device.isAuto) {
        g2.drawImage(thumb, n.x, n.y, n.w, n.thumbH, null) // frame == image aspect → exact fill
      } else if (thumb != null) {
        g2.color = theme.thumbBg
        g2.fillRect(n.x, n.y, n.w, n.thumbH) // device frame → contain (letterbox)
        val s = minOf(n.w.toDouble() / thumb.width, n.thumbH.toDouble() / thumb.height)
        val dw = (thumb.width * s).toInt()
        val dh = (thumb.height * s).toInt()
        g2.drawImage(thumb, n.x + (n.w - dw) / 2, n.y + (n.thumbH - dh) / 2, dw, dh, null)
      } else {
        g2.color = theme.thumbBg
        g2.fillRect(n.x, n.y, n.w, n.thumbH)
        g2.color = theme.muted
        g2.font = labelFont
        val fm = g2.fontMetrics
        g2.drawString(
          "no preview",
          n.x + (n.w - fm.stringWidth("no preview")) / 2,
          n.y + n.thumbH / 2,
        )
      }
      g2.clip = savedClip

      g2.color = theme.border
      g2.drawLine(n.x, n.y + n.thumbH, n.x + n.w, n.y + n.thumbH)
    }

    g2.color = theme.title
    g2.font = titleFont
    val startGlyph = if (n.node.start && theme.emphasizeStart) "  ★" else ""
    g2.drawString(n.node.displayLabel() + startGlyph, n.x + PAD, n.y + n.thumbH + 17)

    if (theme.showArgs) {
      g2.font = argFont
      var ay = n.y + n.thumbH + TITLE_H + 11
      for (arg in n.node.args) {
        drawArg(g2, arg, n.x + PAD, ay)
        ay += ARG_H
      }
    }

    val highlighted = (n.node.start && theme.emphasizeStart) || n.node.id == hoverId
    g2.color = if (highlighted) theme.accent else theme.border
    g2.stroke = if (highlighted) STROKE2 else STROKE1
    g2.draw(box)
    g2.stroke = STROKE1

    // F4: a connector handle on the hovered node's right edge — press + drag it onto another
    // node to add a transition (@NavEdge). Hidden while a wire is already being dragged, to
    // keep the canvas uncluttered.
    // Suppressed in the gallery (no transitions there).
    if (!galleryMode && n.node.id == hoverId && wireFrom == null) {
      val hx = (n.x + n.w).toDouble()
      val hy = (n.y + n.h / 2.0)
      val circle = Ellipse2D.Double(hx - HANDLE_R, hy - HANDLE_R, HANDLE_R * 2, HANDLE_R * 2)
      g2.color = theme.accent
      g2.fill(circle)
      g2.color = theme.nodeBg
      val a = HANDLE_R * 0.5
      g2.drawLine((hx - a).toInt(), hy.toInt(), (hx + a).toInt(), hy.toInt())
      g2.drawLine(hx.toInt(), (hy - a).toInt(), hx.toInt(), (hy + a).toInt())
    }
  }

  private fun drawArg(g2: Graphics2D, arg: NavArgDto, x: Int, y: Int) {
    val fm = g2.fontMetrics
    var cx = x
    g2.color = theme.argName
    g2.drawString(arg.name, cx, y)
    cx += fm.stringWidth(arg.name)
    g2.color = theme.muted
    g2.drawString(": ", cx, y)
    cx += fm.stringWidth(": ")
    val type = displayType(arg)
    g2.color = theme.argType
    g2.drawString(type, cx, y)
    cx += fm.stringWidth(type)
    if (arg.optional) {
      g2.color = theme.muted
      g2.drawString(" = …", cx, y)
    }
  }

  private fun drawEdge(g2: Graphics2D, e: NavEdgeDto, y1: Double, y2: Double) {
    val from = byId[e.from] ?: return
    val to = byId[e.to] ?: return
    // Same-column edges (two stacked siblings) can't use the inter-column gap, so both ends
    // arc on the RIGHT as a tight C and the head points left into the target — instead of
    // looping across the whole box.
    if (Math.abs(from.x - to.x) < 1) {
      val sx = (from.x + from.w).toDouble()
      val tx = (to.x + to.w).toDouble()
      val baseX = tx + ARROW_LEN
      val path = Path2D.Double().apply {
        moveTo(sx, y1)
        when (theme.curveStyle) {
          CurveStyle.CURVED ->
            curveTo(sx + SAME_COL_BULGE, y1, baseX + SAME_COL_BULGE, y2, baseX, y2)

          CurveStyle.STRAIGHT -> {
            lineTo(baseX + SAME_COL_BULGE, (y1 + y2) / 2)
            lineTo(baseX, y2)
          }
        }
      }
      g2.color = theme.edge
      g2.stroke = theme.edgeStroke
      g2.draw(path)
      g2.stroke = STROKE1
      g2.fill(arrowHead(tx, baseX, y2))
      return
    }
    // Route by relative position: a forward edge exits the source's RIGHT and enters the
    // target's LEFT; a backward (leftward) edge exits LEFT and enters the target's RIGHT — so
    // the arrowhead is always on the side the curve approaches, pointing toward the target.
    // y1/y2 are the staggered per-edge anchors.
    val forward = (to.x + to.w / 2) >= (from.x + from.w / 2)
    val dir = if (forward) 1 else -1
    val x1 = (if (forward) from.x + from.w else from.x).toDouble()
    val x2 = (if (forward) to.x else to.x + to.w).toDouble()
    val bend = (Math.abs(x2 - x1) * 0.5).coerceAtLeast(55.0)
    // The line ends at the arrowhead BASE (not the tip) and approaches it horizontally, so
    // head and line meet cleanly however steeply the edge drops; the filled head caps it to
    // the edge.
    val baseX = x2 - dir * ARROW_LEN
    val path = Path2D.Double().apply {
      moveTo(x1, y1)
      when (theme.curveStyle) {
        CurveStyle.CURVED -> curveTo(x1 + dir * bend, y1, baseX - dir * bend, y2, baseX, y2)
        CurveStyle.STRAIGHT -> lineTo(baseX, y2)
      }
    }
    g2.color = theme.edge
    g2.stroke = theme.edgeStroke
    g2.draw(path)
    g2.stroke = STROKE1
    g2.fill(arrowHead(x2, baseX, y2))
  }

  /**
   * Filled triangular arrowhead with its tip at [tipX] and base at [baseX] (either side),
   * centred on [y].
   */
  private fun arrowHead(tipX: Double, baseX: Double, y: Double): Path2D.Double =
    Path2D.Double().apply {
      moveTo(tipX, y)
      lineTo(baseX, y - ARROW_W)
      lineTo(baseX, y + ARROW_W)
      closePath()
    }

  /**
   * Transition label as a rounded pill — white text on a dark, mostly-opaque background — at
   * the edge's horizontal midpoint, i.e. in the GAP between the two boxes so it never sits over
   * a screen. Painted in its own pass AFTER the nodes, so it always stays on top of both the
   * arrow and any box it would otherwise hide behind.
   */
  private fun drawEdgeLabel(g2: Graphics2D, e: NavEdgeDto, y1: Double, y2: Double) {
    if (!theme.showEdgeLabels) return
    val text = e.label?.takeIf { it.isNotEmpty() } ?: return
    val from = byId[e.from] ?: return
    val to = byId[e.to] ?: return
    val (cx, cy) = if (Math.abs(from.x - to.x) < 1) {
      // Same-column C-curve: ride the bulge out to the right of the boxes.
      (to.x + to.w + ARROW_LEN + SAME_COL_BULGE / 2.0) to (y1 + y2) / 2.0
    } else {
      val forward = (to.x + to.w / 2) >= (from.x + from.w / 2)
      val x1 = (if (forward) from.x + from.w else from.x).toDouble()
      val x2 = (if (forward) to.x else to.x + to.w).toDouble()
      (x1 + x2) / 2.0 to (y1 + y2) / 2.0
    }
    g2.font = labelFont
    val fm = g2.fontMetrics
    val tw = fm.stringWidth(text)
    val padX = 7.0
    val padY = 3.0
    val bgW = tw + padX * 2
    val bgH = fm.ascent + fm.descent + padY * 2
    g2.color = EDGE_LABEL_BG
    g2.fill(RoundRectangle2D.Double(cx - bgW / 2, cy - bgH / 2, bgW, bgH, bgH, bgH))
    g2.color = EDGE_LABEL_FG
    g2.drawString(text, (cx - tw / 2).toInt(), (cy - bgH / 2 + padY + fm.ascent).toInt())
  }

  /**
   * Per-edge anchor Y on each endpoint, fanned out so multiple edges touching one node don't
   * stack on a single point (the cause of arrowheads piling up on a sink). Edges sharing a
   * node-side are ordered by the opposite end's height and spread evenly down that side.
   * Returns parallel arrays indexed like [edges].
   */
  private fun staggerAnchors(
    edges: List<NavEdgeDto>,
    byId: Map<String, Laid>,
  ): Pair<DoubleArray, DoubleArray> {
    val srcY = DoubleArray(edges.size)
    val dstY = DoubleArray(edges.size)
    val sides = HashMap<Pair<String, Boolean>, MutableList<Triple<Int, Boolean, Double>>>()
    edges.forEachIndexed { i, e ->
      val from = byId[e.from] ?: return@forEachIndexed
      val to = byId[e.to] ?: return@forEachIndexed
      val sameCol = Math.abs(from.x - to.x) < 1
      val forward = (to.x + to.w / 2) >= (from.x + from.w / 2)
      sides.getOrPut(e.from to (sameCol || forward)) { mutableListOf() }
        .add(Triple(i, true, to.y + to.h / 2.0))
      sides.getOrPut(e.to to (sameCol || !forward)) { mutableListOf() }
        .add(Triple(i, false, from.y + from.h / 2.0))
    }
    sides.forEach { (key, slots) ->
      val node = byId[key.first] ?: return@forEach
      slots.sortedBy { it.third }.forEachIndexed { idx, s ->
        val y = node.y + node.h * (idx + 1.0) / (slots.size + 1)
        if (s.second) srcY[s.first] = y else dstY[s.first] = y
      }
    }
    return srcY to dstY
  }

  /**
   * Divider + label marking the start of the unconnected-screens section (only when the flow
   * has nodes).
   */
  private fun drawSectionDivider(g2: Graphics2D) {
    val touched = HashSet<String>()
    edges.forEach {
      touched.add(it.from)
      touched.add(it.to)
    }
    if (laid.none { it.node.id in touched }) return
    val isolatedTop = laid.filter { it.node.id !in touched }.minOfOrNull { it.y } ?: return
    val y = isolatedTop - SECTION_GAP / 2
    val right = laid.maxOf { it.x + it.w }
    g2.color = theme.muted
    g2.font = labelFont
    g2.drawString("Unconnected screens", MARGIN, y - 6)
    g2.color = theme.border
    g2.stroke = STROKE1
    g2.drawLine(MARGIN, y, right, y)
  }

  private fun drawZoomButtons(g2: Graphics2D) {
    val size = 30
    val gap = 6
    val margin = 14
    val xr = width - margin - size
    val yTop = height - margin - size * 3 - gap * 2
    zoomButtons = listOf(
      ZoomButton(Rectangle(xr, yTop, size, size), AllIcons.General.Add) {
        zoomAround(width / 2.0, height / 2.0, 1.2)
      },
      ZoomButton(Rectangle(xr, yTop + size + gap, size, size), AllIcons.General.Remove) {
        zoomAround(width / 2.0, height / 2.0, 1 / 1.2)
      },
      ZoomButton(Rectangle(xr, yTop + (size + gap) * 2, size, size), AllIcons.General.FitContent) {
        fit()
      },
    )
    for (b in zoomButtons) {
      val r = RoundRectangle2D.Double(
        b.rect.x.toDouble(),
        b.rect.y.toDouble(),
        b.rect.width.toDouble(),
        b.rect.height.toDouble(),
        8.0,
        8.0,
      )
      g2.color = theme.nodeBg
      g2.fill(r)
      g2.color = theme.border
      g2.draw(r)
      b.icon.paintIcon(
        this,
        g2,
        b.rect.x + (b.rect.width - b.icon.iconWidth) / 2,
        b.rect.y + (b.rect.height - b.icon.iconHeight) / 2,
      )
    }
  }

  private fun zoomAround(px: Double, py: Double, factor: Double) {
    val next = (scale * factor).coerceIn(0.2, 3.0)
    val gx = (px - tx) / scale
    val gy = (py - ty) / scale
    tx = px - gx * next
    ty = py - gy * next
    scale = next
    repaint()
  }

  private fun nodeAt(screenX: Int, screenY: Int): Laid? {
    val gx = (screenX - tx) / scale
    val gy = (screenY - ty) / scale
    return laid.firstOrNull { gx >= it.x && gx <= it.x + it.w && gy >= it.y && gy <= it.y + it.h }
  }

  /**
   * Hit-test the hovered node's connector handle (the graph-space circle at its right-edge
   * midpoint).
   */
  private fun handleHit(screenX: Int, screenY: Int): Laid? {
    if (galleryMode) return null
    val n = hoverId?.let { byId[it] } ?: return null
    val cx = (n.x + n.w).toDouble()
    val cy = (n.y + n.h / 2.0)
    val gx = (screenX - tx) / scale
    val gy = (screenY - ty) / scale
    val r = HANDLE_R + 3
    return if ((gx - cx) * (gx - cx) + (gy - cy) * (gy - cy) <= r * r) n else null
  }

  /**
   * Right-click menu: context-sensitive Map→Code actions, branched by what's under the cursor.
   */
  private fun showContextMenu(e: MouseEvent) {
    if (galleryMode) return // the gallery has no Map→Code actions; a right-click does nothing
    val hit = nodeAt(e.x, e.y)?.node
    val group = DefaultActionGroup()
    if (hit == null) {
      group.add(action("Add Destination…") { onAddDestination() })
    } else {
      if (hit.isClickable()) {
        group.add(action("Go to Destination") { onNodeActivated(hit) })
      } else {
        // an orphan: a NavKey with no @NavDestination yet
        group.add(action("Wire This Up…") { onWireUp(hit) })
      }
      group.add(action("Add Transition from Here…") { pickTransitionTarget(hit) })
      group.addSeparator()
      group.add(action("Add Destination…") { onAddDestination() })
    }
    JBPopupFactory.getInstance()
      .createActionGroupPopup(
        null,
        group,
        DataManager.getInstance().getDataContext(this),
        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
        true,
      )
      .show(RelativePoint(e))
  }

  private inline fun action(text: String, crossinline run: () -> Unit): AnAction =
    object : AnAction(text) {
      override fun getActionUpdateThread() = ActionUpdateThread.EDT
      override fun actionPerformed(e: AnActionEvent) = run()
    }

  /**
   * Menu alternative to the drag gesture: pick a target node from a list → add the transition
   * to it.
   */
  private fun pickTransitionTarget(source: NavNodeDto) {
    val targets = laid.map { it.node }.filter { it.id != source.id }
    if (targets.isEmpty()) return
    JBPopupFactory.getInstance()
      .createPopupChooserBuilder(targets)
      .setTitle("Add transition:  ${source.displayLabel()}  →")
      .setRenderer(SimpleListCellRenderer.create("") { it.displayLabel() })
      .setItemChosenCallback { onAddTransition(source, it) }
      .createPopup()
      .showInFocusCenter()
  }

  private fun fitToView() {
    if (laid.isEmpty() || width <= 0 || height <= 0) {
      tx = MARGIN.toDouble()
      ty = MARGIN.toDouble()
      scale = 1.0
      return
    }
    val contentW = (laid.maxOf { it.x + it.w } + MARGIN).toDouble()
    val contentH = (laid.maxOf { it.y + it.h } + MARGIN).toDouble()
    scale = minOf(1.0, (width - 24) / contentW, (height - 24) / contentH).coerceIn(0.2, 1.0)
    tx = 12.0
    ty = 12.0
  }

  private fun paintCentered(g2: Graphics2D, text: String, color: Color, f: Font) {
    g2.color = color
    g2.font = f
    val fm = g2.fontMetrics
    g2.drawString(text, (width - fm.stringWidth(text)) / 2, height / 2)
  }

  private fun NavNodeDto.isClickable() = clickTargetFqn != null || sourceFile != null

  private fun displayType(arg: NavArgDto): String {
    val base = arg.type.substringAfterLast('.')
    val generics = if (arg.typeArguments.isNotEmpty()) {
      "<" + arg.typeArguments.joinToString(", ") { it.substringAfterLast('.') } + ">"
    } else {
      ""
    }
    return base + generics + (if (arg.nullable) "?" else "")
  }

  companion object {
    /**
     * Display frames offered in the toolbar. First = Auto (each node at its rendered-preview
     * aspect).
     */
    val DEVICES = listOf(
      Device("Auto (preview size)", "Auto", 0, 0),
      // Android phones (portrait px). 1080×2400 = 20:9 is the modern baseline.
      Device("Pixel 9 Pro", "Android phone", 1280, 2856),
      Device("Pixel 9", "Android phone", 1080, 2424),
      Device("Pixel 8 Pro", "Android phone", 1344, 2992),
      Device("Pixel 8", "Android phone", 1080, 2400),
      Device("Pixel 7 Pro", "Android phone", 1440, 3120),
      Device("Pixel 7", "Android phone", 1080, 2400),
      Device("Pixel 6 Pro", "Android phone", 1440, 3120),
      Device("Pixel 6", "Android phone", 1080, 2400),
      Device("Pixel 5", "Android phone", 1080, 2340),
      Device("Pixel 4a", "Android phone", 1080, 2340),
      Device("Galaxy S24 Ultra", "Android phone", 1440, 3120),
      Device("Galaxy S24", "Android phone", 1080, 2340),
      Device("Galaxy S23", "Android phone", 1080, 2340),
      Device("Galaxy S22", "Android phone", 1080, 2340),
      Device("Galaxy S21", "Android phone", 1080, 2400),
      Device("Galaxy A55", "Android phone", 1080, 2340),
      Device("Galaxy A54", "Android phone", 1080, 2340),
      // Android foldables (primary = inner panel; cover listed separately)
      Device("Galaxy Z Fold 6 (unfolded)", "Android foldable", 1856, 2160),
      Device("Galaxy Z Fold 6 (folded)", "Android foldable", 968, 2376),
      Device("Galaxy Z Fold 5 (unfolded)", "Android foldable", 1812, 2176),
      Device("Galaxy Z Fold 5 (folded)", "Android foldable", 904, 2316),
      Device("Galaxy Z Flip 6 (open)", "Android foldable", 1080, 2640),
      Device("Galaxy Z Flip 5 (open)", "Android foldable", 1080, 2640),
      // Android tablets
      Device("Galaxy Tab S9", "Android tablet", 1600, 2560),
      Device("Pixel Tablet", "Android tablet", 1600, 2560),
      // iPhone (native panel px, point × scale). Newest first.
      Device("iPhone 16 Pro Max", "iPhone", 1320, 2868),
      Device("iPhone 16 Pro", "iPhone", 1206, 2622),
      Device("iPhone 16 Plus", "iPhone", 1290, 2796),
      Device("iPhone 16", "iPhone", 1179, 2556),
      Device("iPhone 15 Pro Max", "iPhone", 1290, 2796),
      Device("iPhone 15 Pro", "iPhone", 1179, 2556),
      Device("iPhone 15 Plus", "iPhone", 1290, 2796),
      Device("iPhone 15", "iPhone", 1179, 2556),
      Device("iPhone 14 Pro Max", "iPhone", 1290, 2796),
      Device("iPhone 14 Pro", "iPhone", 1179, 2556),
      Device("iPhone 14 Plus", "iPhone", 1284, 2778),
      Device("iPhone 14", "iPhone", 1170, 2532),
      Device("iPhone 13", "iPhone", 1170, 2532),
      Device("iPhone 13 mini", "iPhone", 1080, 2340),
      Device("iPhone 12 Pro Max", "iPhone", 1284, 2778),
      Device("iPhone 12", "iPhone", 1170, 2532),
      Device("iPhone 12 mini", "iPhone", 1080, 2340),
      Device("iPhone SE (3rd gen)", "iPhone", 750, 1334),
      // iPad
      Device("iPad Pro 13\"", "iPad", 2064, 2752),
      Device("iPad Pro 11\"", "iPad", 1668, 2420),
      Device("iPad (10th gen)", "iPad", 1640, 2360),
      // Desktop
      Device("Desktop 16:10", "Desktop", 1920, 1200),
      Device("Desktop 16:9", "Desktop", 1920, 1080),
    )

    // Fixed geometry not exposed as settings: glyph metrics, margins, arrowhead, default aspect.
    // Customizable geometry (box width, corner radius, column/row gaps) lives in NavGraphTheme.
    private const val TITLE_H = 26
    private const val ARG_H = 16
    private const val PAD = 10
    private const val MARGIN = 40
    private const val HANDLE_R = 6.0 // F4 connector-handle radius (graph space)

    // gap above the unconnected-screens section (divider sits in its middle)
    private const val SECTION_GAP = 72

    // height reserved above each gallery module section for its header
    private const val GALLERY_HEADER_H = 30

    // section label for a node with no module
    private const val GALLERY_THIS_MODULE = "(this module)"
    private const val ARROW_LEN = 11.0
    private const val ARROW_W = 5.0

    // how far a same-column edge's C-curve bows out past the right edge
    private const val SAME_COL_BULGE = 46.0
    private const val DEFAULT_RATIO = 2400.0 / 1080.0

    private val DEFAULT_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 12)
    private val STROKE1 = BasicStroke(1f)
    private val STROKE2 = BasicStroke(2f)

    // dark, mostly-opaque pill — readable on any theme
    private val EDGE_LABEL_BG = Color(30, 31, 34, 224)
    private val EDGE_LABEL_FG = Color.WHITE
  }
}
