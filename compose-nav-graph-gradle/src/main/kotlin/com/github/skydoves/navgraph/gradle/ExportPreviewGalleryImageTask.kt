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
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the merged `preview-gallery.json` (+ thumbnail PNGs) into a single hi-DPI PNG: a static contact
 * sheet of EVERY `@Preview` in the project, grouped by Gradle **module** then **package**, drawn headlessly
 * with Java2D (`java.awt` + `javax.imageio`) — `compose-nav-graph-gradle` is plain `kotlin-dsl` with no IntelliJ
 * dependency. This is the image parity of [ExportPreviewGalleryHtmlTask], the way [ExportNavGraphImageTask]
 * is the image parity of the nav-graph HTML.
 *
 * Unlike the nav graph (a flow with edges/arrows), a gallery manifest has NO edges: each [HNode] is a
 * `(module, package)` bucket whose `previews` are that package's `@Preview`s. So the layout is a wrap-grid,
 * NOT a flow: a MODULE header, then per package a sub-header + a row-wrapping grid of thumbnail cells. The
 * light-theme colors/fonts are kept in sync with [ExportNavGraphImageTask] (the same `JBColors` light halves).
 */
@CacheableTask
public abstract class ExportPreviewGalleryImageTask : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val manifest: RegularFileProperty

  // The thumbnail dir is declared so the PNG set is part of the cache key; a no-preview module has none.
  @get:InputDirectory
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val thumbsDir: DirectoryProperty

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
    val s = (scale.orNull ?: DEFAULT_SCALE).coerceIn(1, 8)

    val out = outputImage.get().asFile
    out.parentFile?.mkdirs()
    val img = render(graph, thumbsRoot, s)
    ImageIO.write(img, "png", out)
    val previews = graph.nodes.sumOf { it.previews.size }
    logger.lifecycle(
      "navgraph: wrote ${out.path} (${out.length() / 1024} KB) — " +
        "$previews preview(s) in ${graph.nodes.size} package(s).",
    )
  }

  // A decoded thumbnail (once) for one preview cell; width/height drive the contain-fit inside the cell box.
  private class Cell(val label: String, val image: BufferedImage?, val w: Int, val h: Int)

  private fun render(graph: HGraph, thumbsRoot: File?, scale: Int): BufferedImage {
    val totalPreviews = graph.nodes.sumOf { it.previews.size }
    if (graph.nodes.isEmpty() || totalPreviews == 0) return emptyImage(scale)

    // module -> its package nodes, both sorted deterministically — identical grouping to the HTML export.
    val byModule = graph.nodes
      .sortedWith(compareBy({ it.module ?: "" }, { it.route }))
      .groupBy { it.module }

    // First PASS: walk the grouped tree advancing a y cursor to compute the total content height; the width is
    // fixed to CONTENT_W (cells wrap within it). Nothing is painted yet — Java2D needs the image size up front.
    var y = MARGIN
    byModule.forEach { (_, nodes) ->
      y += MODULE_HEAD_H
      nodes.forEach { node ->
        y += PKG_HEAD_H
        val count = node.previews.size
        if (count == 0) {
          y += EMPTY_PKG_H
        } else {
          val rows = (count + COLS - 1) / COLS
          y += rows * CELL_H + (rows - 1) * CELL_GAP_Y + PKG_GAP
        }
      }
      y += MODULE_GAP
    }
    val contentW = MARGIN + CONTENT_W + MARGIN
    val contentH = y + MARGIN

    val img = BufferedImage(contentW * scale, contentH * scale, BufferedImage.TYPE_INT_ARGB)
    val g2 = img.createGraphics()
    try {
      applyHints(g2)
      g2.color = BACKGROUND
      g2.fillRect(0, 0, contentW * scale, contentH * scale)
      g2.scale(scale.toDouble(), scale.toDouble())
      drawGallery(g2, byModule, thumbsRoot, contentW)
    } finally {
      g2.dispose()
    }
    return img
  }

  /** Second PASS: paint module headers, package sub-headers, and the wrap-grid of cells, re-deriving the same
   *  y cursor the height pass computed (so the two stay byte-for-byte aligned). */
  private fun drawGallery(
    g2: Graphics2D,
    byModule: Map<String?, List<HNode>>,
    thumbsRoot: File?,
    contentW: Int,
  ) {
    var y = MARGIN
    byModule.forEach { (module, nodes) ->
      val label = module?.ifBlank { null } ?: "(this module)"
      val count = nodes.sumOf { it.previews.size }
      // Module header: the Gradle module path in bold, plus its preview count, over a hairline rule.
      g2.color = TITLE
      g2.font = MODULE_FONT
      g2.drawString(label, MARGIN, y + 18)
      g2.color = MUTED
      g2.font = LABEL_FONT
      g2.drawString("$count preview(s)", MARGIN + textWidth(g2, MODULE_FONT, label) + 10, y + 18)
      g2.color = BORDER
      g2.stroke = STROKE1
      g2.drawLine(MARGIN, y + MODULE_HEAD_H - 8, contentW - MARGIN, y + MODULE_HEAD_H - 8)
      y += MODULE_HEAD_H

      nodes.forEach { node ->
        // Package sub-header: the package name (the node's route).
        g2.color = SUBTITLE
        g2.font = PKG_FONT
        g2.drawString(node.route, MARGIN, y + 14)
        y += PKG_HEAD_H

        if (node.previews.isEmpty()) {
          g2.color = MUTED
          g2.font = LABEL_FONT
          g2.drawString("no previews", MARGIN, y + 12)
          y += EMPTY_PKG_H
        } else {
          node.previews.forEachIndexed { i, pv ->
            val col = i % COLS
            val row = i / COLS
            val cx = MARGIN + col * (CELL_W + CELL_GAP_X)
            val cy = y + row * (CELL_H + CELL_GAP_Y)
            drawCell(g2, resolveCell(pv, thumbsRoot), cx, cy)
          }
          val rows = (node.previews.size + COLS - 1) / COLS
          y += rows * CELL_H + (rows - 1) * CELL_GAP_Y + PKG_GAP
        }
      }
      y += MODULE_GAP
    }
  }

  /** Paints one thumbnail cell at [cx], [cy]: a light card (bordered rounded rect), the decoded preview
   *  contained (letterboxed) into the image box, and the preview name ellipsized on the caption row. A cell
   *  with no decodable thumbnail shows a centered "no preview" placeholder over the image box. */
  private fun drawCell(g2: Graphics2D, cell: Cell, cx: Int, cy: Int) {
    val card = RoundRectangle2D.Double(
      cx.toDouble(),
      cy.toDouble(),
      CELL_W.toDouble(),
      CELL_H.toDouble(),
      ARC,
      ARC,
    )
    g2.color = NODE_BG
    g2.fill(card)

    // The image region — the top of the card, leaving a caption strip below it.
    val imgX = cx + CELL_PAD
    val imgY = cy + CELL_PAD
    val imgW = CELL_W - 2 * CELL_PAD
    val imgH = CELL_H - 2 * CELL_PAD - CAPTION_H
    g2.color = THUMB_BG
    g2.fillRect(imgX, imgY, imgW, imgH)
    if (cell.image != null && cell.w > 0 && cell.h > 0) {
      val savedClip = g2.clip
      g2.clipRect(imgX, imgY, imgW, imgH)
      // Contain (letterbox): scale to fit, centered — the phone aspect (~411:891) is taller than the cell box.
      val scl = minOf(imgW.toDouble() / cell.w, imgH.toDouble() / cell.h)
      val dw = (cell.w * scl).toInt().coerceAtLeast(1)
      val dh = (cell.h * scl).toInt().coerceAtLeast(1)
      g2.drawImage(cell.image, imgX + (imgW - dw) / 2, imgY + (imgH - dh) / 2, dw, dh, null)
      g2.clip = savedClip
    } else {
      g2.color = MUTED
      g2.font = LABEL_FONT
      val fm = g2.fontMetrics
      g2.drawString(
        "no preview",
        imgX + (imgW - fm.stringWidth("no preview")) / 2,
        imgY + imgH / 2 + fm.ascent / 2,
      )
    }

    // Caption: the preview name, ellipsized to the card width so a long name can't spill past the cell.
    g2.color = TITLE
    g2.font = LABEL_FONT
    val textY = cy + CELL_H - CELL_PAD - 3
    g2.drawString(ellipsize(g2, cell.label, CELL_W - 2 * CELL_PAD), imgX, textY)

    g2.color = BORDER
    g2.stroke = STROKE1
    g2.draw(card)
  }

  /**
   * Resolves a preview's thumbnail by the HTML/PNG tasks' rule: basename looked up under [thumbsRoot], decoded
   * once with [ImageIO]. A null `thumbsRoot`, missing file, or undecodable/corrupt PNG yields a label-only
   * [Cell] (→ the "no preview" placeholder).
   */
  private fun resolveCell(pv: HPreview, thumbsRoot: File?): Cell {
    val rel = pv.thumbnail
    if (thumbsRoot == null || rel == null) return Cell(pv.previewName, null, 0, 0)
    val file = File(thumbsRoot, rel.substringAfterLast('/'))
    if (!file.isFile) return Cell(pv.previewName, null, 0, 0)
    return try {
      val dim = ImageIO.read(file) ?: return Cell(pv.previewName, null, 0, 0)
      Cell(pv.previewName, dim, dim.width, dim.height)
    } catch (_: Exception) {
      Cell(pv.previewName, null, 0, 0)
    }
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
      g2.font = MODULE_FONT.deriveFont(13f)
      val fm = g2.fontMetrics
      val text = "No previews"
      g2.drawString(text, (w - fm.stringWidth(text)) / 2, h / 2)
    } finally {
      g2.dispose()
    }
    return img
  }

  private companion object {
    const val DEFAULT_SCALE = 2

    // ── Grid geometry — the contact-sheet layout (no flow/edges, so this is bespoke to the gallery). ──
    const val MARGIN = 40

    // The fixed content column the cells wrap inside (image grows in height, not width). COLS cells fit it.
    const val COLS = 6
    const val CELL_W = 160
    const val CELL_GAP_X = 16
    const val CELL_GAP_Y = 16
    const val CELL_PAD = 8

    // The caption strip under the image (one LABEL_FONT line). The image box fills the rest of the cell.
    const val CAPTION_H = 18

    // Cell box height: image region (CELL_W-pad wide at the phone aspect 891:411) + paddings + caption.
    const val IMAGE_H = 240
    const val CELL_H = IMAGE_H + 2 * CELL_PAD + CAPTION_H

    // The content width is exactly COLS cells + the inter-cell gaps; the image margins are added around it.
    const val CONTENT_W = COLS * CELL_W + (COLS - 1) * CELL_GAP_X

    // Header band heights + the gaps between packages / modules.
    const val MODULE_HEAD_H = 34
    const val PKG_HEAD_H = 22
    const val EMPTY_PKG_H = 18
    const val PKG_GAP = 18
    const val MODULE_GAP = 16
    const val ARC = 12.0

    // ── Theme — LIGHT halves of NavGraphCanvas's JBColors (kept in sync with ExportNavGraphImageTask). ──
    val BACKGROUND = Color(0xFBFCFE) // settings.backgroundRGB (light)
    val NODE_BG = Color(0xFFFFFF) // settings.nodeFillRGB (light)
    val THUMB_BG = Color(0xF1, 0xF3, 0xF5) // LightDefaults.THUMB_BG
    val BORDER = Color(0xD6, 0xD9, 0xDE) // LightDefaults.BORDER
    val TITLE = Color(0x20, 0x21, 0x24) // LightDefaults.TITLE
    val SUBTITLE = Color(0x3C, 0x40, 0x43) // a touch lighter than TITLE for the package sub-header
    val MUTED = Color(0x80, 0x86, 0x8B) // LightDefaults.MUTED

    // ── Fonts — module header BOLD 15, package sub-header BOLD 12, cell caption/label 10. ──
    val MODULE_FONT = Font(Font.SANS_SERIF, Font.BOLD, 15)
    val PKG_FONT = Font(Font.SANS_SERIF, Font.BOLD, 12)
    val LABEL_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 10)

    val STROKE1 = BasicStroke(1f)

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

    /** Pixel width of [text] in [font] using [g2]'s metrics (used to place the module-header count). */
    fun textWidth(g2: Graphics2D, font: Font, text: String): Int {
      val saved = g2.font
      g2.font = font
      val w = g2.fontMetrics.stringWidth(text)
      g2.font = saved
      return w
    }

    /** Truncates [text] with a trailing ellipsis so it fits within [maxWidth] px at [g2]'s current font. */
    fun ellipsize(g2: Graphics2D, text: String, maxWidth: Int): String {
      val fm = g2.fontMetrics
      if (fm.stringWidth(text) <= maxWidth) return text
      val ellipsis = "…"
      val budget = maxWidth - fm.stringWidth(ellipsis)
      if (budget <= 0) return ellipsis
      var end = text.length
      while (end > 0 && fm.stringWidth(text.substring(0, end)) > budget) end--
      return text.substring(0, end) + ellipsis
    }
  }
}
