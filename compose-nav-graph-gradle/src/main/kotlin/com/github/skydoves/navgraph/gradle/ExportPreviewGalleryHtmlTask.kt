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
 * Renders the merged `preview-gallery.json` (+ thumbnail PNGs) into a single self-contained, interactive HTML
 * gallery of EVERY `@Preview` in the project — base64 images + inline CSS/JS, no external dependencies, opens
 * offline. Previews are grouped by Gradle **module**, then by **package**; a search box filters by name and a
 * click on a section header collapses it.
 *
 * A gallery manifest is a [HGraph] whose nodes are `(module, package)` buckets and whose `previews` are that
 * package's `@Preview`s, so it reuses the shared [parseGraph] mirror (see NavManifest.kt). The CSS/JS are
 * classpath resources, mirroring [ExportNavGraphHtmlTask].
 */
@CacheableTask
public abstract class ExportPreviewGalleryHtmlTask : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val manifest: RegularFileProperty

  // Declared so the PNG set is part of the cache key; a no-preview module has none.
  @get:InputDirectory
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val thumbsDir: DirectoryProperty

  @get:OutputFile
  public abstract val outputHtml: RegularFileProperty

  // CSS/JS are classpath resources, not file inputs — fingerprint them so editing one invalidates the cache.
  @get:Input
  public val templateFingerprint: Int
    get() = resource(CSS_PATH).hashCode() * 31 + resource(JS_PATH).hashCode()

  @TaskAction
  public fun export() {
    val manifestFile = manifest.get().asFile
    val graph = parseGraph(manifestFile.readText())
    val thumbsRoot: File? = thumbsDir.orNull?.asFile
    // "Generated" date = the manifest mtime, not now() — keeps the output reproducible for a given gallery.
    val date = LocalDate.ofInstant(
      Instant.ofEpochMilli(manifestFile.lastModified()),
      ZoneId.systemDefault(),
    ).toString()

    val out = outputHtml.get().asFile
    out.parentFile?.mkdirs()
    out.writeText(render(graph, thumbsRoot, date))
    val previews = graph.nodes.sumOf { it.previews.size }
    logger.lifecycle(
      "navgraph: wrote ${out.path} (${out.length() / 1024} KB) — " +
        "$previews preview(s) in ${graph.nodes.size} package(s).",
    )
  }

  private class Card(val label: String, val dataUri: String?)

  private fun render(graph: HGraph, thumbsRoot: File?, date: String): String {
    val css = resource(CSS_PATH)
    val js = resource(JS_PATH)
    val totalPreviews = graph.nodes.sumOf { it.previews.size }
    if (graph.nodes.isEmpty() || totalPreviews == 0) return emptyHtml(css, date)

    // module -> its package nodes, both sorted deterministically.
    val byModule = graph.nodes
      .sortedWith(compareBy({ it.module ?: "" }, { it.route }))
      .groupBy { it.module }

    return buildString {
      appendLine("<!doctype html>")
      appendLine("<html lang=\"en\">")
      appendLine("<head>")
      appendLine("<meta charset=\"utf-8\">")
      appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
      appendLine("<title>NavGraph preview gallery — ${esc(date)}</title>")
      appendLine("<style>")
      appendLine(css)
      appendLine("</style>")
      appendLine("</head>")
      appendLine("<body>")
      appendLine("<header class=\"toolbar\">")
      appendLine("  <h1>Preview gallery</h1>")
      appendLine(
        "  <span class=\"counts\">$totalPreviews previews · " +
          "${graph.nodes.size} packages · ${esc(date)}</span>",
      )
      appendLine("  <span class=\"spacer\"></span>")
      appendLine(
        "  <input id=\"search\" class=\"search\" type=\"search\" " +
          "placeholder=\"Filter preview…\" autocomplete=\"off\">",
      )
      appendLine(
        "  <button id=\"theme\" class=\"btn\" title=\"Theme (auto / light / dark)\">🌗</button>",
      )
      appendLine("</header>")
      appendLine("<main>")
      byModule.forEach { (module, nodes) -> append(moduleHtml(module, nodes, thumbsRoot)) }
      appendLine("</main>")
      appendLine("<script>")
      appendLine(js)
      appendLine("</script>")
      appendLine("</body>")
      appendLine("</html>")
    }
  }

  private fun moduleHtml(module: String?, nodes: List<HNode>, thumbsRoot: File?): String {
    val label = module?.ifBlank { null } ?: "(this module)"
    val count = nodes.sumOf { it.previews.size }
    return buildString {
      appendLine("<section class=\"module\" data-module=\"${esc(label)}\">")
      appendLine(
        "  <h2 class=\"mod-head\" onclick=\"navgraphToggle(this)\">${esc(label)}" +
          "<span class=\"count\">$count</span></h2>",
      )
      appendLine("  <div class=\"mod-body\">")
      nodes.forEach { node -> append(packageHtml(node, thumbsRoot)) }
      appendLine("  </div>")
      appendLine("</section>")
    }
  }

  private fun packageHtml(node: HNode, thumbsRoot: File?): String = buildString {
    appendLine("    <section class=\"pkg\">")
    appendLine(
      "      <h3 class=\"pkg-head\" onclick=\"navgraphToggle(this)\">${esc(node.route)}" +
        "<span class=\"count\">${node.previews.size}</span></h3>",
    )
    appendLine("      <div class=\"grid\">")
    node.previews.forEach { pv -> append(cardHtml(pv, thumbsRoot)) }
    appendLine("      </div>")
    appendLine("    </section>")
  }

  private fun cardHtml(pv: HPreview, thumbsRoot: File?): String {
    val card = resolveCard(pv, thumbsRoot)
    return buildString {
      appendLine("        <figure class=\"card\" data-name=\"${esc(card.label.lowercase())}\">")
      if (card.dataUri != null) {
        appendLine(
          "          <div class=\"thumb\"><img loading=\"lazy\" " +
            "alt=\"${esc(card.label)} preview\" src=\"${card.dataUri}\"></div>",
        )
      } else {
        appendLine("          <div class=\"thumb placeholder\">no preview</div>")
      }
      appendLine(
        "          <figcaption title=\"${esc(card.label)}\">${esc(card.label)}</figcaption>",
      )
      appendLine("        </figure>")
    }
  }

  private fun resolveCard(pv: HPreview, thumbsRoot: File?): Card {
    val rel = pv.thumbnail
    if (thumbsRoot == null || rel == null) return Card(pv.previewName, null)
    val file = thumbsRoot.resolve(rel.substringAfterLast('/'))
    if (!file.isFile) return Card(pv.previewName, null)
    return try {
      // Decode once to confirm it's a real image before embedding (a 0-byte/corrupt PNG → placeholder).
      ImageIO.read(file) ?: return Card(pv.previewName, null)
      val b64 = Base64.getEncoder().encodeToString(file.readBytes())
      Card(pv.previewName, "data:image/png;base64,$b64")
    } catch (_: Exception) {
      Card(pv.previewName, null)
    }
  }

  private fun emptyHtml(css: String, date: String): String = buildString {
    appendLine("<!doctype html>")
    appendLine("<html lang=\"en\">")
    appendLine("<head>")
    appendLine("<meta charset=\"utf-8\">")
    appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    appendLine("<title>NavGraph preview gallery — ${esc(date)}</title>")
    appendLine("<style>")
    appendLine(css)
    appendLine("</style>")
    appendLine("</head>")
    appendLine("<body>")
    appendLine("<div class=\"empty\">")
    appendLine("  <h1>No previews</h1>")
    appendLine(
      "  <p>No <code>@Preview</code> composables were found. " +
        "Run <code>generatePreviewGallery</code> first.</p>",
    )
    appendLine("</div>")
    appendLine("</body>")
    appendLine("</html>")
  }

  private fun resource(path: String): String =
    javaClass.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
      ?: error("navgraph export resource missing on the classpath: $path")

  private companion object {
    const val CSS_PATH = "/navgraph-export/gallery.css"
    const val JS_PATH = "/navgraph-export/gallery.js"

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
