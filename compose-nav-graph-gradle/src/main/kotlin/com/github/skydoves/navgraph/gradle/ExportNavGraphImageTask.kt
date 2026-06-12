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
package com.github.skydoves.navgraph.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the merged `nav-graph.json` (+ thumbnail PNGs) into a single hi-DPI PNG: the same node boxes,
 * rendered `@Preview` thumbnails, typed args, and transition arrows the IDE's `NavGraphCanvas` paints, but
 * drawn headlessly with Java2D (`java.awt` + `javax.imageio`) — `compose-nav-graph-gradle` is plain `kotlin-dsl` with no
 * IntelliJ dependency, so the canvas itself cannot be reused; its drawing is ported here.
 *
 * The layout + edge geometry + colors are a faithful port of `NavGraphCanvas.kt` (light theme), mirroring the
 * HTML task's port (`ExportNavGraphHtmlTask.kt`). See the `Geometry`/`Theme` blocks — keep in sync with both.
 * Reads the manifest via the shared `parseGraph` / `HGraph` model in `NavManifest.kt`.
 */
@CacheableTask
public abstract class ExportNavGraphImageTask : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val manifest: RegularFileProperty

  // The thumbnail dir is declared so the PNG set is part of the cache key; a no-preview module has none.
  @get:InputDirectory
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val thumbsDir: DirectoryProperty

  /** "" = Auto (each node at its rendered aspect); else "WxH" device frame (letterbox), e.g. "1080x2400". */
  @get:Input
  @get:Optional
  public abstract val deviceSpec: Property<String>

  /** Supersampling factor — the image is rendered at this multiple and `g2.scale(scale)`d for crisp hi-DPI. */
  @get:Input
  @get:Optional
  public abstract val scale: Property<Int>

  @get:OutputFile
  public abstract val outputImage: RegularFileProperty

  @TaskAction
  public fun export() {
    val manifestFile = manifest.get().asFile
    val graph = parseGraph(manifestFile.readText())
    val thumbsRoot: File? = thumbsDir.orNull?.asFile
    val device = parseDevice(deviceSpec.orNull)
    val s = (scale.orNull ?: DEFAULT_SCALE).coerceIn(1, 8)

    val out = outputImage.get().asFile
    out.parentFile?.mkdirs()
    val img = render(graph, thumbsRoot, device, s)
    ImageIO.write(img, "png", out)
    logger.lifecycle(
      "navgraph: wrote ${out.path} (${out.length() / 1024} KB) — ${graph.nodes.size} nodes, ${graph.edges.size} edges.",
    )
  }

  // The manifest model (HGraph/HNode/…), `parseGraph`, and `displayType` live in NavManifest.kt — shared with
  // the baseline tasks (NavDumpTask / NavCheckTask) and the HTML export.

  // resolved-per-node thumbnail (decoded once; width/height drive the aspect ratio in thumbHeight)
  private class Thumb(val image: BufferedImage, val w: Int, val h: Int)
  private class Laid(
    val node: HNode,
    val x: Int,
    val y: Int,
    val w: Int,
    val thumbH: Int,
    val h: Int,
    val thumb: Thumb?,
  )

  private fun render(
    graph: HGraph,
    thumbsRoot: File?,
    device: Pair<Int, Int>?,
    scale: Int,
  ): BufferedImage {
    if (graph.nodes.isEmpty()) return emptyImage(scale)

    // resolve thumbnails (decoded image + aspect), then lay out (BFS columns) — ports NavGraphCanvas.layout()
    val thumbs: Map<String, Thumb?> = graph.nodes.associate { it.id to embed(it, thumbsRoot) }
    val laid = layout(graph, thumbs, device)
    val byId = laid.associateBy { it.node.id }
    val contentW = laid.maxOf { it.x + it.w } + MARGIN
    val contentH = laid.maxOf { it.y + it.h } + MARGIN

    val img = BufferedImage(contentW * scale, contentH * scale, BufferedImage.TYPE_INT_ARGB)
    val g2 = img.createGraphics()
    try {
      applyHints(g2)
      g2.color = BACKGROUND
      g2.fillRect(0, 0, contentW * scale, contentH * scale)
      g2.scale(scale.toDouble(), scale.toDouble())
      // Edges first so node boxes paint over the arrow tails (mirrors NavGraphCanvas.paintComponent).
      val (srcAnchorY, dstAnchorY) = staggerAnchors(graph, byId)
      graph.edges.forEachIndexed { i, e ->
        val from = byId[e.from] ?: return@forEachIndexed
        val to = byId[e.to] ?: return@forEachIndexed
        drawEdge(g2, from, to, srcAnchorY[i], dstAnchorY[i])
      }
      drawSectionDivider(g2, graph, laid, contentW)
      laid.forEach { drawNode(g2, it, device) }
    } finally {
      g2.dispose()
    }
    return img
  }

  // ── Painting — keep in sync with NavGraphCanvas.kt (drawNode/drawArg/drawEdge/paintComponent) ────────────

  private fun drawNode(g2: Graphics2D, n: Laid, device: Pair<Int, Int>?) {
    val box = RoundRectangle2D.Double(
      n.x.toDouble(),
      n.y.toDouble(),
      n.w.toDouble(),
      n.h.toDouble(),
      ARC,
      ARC,
    )
    g2.color = NODE_BG
    g2.fill(box)

    if (n.thumbH > 0) {
      val savedClip = g2.clip
      g2.clip(box)
      g2.clipRect(n.x, n.y, n.w, n.thumbH) // rounded-top preview region
      val thumb = n.thumb
      if (thumb != null && device == null) {
        // Auto: frame == image aspect → exact fill
        g2.drawImage(thumb.image, n.x, n.y, n.w, n.thumbH, null)
      } else if (thumb != null) {
        g2.color = THUMB_BG
        g2.fillRect(n.x, n.y, n.w, n.thumbH) // device frame → contain (letterbox)
        val scl = minOf(n.w.toDouble() / thumb.w, n.thumbH.toDouble() / thumb.h)
        val dw = (thumb.w * scl).toInt()
        val dh = (thumb.h * scl).toInt()
        g2.drawImage(thumb.image, n.x + (n.w - dw) / 2, n.y + (n.thumbH - dh) / 2, dw, dh, null)
      } else {
        g2.color = THUMB_BG
        g2.fillRect(n.x, n.y, n.w, n.thumbH)
        g2.color = MUTED
        g2.font = LABEL_FONT
        val fm = g2.fontMetrics
        g2.drawString(
          "no preview",
          n.x + (n.w - fm.stringWidth("no preview")) / 2,
          n.y + n.thumbH / 2,
        )
      }
      g2.clip = savedClip

      g2.color = BORDER
      g2.drawLine(n.x, n.y + n.thumbH, n.x + n.w, n.y + n.thumbH)
    }

    g2.color = TITLE
    g2.font = TITLE_FONT
    // The composable's name (e.g. "SearchScreen"), falling back to the route — shared with the canvas/HTML.
    val startGlyph = if (n.node.start) "  ★" else ""
    g2.drawString(n.node.displayLabel() + startGlyph, n.x + PAD, n.y + n.thumbH + 17)

    g2.font = ARG_FONT
    var ay = n.y + n.thumbH + TITLE_H + 11
    for (arg in n.node.args) {
      drawArg(g2, arg, n.x + PAD, ay)
      ay += ARG_H
    }

    val highlighted = n.node.start
    g2.color = if (highlighted) ACCENT else BORDER
    g2.stroke = if (highlighted) STROKE2 else STROKE1
    g2.draw(box)
    g2.stroke = STROKE1
  }

  private fun drawArg(g2: Graphics2D, arg: HArg, x: Int, y: Int) {
    val fm = g2.fontMetrics
    var cx = x
    g2.color = ARG_NAME
    g2.drawString(arg.name, cx, y)
    cx += fm.stringWidth(arg.name)
    g2.color = MUTED
    g2.drawString(": ", cx, y)
    cx += fm.stringWidth(": ")
    val type = displayType(arg)
    g2.color = ARG_TYPE
    g2.drawString(type, cx, y)
    cx += fm.stringWidth(type)
    if (arg.optional) {
      g2.color = MUTED
      g2.drawString(" = …", cx, y)
    }
  }

  /**
   * Per-edge anchor Y on each endpoint, fanned out so multiple edges touching one node don't stack on a single
   * point (the cause of arrowheads piling up on a sink). Edges sharing a node-side are ordered by the opposite
   * end's height and spread evenly down that side. Returns parallel arrays indexed like [HGraph.edges].
   */
  private fun staggerAnchors(
    graph: HGraph,
    byId: Map<String, Laid>,
  ): Pair<DoubleArray, DoubleArray> {
    val srcY = DoubleArray(graph.edges.size)
    val dstY = DoubleArray(graph.edges.size)
    // (nodeId, isRightSide) -> list of (edgeIndex, isSource, opposite-end centre-y) using that side.
    val sides = HashMap<Pair<String, Boolean>, MutableList<Triple<Int, Boolean, Double>>>()
    graph.edges.forEachIndexed { i, e ->
      val from = byId[e.from] ?: return@forEachIndexed
      val to = byId[e.to] ?: return@forEachIndexed
      // Same-column edges arc on the right side (both ends right); others use the facing sides.
      val sameCol = Math.abs(from.x - to.x) < 1
      val forward = (to.x + to.w / 2) >= (from.x + from.w / 2)
      sides.getOrPut(e.from to (sameCol || forward)) { mutableListOf() }.add(
        Triple(
          i,
          true,
          to.y + to.h / 2.0,
        ),
      )
      sides.getOrPut(e.to to (sameCol || !forward)) { mutableListOf() }.add(
        Triple(
          i,
          false,
          from.y + from.h / 2.0,
        ),
      )
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

  private fun drawEdge(g2: Graphics2D, from: Laid, to: Laid, y1: Double, y2: Double) {
    // Same-column edges (two stacked siblings) can't use the inter-column gap, so both ends arc on the RIGHT
    // as a tight C and the head points left into the target — instead of looping across the whole box.
    if (Math.abs(from.x - to.x) < 1) {
      val sx = (from.x + from.w).toDouble()
      val tx = (to.x + to.w).toDouble()
      val baseX = tx + ARROW_LEN
      val path = Path2D.Double().apply {
        moveTo(sx, y1)
        curveTo(sx + SAME_COL_BULGE, y1, baseX + SAME_COL_BULGE, y2, baseX, y2)
      }
      g2.color = EDGE
      g2.stroke = EDGE_STROKE
      g2.draw(path)
      g2.stroke = STROKE1
      g2.fill(arrowHead(tx, baseX, y2))
      return
    }
    // Route by relative position: a forward edge exits the source's RIGHT and enters the target's LEFT; a
    // backward (leftward) edge exits LEFT and enters the target's RIGHT — so the arrowhead is always on the
    // side the curve approaches, pointing toward the target. y1/y2 are the staggered per-edge anchors.
    val forward = (to.x + to.w / 2) >= (from.x + from.w / 2)
    val dir = if (forward) 1 else -1
    val x1 = (if (forward) from.x + from.w else from.x).toDouble()
    val x2 = (if (forward) to.x else to.x + to.w).toDouble()
    val bend = (Math.abs(x2 - x1) * 0.5).coerceAtLeast(55.0)
    // The line ends at the arrowhead BASE (not the tip) and approaches it horizontally, so head and line meet
    // cleanly however steeply the edge drops; the filled head caps it to the edge.
    val baseX = x2 - dir * ARROW_LEN
    val path = Path2D.Double().apply {
      moveTo(x1, y1)
      curveTo(x1 + dir * bend, y1, baseX - dir * bend, y2, baseX, y2)
    }
    g2.color = EDGE
    g2.stroke = EDGE_STROKE
    g2.draw(path)
    g2.stroke = STROKE1
    g2.fill(arrowHead(x2, baseX, y2))
  }

  /** Filled triangular arrowhead with its tip at [tipX] and base at [baseX] (either side), centred on [y]. */
  private fun arrowHead(tipX: Double, baseX: Double, y: Double): Path2D.Double =
    Path2D.Double().apply {
      moveTo(tipX, y)
      lineTo(baseX, y - ARROW_W)
      lineTo(baseX, y + ARROW_W)
      closePath()
    }

  private fun emptyImage(scale: Int): BufferedImage {
    val w = 360
    val h = 120
    val img = BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_ARGB)
    val g2 = img.createGraphics()
    try {
      applyHints(g2)
      g2.color = BACKGROUND
      g2.fillRect(0, 0, w * scale, h * scale)
      g2.scale(scale.toDouble(), scale.toDouble())
      g2.color = MUTED
      g2.font = TITLE_FONT.deriveFont(13f)
      val fm = g2.fontMetrics
      val text = "No navigation graph"
      g2.drawString(text, (w - fm.stringWidth(text)) / 2, h / 2)
    } finally {
      g2.dispose()
    }
    return img
  }

  /**
   * Resolves a node's thumbnail PNG by the HTML task's `embed` rule: primary preview (else first), basename
   * looked up under [thumbsRoot]; decoded once with [ImageIO]. Its width/height drive the aspect ratio.
   */
  private fun embed(node: HNode, thumbsRoot: File?): Thumb? {
    if (thumbsRoot == null) return null
    val rel =
      (node.previews.firstOrNull { it.primary } ?: node.previews.firstOrNull())?.thumbnail
        ?: return null
    val file = thumbsRoot.resolve(rel.substringAfterLast('/'))
    if (!file.isFile) return null
    return try {
      val dim = ImageIO.read(file) ?: return null
      Thumb(dim, dim.width, dim.height)
    } catch (_: Exception) {
      null
    }
  }

  // ── Geometry — keep in sync with NavGraphCanvas.kt / ExportNavGraphHtmlTask.kt (layout/thumbHeight) ───────
  private fun thumbHeight(t: Thumb?, device: Pair<Int, Int>?): Int {
    val ratio = when {
      device != null -> device.second.toDouble() / device.first
      t != null && t.w > 0 -> t.h.toDouble() / t.w
      else -> DEFAULT_RATIO
    }
    return (BOX_W * ratio).toInt().coerceIn(90, 560)
  }

  private fun layout(
    graph: HGraph,
    thumbs: Map<String, Thumb?>,
    device: Pair<Int, Int>?,
  ): List<Laid> {
    val ids = graph.nodes.map { it.id }.toHashSet()
    val adj = HashMap<String, MutableList<String>>()
    graph.edges.forEach {
      if (it.from in ids &&
        it.to in ids
      ) {
        adj.getOrPut(it.from) { mutableListOf() }.add(it.to)
      }
    }

    // Screens with no transition at all (no in- or out-edge) are pulled out of the flow into a separate bottom
    // section, so they don't clutter the connected graph. The rest lay out in BFS lanes.
    val touched = HashSet<String>()
    graph.edges.forEach {
      if (it.from in ids && it.to in ids) {
        touched.add(it.from)
        touched.add(it.to)
      }
    }
    val connected = graph.nodes.filter { it.id in touched }
    val isolated = graph.nodes.filter { it.id !in touched }

    val depth = HashMap<String, Int>()
    val queue = ArrayDeque<String>()
    // Seed lanes from the real entry points: marked start(s) plus every in-degree-0 source among connected
    // nodes (so a multi-root merged graph lays out compactly instead of spilling each root into its own lane),
    // else the first connected node.
    val indeg = HashMap<String, Int>().apply { graph.nodes.forEach { put(it.id, 0) } }
    graph.edges.forEach {
      if (it.from in ids && it.to in ids &&
        it.from != it.to
      ) {
        indeg[it.to] = (indeg[it.to] ?: 0) + 1
      }
    }
    val starts = connected.filter { it.start || indeg[it.id] == 0 }.map { it.id }
      .ifEmpty { connected.take(1).map { it.id } }
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
    connected.forEach {
      if (it.id !in depth) {
        maxDepth++
        depth[it.id] = maxDepth
      }
    }

    fun box(node: HNode, x: Int, y: Int): Laid {
      val thumb = thumbs[node.id]
      val th = thumbHeight(thumb, device)
      return Laid(node, x, y, BOX_W, th, th + TITLE_H + node.args.size * ARG_H + PAD, thumb)
    }

    val colY = HashMap<Int, Int>()
    val connectedLaid = connected.sortedBy { depth.getValue(it.id) }.map { node ->
      val d = depth.getValue(node.id)
      val laid = box(node, MARGIN + d * (BOX_W + COL_GAP), MARGIN + (colY[d] ?: 0))
      colY[d] = (colY[d] ?: 0) + laid.h + ROW_GAP
      laid
    }
    if (isolated.isEmpty()) return connectedLaid

    // The unconnected section: a grid below the flow, wrapped to the graph's column count.
    val sectionTop = (connectedLaid.maxOfOrNull { it.y + it.h } ?: MARGIN) + SECTION_GAP
    val perRow = (maxDepth + 1).coerceAtLeast(1)
    var col = 0
    var rowTop = sectionTop
    var rowH = 0
    val isolatedLaid = isolated.map { node ->
      if (col >= perRow) {
        col = 0
        rowTop += rowH + ROW_GAP
        rowH = 0
      }
      val laid = box(node, MARGIN + col * (BOX_W + COL_GAP), rowTop)
      col++
      rowH = maxOf(rowH, laid.h)
      laid
    }
    return connectedLaid + isolatedLaid
  }

  /** Divider + label marking the start of the unconnected-screens section (only when the flow has nodes). */
  private fun drawSectionDivider(g2: Graphics2D, graph: HGraph, laid: List<Laid>, contentW: Int) {
    val touched = HashSet<String>()
    graph.edges.forEach {
      touched.add(it.from)
      touched.add(it.to)
    }
    if (laid.none { it.node.id in touched }) return
    val isolatedTop = laid.filter { it.node.id !in touched }.minOfOrNull { it.y } ?: return
    val y = isolatedTop - SECTION_GAP / 2
    g2.color = MUTED
    g2.font = LABEL_FONT
    g2.drawString("Unconnected screens", MARGIN, y - 6)
    g2.color = BORDER
    g2.stroke = STROKE1
    g2.drawLine(MARGIN, y, contentW - MARGIN, y)
  }

  private companion object {
    const val DEFAULT_SCALE = 2

    // ── Geometry constants — identical to NavGraphCanvas.kt / ExportNavGraphHtmlTask.kt. ──
    const val BOX_W = 240
    const val TITLE_H = 26
    const val ARG_H = 16
    const val PAD = 10
    const val MARGIN = 40
    const val COL_GAP = 90
    const val ROW_GAP = 26

    // gap above the unconnected-screens section (divider sits in its middle)
    const val SECTION_GAP = 72
    const val ARROW_LEN = 11.0
    const val ARROW_W = 5.0

    // how far a same-column edge's C-curve bows out past the right edge
    const val SAME_COL_BULGE = 46.0
    const val DEFAULT_RATIO = 2400.0 / 1080.0
    const val ARC = 14.0

    // ── Theme — LIGHT halves of NavGraphCanvas's JBColors (NavGraphTheme.kt / NavGraphSettings.kt). ──
    val BACKGROUND = Color(0xFBFCFE) // settings.backgroundRGB (light)
    val NODE_BG = Color(0xFFFFFF) // settings.nodeFillRGB (light)
    val ACCENT = Color(0x6750A4) // settings.accentColorRGB (light)
    val EDGE = Color(0x3377D6) // settings.edgeColorRGB (light)
    val THUMB_BG = Color(0xF1, 0xF3, 0xF5) // LightDefaults.THUMB_BG
    val BORDER = Color(0xD6, 0xD9, 0xDE) // LightDefaults.BORDER
    val TITLE = Color(0x20, 0x21, 0x24) // LightDefaults.TITLE
    val ARG_NAME = Color(0x1A, 0x73, 0xE8) // LightDefaults.ARG_NAME
    val ARG_TYPE = Color(0x18, 0x80, 0x38) // LightDefaults.ARG_TYPE
    val MUTED = Color(0x80, 0x86, 0x8B) // LightDefaults.MUTED

    // ── Fonts — mirror NavGraphCanvas (titleFont BOLD 13, argFont MONOSPACED 11, labelFont 10). ──
    val TITLE_FONT = Font(Font.SANS_SERIF, Font.BOLD, 13)
    val ARG_FONT = Font(Font.MONOSPACED, Font.PLAIN, 11)
    val LABEL_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 10)

    // ── Strokes — STROKE1/STROKE2 from NavGraphCanvas; edge uses the default 1px settings thickness. ──
    val STROKE1 = BasicStroke(1f)
    val STROKE2 = BasicStroke(2f)
    val EDGE_STROKE = BasicStroke(1f)

    fun applyHints(g2: Graphics2D) {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION,
        RenderingHints.VALUE_INTERPOLATION_BILINEAR,
      )
      g2.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
      )
    }

    fun parseDevice(spec: String?): Pair<Int, Int>? {
      val parts = spec?.split('x', 'X')?.map { it.trim().toIntOrNull() } ?: return null
      if (parts.size != 2) return null
      val (w, h) = parts
      return if (w != null && h != null && w > 0 && h > 0) w to h else null
    }
  }
}
