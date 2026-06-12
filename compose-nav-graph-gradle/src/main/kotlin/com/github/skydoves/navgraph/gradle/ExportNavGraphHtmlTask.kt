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
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Renders the merged `nav-graph.json` (+ thumbnail PNGs) into a single self-contained, interactive HTML
 * file: rendered `@Preview` images, transition arrows, typed args, and a searchable details table — base64
 * images + inline CSS/JS, no external dependencies, opens offline in any browser.
 *
 * The layout + edge geometry are a faithful port of `NavGraphCanvas.kt` (kept in sync; see the `Geometry`
 * block). Reads the manifest via local `@Serializable` mirrors so this stays free of `compose-nav-graph-annotations`.
 */
@CacheableTask
public abstract class ExportNavGraphHtmlTask : DefaultTask() {

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

  @get:OutputFile
  public abstract val outputHtml: RegularFileProperty

  // The CSS/JS templates are classpath resources, not file inputs — fingerprint them so editing a
  // template invalidates a cached/up-to-date result.
  @get:Input
  public val templateFingerprint: Int
    get() = resource(CSS_PATH).hashCode() * 31 + resource(JS_PATH).hashCode()

  @TaskAction
  public fun export() {
    val manifestFile = manifest.get().asFile
    val graph = parseGraph(manifestFile.readText())
    val thumbsRoot: File? = thumbsDir.orNull?.asFile
    val device = parseDevice(deviceSpec.orNull)
    // "Generated" date = the graph data's timestamp (manifest mtime), not now() — keeps the output
    // reproducible for a given graph instead of changing on every run.
    val date = LocalDate.ofInstant(
      Instant.ofEpochMilli(manifestFile.lastModified()),
      ZoneId.systemDefault(),
    ).toString()

    val out = outputHtml.get().asFile
    out.parentFile?.mkdirs()
    out.writeText(render(graph, thumbsRoot, device, date))
    logger.lifecycle(
      "navgraph: wrote ${out.path} (${out.length() / 1024} KB) — ${graph.nodes.size} nodes, ${graph.edges.size} edges.",
    )
  }

  // The manifest model (HGraph/HNode/…), `parseGraph`, `displayType`, and the JSON accessors live in
  // NavManifest.kt — shared with the baseline tasks (NavDumpTask / NavCheckTask).

  // resolved-per-node embedded thumbnail
  private class Thumb(val dataUri: String, val w: Int, val h: Int)
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
    date: String,
  ): String {
    val css = resource(CSS_PATH)
    val js = resource(JS_PATH)

    if (graph.nodes.isEmpty()) return emptyHtml(css, date)

    // resolve thumbnails (base64 + aspect), then lay out (BFS columns) — ports NavGraphCanvas.layout()
    val thumbs: Map<String, Thumb?> = graph.nodes.associate { it.id to embed(it, thumbsRoot) }
    val laid = layout(graph, thumbs, device)
    val byId = laid.associateBy { it.node.id }
    val routeById = graph.nodes.associate { it.id to it.route }
    // Same-column edges bow out to the right past the box, so include that in the canvas width (else clipped).
    val edgeRight = graph.edges.maxOfOrNull { e ->
      val f = byId[e.from]
      val t = byId[e.to]
      if (f != null && t != null &&
        Math.abs(f.x - t.x) < 1
      ) {
        t.x + t.w + (SAME_COL_BULGE + ARROW_LEN).toInt()
      } else {
        0
      }
    } ?: 0
    val contentW = maxOf(laid.maxOf { it.x + it.w } + MARGIN, edgeRight + 8)
    val contentH = (laid.maxOf { it.y + it.h } + MARGIN)

    val edgesSvg = buildString {
      val (srcY, dstY) = staggerAnchors(graph, byId)
      graph.edges.forEachIndexed { i, e ->
        val from = byId[e.from] ?: return@forEachIndexed
        val to = byId[e.to] ?: return@forEachIndexed
        append(edgeMarkup(e, from, to, srcY[i], dstY[i]))
      }
      append(dividerMarkup(graph, byId, contentW))
    }
    val nodesHtml = buildString {
      laid.forEachIndexed { i, l ->
        append(
          nodeMarkup(
            i,
            l,
            device != null,
          ),
        )
      }
    }
    val rowsHtml =
      buildString {
        laid.forEachIndexed { i, l -> append(rowMarkup(i, l, graph.edges, routeById)) }
      }

    return buildString {
      append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
      append("<meta charset=\"utf-8\">\n")
      append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
      append("<title>NavGraph graph — ").append(esc(date)).append("</title>\n")
      append("<style>\n").append(css).append("\n</style>\n</head>\n<body>\n")
      append("<header class=\"toolbar\">\n")
      append("  <h1>Navigation graph</h1>\n")
      append("  <span class=\"counts\">").append(graph.nodes.size).append(" screens · ")
        .append(graph.edges.size).append(" transitions · ").append(esc(date)).append("</span>\n")
      append("  <span class=\"spacer\"></span>\n")
      append(
        "  <input id=\"search\" class=\"search\" type=\"search\" placeholder=\"Filter route or arg…\" autocomplete=\"off\">\n",
      )
      append(
        "  <button id=\"theme\" class=\"btn\" title=\"Theme (auto / light / dark)\">🌗</button>\n",
      )
      append(
        "  <span class=\"zoomgroup\"><button id=\"zout\" class=\"btn\" title=\"Zoom out\">−</button>",
      )
        .append("<button id=\"zfit\" class=\"btn\" title=\"Fit\">Fit</button>")
        .append("<button id=\"zin\" class=\"btn\" title=\"Zoom in\">+</button></span>\n")
      append(
        "  <span class=\"legend\"><span><b>★</b> start</span><span><b>→</b> transition</span></span>\n",
      )
      append("</header>\n<main>\n")
      append(
        "<div id=\"stage\">\n  <div id=\"canvas\" style=\"width:",
      ).append(contentW).append("px;height:")
        .append(contentH).append("px\">\n")
      append(
        "    <svg class=\"edges\" width=\"",
      ).append(contentW).append("\" height=\"").append(contentH)
        .append("\" viewBox=\"0 0 ").append(contentW).append(' ').append(contentH).append("\">\n")
      append(edgesSvg)
      append("    </svg>\n")
      append(nodesHtml)
      append(
        "  </div>\n  <div id=\"minimap\" class=\"minimap\" title=\"Minimap — click to navigate\"></div>\n</div>\n</main>\n",
      )
      append("<section id=\"details\">\n  <h2>Screens</h2>\n  <table class=\"detail-table\">\n")
      append("    <thead><tr><th>Route</th><th>Module</th><th>Arguments</th><th>Source</th>")
        .append("<th>In</th><th>Out</th><th>Preview</th></tr></thead>\n    <tbody>\n")
      append(rowsHtml)
      append("    </tbody>\n  </table>\n</section>\n")
      append("<script>\n").append(js).append("\n</script>\n</body>\n</html>\n")
    }
  }

  // ── Geometry — keep in sync with NavGraphCanvas.kt (layout/drawEdge/thumbHeight/displayType) ─────────
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

    // Screens with no transition at all (no in- or out-edge) are pulled out into a separate bottom section so
    // they don't clutter the connected flow (kept in sync with ExportNavGraphImageTask).
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
    // nodes (so a multi-root merged graph lays out compactly), else the first connected node.
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
    val sides = HashMap<Pair<String, Boolean>, MutableList<Triple<Int, Boolean, Double>>>()
    graph.edges.forEachIndexed { i, e ->
      val from = byId[e.from] ?: return@forEachIndexed
      val to = byId[e.to] ?: return@forEachIndexed
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

  /** SVG divider + label marking the unconnected-screens section (only when the flow has connected nodes). */
  private fun dividerMarkup(graph: HGraph, byId: Map<String, Laid>, contentW: Int): String {
    val touched = HashSet<String>()
    graph.edges.forEach {
      touched.add(it.from)
      touched.add(it.to)
    }
    if (byId.values.none { it.node.id in touched }) return ""
    val isolatedTop =
      byId.values.filter { it.node.id !in touched }.minOfOrNull { it.y } ?: return ""
    val y = isolatedTop - SECTION_GAP / 2
    return buildString {
      append("      <line x1=\"").append(MARGIN).append("\" y1=\"").append(y).append("\" x2=\"")
        .append(
          contentW - MARGIN,
        ).append(
          "\" y2=\"",
        ).append(y).append("\" style=\"stroke:var(--border);stroke-width:1\"/>\n")
      append("      <text x=\"").append(MARGIN).append("\" y=\"").append(y - 6)
        .append("\" style=\"fill:var(--muted);font:10px sans-serif\">Unconnected screens</text>\n")
    }
  }

  private fun edgeMarkup(e: HEdge, from: Laid, to: Laid, y1: Double, y2: Double): String {
    // Same-column edges (two stacked siblings) arc on the RIGHT as a tight C with the head pointing left into
    // the target; others route through the inter-column gap. y1/y2 are the staggered per-edge anchors.
    val path: String
    val head: String
    val mx: Double
    if (Math.abs(from.x - to.x) < 1) {
      val sx = (from.x + from.w).toDouble()
      val tx = (to.x + to.w).toDouble()
      val baseX = tx + ARROW_LEN
      path =
        "M ${n(
          sx,
        )},${n(
          y1,
        )} C ${n(
          sx + SAME_COL_BULGE,
        )},${n(y1)} ${n(baseX + SAME_COL_BULGE)},${n(y2)} ${n(baseX)},${n(y2)}"
      head = "${n(tx)},${n(y2)} ${n(baseX)},${n(y2 - ARROW_W)} ${n(baseX)},${n(y2 + ARROW_W)}"
      mx = (sx + baseX) / 2
    } else {
      val forward = (to.x + to.w / 2) >= (from.x + from.w / 2)
      val dir = if (forward) 1 else -1
      val x1 = (if (forward) from.x + from.w else from.x).toDouble()
      val x2 = (if (forward) to.x else to.x + to.w).toDouble()
      val bend = (Math.abs(x2 - x1) * 0.5).coerceAtLeast(55.0)
      val baseX = x2 - dir * ARROW_LEN
      path =
        "M ${n(
          x1,
        )},${n(
          y1,
        )} C ${n(x1 + dir * bend)},${n(y1)} ${n(baseX - dir * bend)},${n(y2)} ${n(baseX)},${n(y2)}"
      head = "${n(x2)},${n(y2)} ${n(baseX)},${n(y2 - ARROW_W)} ${n(baseX)},${n(y2 + ARROW_W)}"
      mx = (x1 + x2) / 2
    }
    return buildString {
      append("      <g class=\"edge-g\" data-from=\"").append(esc(e.from)).append("\" data-to=\"")
        .append(esc(e.to)).append("\">\n")
      append("        <path class=\"edge\" d=\"").append(path).append("\"/>\n")
      append("        <polygon class=\"edge-head\" points=\"").append(head).append("\"/>\n")
      e.label?.takeIf { it.isNotEmpty() }?.let {
        val ly = (y1 + y2) / 2 - 4
        append(
          "        <text class=\"edge-label\" x=\"",
        ).append(n(mx)).append("\" y=\"").append(n(ly))
          .append("\" text-anchor=\"middle\">").append(esc(it)).append("</text>\n")
      }
      append("      </g>\n")
    }
  }

  private fun nodeMarkup(idx: Int, l: Laid, deviceFrame: Boolean): String {
    val node = l.node
    val argsPlain = node.args.joinToString(", ") { "${it.name}: ${displayType(it)}" }
    return buildString {
      append("    <article class=\"node").append(if (node.start) " start" else "")
        .append("\" id=\"n-").append(idx).append("\" data-idx=\"").append(idx)
        .append(
          "\" data-id=\"",
        ).append(esc(node.id)).append("\" data-route=\"").append(esc(node.route))
        .append("\" data-args=\"").append(esc(argsPlain)).append("\" style=\"left:").append(l.x)
        .append(
          "px;top:",
        ).append(
          l.y,
        ).append("px;width:").append(l.w).append("px;height:").append(l.h).append("px\">\n")
      // thumbnail
      val thumb = l.thumb
      append("      <div class=\"thumb ").append(if (deviceFrame) "contain" else "fill")
        .append(
          if (thumb == null) {
            " placeholder"
          } else {
            ""
          },
        ).append("\" style=\"height:").append(l.thumbH).append("px\">")
      if (thumb != null) {
        append(
          "<img loading=\"lazy\" alt=\"",
        ).append(esc(node.route)).append(" preview\" src=\"").append(thumb.dataUri).append("\">")
      } else {
        append("no preview")
      }
      append("</div>\n")
      // title (the composable's name, e.g. "SearchScreen"; falls back to the route)
      append(
        "      <div class=\"title\"><span class=\"route\">",
      ).append(esc(node.displayLabel())).append("</span>")
      if (node.start) append("<span class=\"star\">★</span>")
      append("</div>\n")
      // args
      if (node.args.isNotEmpty()) {
        append("      <div class=\"args\">\n")
        node.args.forEach { append("        ").append(argLine(it)).append('\n') }
        append("      </div>\n")
      }
      append("    </article>\n")
    }
  }

  private fun argLine(a: HArg): String = buildString {
    append("<div class=\"arg\"><span class=\"aname\">").append(esc(a.name)).append("</span>")
    append(
      "<span class=\"acolon\">: </span><span class=\"atype\">",
    ).append(esc(displayType(a))).append("</span>")
    if (a.optional) append("<span class=\"adefault\"> = …</span>")
    append("</div>")
  }

  private fun rowMarkup(
    idx: Int,
    l: Laid,
    edges: List<HEdge>,
    routeById: Map<String, String>,
  ): String {
    val node = l.node
    val incoming = edges.filter { it.to == node.id }.mapNotNull { routeById[it.from] }
    val outgoing = edges.filter {
      it.from == node.id
    }.map { (routeById[it.to] ?: it.to) to it.label }
    val source = when {
      node.sourceFile == null -> "—"
      node.sourceLine != null -> "${node.sourceFile.substringAfterLast('/')}:${node.sourceLine}"
      else -> node.sourceFile.substringAfterLast('/')
    }
    val previews = node.previews.joinToString(", ") { it.previewName }
    return buildString {
      append("    <tr id=\"row-").append(idx).append("\" data-idx=\"").append(idx)
        .append("\" data-id=\"").append(esc(node.id)).append("\">\n")
      append("      <td class=\"t-route\">").append(esc(node.route))
      if (node.start) append(" <span class=\"star\">★</span>")
      append("</td>\n")
      append("      <td class=\"muted\">").append(esc(node.module ?: "—")).append("</td>\n")
      append("      <td class=\"t-args mono\">")
      if (node.args.isEmpty()) {
        append("<span class=\"muted\">—</span>")
      } else {
        append(node.args.joinToString(", ") { argInline(it) })
      }
      append("</td>\n")
      append(
        "      <td class=\"muted mono\" title=\"",
      ).append(esc(node.sourceFile ?: "")).append("\">")
        .append(esc(source)).append("</td>\n")
      append("      <td>").append(pills(incoming.map { it to null })).append("</td>\n")
      append("      <td>").append(pills(outgoing)).append("</td>\n")
      append(
        "      <td class=\"muted\">",
      ).append(if (previews.isEmpty()) "—" else esc(previews)).append("</td>\n")
      append("    </tr>\n")
    }
  }

  private fun argInline(a: HArg): String = buildString {
    append(
      "<span class=\"aname\">",
    ).append(esc(a.name)).append("</span><span class=\"acolon\">: </span>")
    append("<span class=\"atype\">").append(esc(displayType(a))).append("</span>")
    if (a.optional) append("<span class=\"adefault\"> = …</span>")
  }

  private fun pills(items: List<Pair<String, String?>>): String {
    if (items.isEmpty()) return "<span class=\"muted\">—</span>"
    return items.joinToString("") { (route, label) ->
      val text = if (label.isNullOrEmpty()) route else "$route ⟨$label⟩"
      "<span class=\"pill\">${esc(text)}</span>"
    }
  }

  private fun embed(node: HNode, thumbsRoot: File?): Thumb? {
    if (thumbsRoot == null) return null
    val rel =
      (node.previews.firstOrNull { it.primary } ?: node.previews.firstOrNull())?.thumbnail
        ?: return null
    val file = thumbsRoot.resolve(rel.substringAfterLast('/'))
    if (!file.isFile) return null
    return try {
      val dim = ImageIO.read(file) ?: return null
      val b64 = Base64.getEncoder().encodeToString(file.readBytes())
      Thumb("data:image/png;base64,$b64", dim.width, dim.height)
    } catch (_: Exception) {
      null
    }
  }

  private fun emptyHtml(css: String, date: String): String = buildString {
    append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
    append(
      "<title>NavGraph graph — ",
    ).append(esc(date)).append("</title>\n<style>\n").append(css).append("\n</style>\n")
    append("</head>\n<body>\n<div class=\"empty\">\n  <h1>No navigation graph</h1>\n")
    append(
      "  <p>No navgraph nodes were found. Run <code>generateNavGraph</code> first.</p>\n</div>\n</body>\n</html>\n",
    )
  }

  private fun resource(path: String): String =
    javaClass.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
      ?: error("navgraph export resource missing on the classpath: $path")

  private companion object {
    const val CSS_PATH = "/navgraph-export/template.css"
    const val JS_PATH = "/navgraph-export/template.js"

    // Geometry constants — identical to NavGraphCanvas.kt.
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

    fun parseDevice(spec: String?): Pair<Int, Int>? {
      val parts = spec?.split('x', 'X')?.map { it.trim().toIntOrNull() } ?: return null
      if (parts.size != 2) return null
      val (w, h) = parts
      return if (w != null && h != null && w > 0 && h > 0) w to h else null
    }

    /** Compact number for SVG (drops trailing ".0"). */
    fun n(d: Double): String {
      val r = Math.round(d * 10.0) / 10.0
      return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }

    fun esc(s: String): String {
      val sb = StringBuilder(s.length + 16)
      for (c in s) {
        when (c) {
          '&' -> sb.append("&amp;")
          '<' -> sb.append("&lt;")
          '>' -> sb.append("&gt;")
          '"' -> sb.append("&quot;")
          '\'' -> sb.append("&#39;")
          else -> sb.append(c)
        }
      }
      return sb.toString()
    }
  }
}
