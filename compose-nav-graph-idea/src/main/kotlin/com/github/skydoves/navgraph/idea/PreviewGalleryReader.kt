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
import com.google.gson.Gson
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Loads the **Preview Gallery** manifest the `compose-nav-graph-gradle` plugin writes to
 * `build/navgallery/preview-gallery.json` (per module) and
 * `build/navgallery-aggregated/preview-gallery.json` (the umbrella module). The manifest is a
 * [NavGraphDto] whose nodes are `(module, package)` buckets and whose `previews[]` are that
 * package's `@Preview`s.
 *
 * Output is shaped for [NavGraphCanvas] (in gallery mode): every `@Preview` becomes its OWN
 * node (a box labelled with the preview name, `module` set for the canvas's section grouping),
 * so the gallery reuses the graph's boxes / zoom / device framing. Thumbnails come from the
 * shared [NavGraphPreviewThumbnails] index (keyed by `previewFqn`), so an overlapping preview
 * already rendered by the nav graph is reused here. Call [load] off the EDT.
 */
internal object PreviewGalleryReader {

  private val gson = Gson()
  private val log = Logger.getInstance(PreviewGalleryReader::class.java)

  /**
   * Aggregated manifest (umbrella module merging itself + deps); preferred so a single tab
   * shows the whole repo.
   */
  private const val AGGREGATED_REL = "build/navgallery-aggregated/preview-gallery.json"

  /**
   * Per-module manifest (this module's own `@Preview`s only); the fallback when no aggregate
   * exists.
   */
  private const val PER_MODULE_REL = "build/navgallery/preview-gallery.json"

  /**
   * The gallery as a [NavGraphDto] (one node per preview) + the node-id → thumbnail map
   * [NavGraphCanvas] expects.
   */
  data class Loaded(
    val graph: NavGraphDto,
    val thumbs: Map<String, BufferedImage>,
    /**
     * The Gradle module dir to run `exportPreviewGallery*` in (the aggregated/umbrella module),
     * if any.
     */
    val exportProjectPath: String? = null,
  )

  /**
   * Loads every `@Preview` as a node, ordered by module → package → manifest order (so the
   * canvas groups cleanly), deduping nodes that surface from two manifests (the aggregated file
   * repeats a module's own nodes).
   * Empty graph when no gallery manifest exists anywhere.
   */
  fun load(project: Project): Loaded {
    val manifests = discoverManifests(project)
    if (manifests.isEmpty()) return Loaded(NavGraphDto(), emptyMap())

    val fqnIndex = NavGraphPreviewThumbnails.indexByFqn(project)
    val decoded = HashMap<File, BufferedImage?>()
    fun decode(file: File?): BufferedImage? =
      file?.let { decoded.getOrPut(it) { runCatching { ImageIO.read(it) }.getOrNull() } }

    val thumbs = HashMap<String, BufferedImage>()
    val seenGalleryNodeIds = HashSet<String>()

    // (sortModule, sortPackage, node) — sorted at the end; the sort is stable so manifest order
    // is preserved.
    data class Row(val sortModule: String, val sortPackage: String, val node: NavNodeDto)
    val rows = ArrayList<Row>()

    var exportProjectPath: String? = null
    var exportRank = -1 // an aggregated manifest outranks per-module ones; then more previews wins
    for ((path, manifest) in manifests) {
      val graph = runCatching { gson.fromJson(manifest.readText(), NavGraphDto::class.java) }
        .getOrNull() ?: continue
      // Run the export in the module that owns the fullest gallery (the aggregated umbrella,
      // else the richest).
      val rawPreviews = graph.nodes.sumOf { it.previews.size }
      val rank =
        (if (manifest.parentFile.name == "navgallery-aggregated") 1_000_000 else 0) + rawPreviews
      if (rawPreviews > 0 && rank > exportRank) {
        exportRank = rank
        exportProjectPath = path
      }
      val dir = manifest.parentFile
      for (gnode in graph.nodes) {
        // taken from an earlier manifest
        if (gnode.id.isNotBlank() && !seenGalleryNodeIds.add(gnode.id)) continue
        val module = gnode.module?.takeIf { it.isNotBlank() }
        for (preview in gnode.previews) {
          val id = "${gnode.id}::${preview.previewName}"
          // Reuse a thumbnail rendered by EITHER pipeline (shared index, keyed by FQN), then
          // this manifest's own.
          val image = decode(preview.previewFqn?.let { fqnIndex[it] })
            ?: decode(preview.thumbnail?.let { File(dir, it) })
          if (image != null) thumbs[id] = image
          rows.add(
            Row(
              sortModule = module?.lowercase() ?: "",
              sortPackage = gnode.route.lowercase(),
              node = NavNodeDto(
                id = id,
                route = preview.previewName.ifBlank { "preview" }, // the box label
                module = module, // drives the canvas's per-module section header
                clickTargetFqn = preview.previewFqn, // double-click → jump to the @Preview source
              ),
            ),
          )
        }
      }
    }

    val ordered = rows.sortedWith(compareBy({ it.sortModule }, { it.sortPackage }))
    if (ordered.isNotEmpty()) log.debug("navgraph preview gallery: ${ordered.size} preview(s)")
    return Loaded(
      NavGraphDto(nodes = ordered.map { it.node }, edges = emptyList()),
      thumbs,
      exportProjectPath,
    )
  }

  /**
   * Resolves one gallery manifest per Gradle project, preferring [AGGREGATED_REL] over
   * [PER_MODULE_REL]. Walks the IntelliJ module model under a read action, then collects
   * existing files. Falls back to scanning the project root's direct children when the Gradle
   * model isn't imported yet (mirrors [NavGraphReader]).
   */
  private fun discoverManifests(project: Project): LinkedHashMap<String, File> {
    val projectPaths = runReadAction {
      LinkedHashSet<String>().apply {
        for (module in ModuleManager.getInstance(project).modules) {
          ExternalSystemApiUtil.getExternalProjectPath(module)?.let { add(it) }
        }
      }
    }

    val paths = projectPaths.ifEmpty {
      val base = project.basePath ?: return LinkedHashMap()
      buildSet {
        add(base)
        File(base).listFiles()?.forEach { if (it.isDirectory) add(it.path) }
      }
    }

    // project path → its chosen manifest (dedupes source-set splits)
    val out = LinkedHashMap<String, File>()
    for (path in paths) {
      if (path in out) continue
      val chosen = File(path, AGGREGATED_REL).takeIf { it.isFile }
        ?: File(path, PER_MODULE_REL).takeIf { it.isFile }
        ?: continue
      out[path] = chosen
    }
    return out
  }
}
