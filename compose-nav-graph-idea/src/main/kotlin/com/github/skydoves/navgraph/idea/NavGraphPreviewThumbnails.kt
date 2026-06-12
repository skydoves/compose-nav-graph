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
import com.google.gson.Gson
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import java.io.File

/**
 * A project-wide index of rendered preview thumbnails, keyed by the preview function FQN (`previewFqn`),
 * gathered from BOTH pipelines — the nav graph (`build/navgraph[-aggregated]`) and the preview gallery
 * (`build/navgallery[-aggregated]`) of every Gradle module.
 *
 * A `@NavPreview` is also an `@Preview`, so the SAME function renders identical pixels in both pipelines. This
 * index lets either tab REUSE whatever was already rendered: the gallery shows a graph-rendered thumbnail for an
 * overlapping preview (and vice versa), so a preview is never rendered/shown twice and one tab can fill in from
 * the other. Pure file IO — call off the EDT.
 */
internal object NavGraphPreviewThumbnails {

  private val gson = Gson()

  // (aggregated, per-module) manifest path pairs — aggregated preferred — for each pipeline.
  private val PIPELINES = listOf(
    "build/navgraph-aggregated/nav-graph.json" to "build/navgraph/nav-graph.json",
    "build/navgallery-aggregated/preview-gallery.json" to "build/navgallery/preview-gallery.json",
  )

  /** previewFqn -> an existing thumbnail file. The first file found for an FQN wins (same pixels either way). */
  fun indexByFqn(project: Project): Map<String, File> {
    val out = HashMap<String, File>()
    for (manifest in discover(project)) {
      val graph =
        runCatching { gson.fromJson(manifest.readText(), NavGraphDto::class.java) }.getOrNull()
          ?: continue
      val dir = manifest.parentFile
      for (node in graph.nodes) {
        for (preview in node.previews) {
          val fqn = preview.previewFqn?.takeIf { it.isNotBlank() } ?: continue
          if (fqn in out) continue
          val file = preview.thumbnail?.let { File(dir, it) }?.takeIf { it.isFile } ?: continue
          out[fqn] = file
        }
      }
    }
    return out
  }

  /** Every existing manifest (both pipelines, aggregated preferred) across all Gradle modules. */
  private fun discover(project: Project): List<File> {
    val paths = runReadAction {
      LinkedHashSet<String>().apply {
        for (module in ModuleManager.getInstance(project).modules) {
          ExternalSystemApiUtil.getExternalProjectPath(module)?.let { add(it) }
        }
      }
    }.ifEmpty {
      val base = project.basePath ?: return emptyList()
      buildSet {
        add(base)
        File(base).listFiles()?.forEach { if (it.isDirectory) add(it.path) }
      }
    }
    val out = ArrayList<File>()
    for (path in paths) {
      for ((aggregated, perModule) in PIPELINES) {
        (File(path, aggregated).takeIf { it.isFile } ?: File(path, perModule).takeIf { it.isFile })
          ?.let { out.add(it) }
      }
    }
    return out
  }
}
