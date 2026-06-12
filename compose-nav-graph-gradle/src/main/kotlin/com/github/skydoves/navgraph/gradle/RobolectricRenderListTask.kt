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
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Writes the TSV render-list (`methodFqn\tnodeId\tpreviewName\tprimary[\tlocale]`, one preview per line) that the
 * generated `NavGraphRobolectricRenderTest` reads via the `navgraph.renderList` system property. In `ROBOLECTRIC` mode
 * every `@NavPreview` is listed; in `AUTO` mode only the previews Layoutlib **failed** to render are listed —
 * derived from the Layoutlib `results.json` ([readLayoutlibResults] + [isLayoutlibSuccess]), the single source
 * of truth for which previews need the Robolectric fallback.
 */
@DisableCachingByDefault(
  because = "Cheap to recompute; depends on the upstream manifest + Layoutlib results",
)
public abstract class RobolectricRenderListTask : DefaultTask() {

  /** The KSP manifest — every `previews[].previewMethodFqn` is a Robolectric render candidate. */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val kspManifest: RegularFileProperty

  /** `AUTO` (filter to Layoutlib failures) or `ROBOLECTRIC` (render all). */
  @get:Input
  public abstract val backend: Property<String>

  /**
   * The Layoutlib renderer's scratch dir (holds `results.json` + `png/`). `@Internal` because it lives under
   * the shared `build/navgraph-render` tree alongside the Layoutlib task's own outputs; ordering is guaranteed by
   * the explicit `dependsOn(renderNavGraphLayoutlib)` wired in `AUTO` mode. Absent/empty ⇒ every preview is listed.
   */
  @get:Internal
  public abstract val layoutlibWorkDir: DirectoryProperty

  @get:OutputFile
  public abstract val renderList: RegularFileProperty

  /**
   * The shared `preview-index.txt` the render appends to. In `ROBOLECTRIC` mode this task truncates it first
   * (no Layoutlib pass owns it), so repeated runs don't accumulate stale duplicate lines. In `AUTO` mode the
   * Layoutlib render owns truncation (it runs first), so this task leaves the index alone. `@Internal` because
   * it is co-owned with the render tasks under `build/navgraph`; ordering is enforced by `dependsOn`.
   */
  @get:Internal
  public abstract val previewIndex: RegularFileProperty

  @TaskAction
  public fun write() {
    val graph = parseGraph(kspManifest.get().asFile.readText())

    // Reconstruct the SAME ordered shot list LayoutlibRenderTask builds (skip blank methodFqns, assign nav<i>),
    // so a results.json `previewId` maps back to its (nodeId, previewName, primary).
    data class Shot(
      val nodeId: String,
      val previewName: String,
      val primary: Boolean,
      val methodFqn: String,
      val id: String,
      val locale: String?,
    )
    val shots = buildList {
      var i = 0
      graph.nodes.forEach { node ->
        node.previews.forEach { pv ->
          val method = pv.previewMethodFqn
          if (!method.isNullOrBlank()) {
            add(Shot(node.id, pv.previewName, pv.primary, method, "nav${i++}", pv.locale))
          }
        }
      }
    }

    val auto = backend.get().equals(RenderBackend.AUTO.name, ignoreCase = true)
    // ROBOLECTRIC mode has no Layoutlib pass to truncate the shared index — do it here before the render appends.
    if (!auto) {
      previewIndex.orNull?.asFile?.apply { parentFile?.mkdirs() }?.writeText("")
    }
    val work = layoutlibWorkDir.get().asFile
    val byId = if (auto) readLayoutlibResults(work.resolve("results.json")) else emptyMap()
    val pngDir = work.resolve("png")

    val selected = shots.filter { shot ->
      if (!auto) {
        return@filter true
      }
      // Keep only the previews Layoutlib failed (or never produced a result for).
      val result = byId[shot.id]
      val image = result?.imagePath?.let { pngDir.resolve(it) }
      !isLayoutlibSuccess(result, image)
    }

    val out = renderList.get().asFile
    out.parentFile.mkdirs()
    // TSV fields are TRAILING-additive only: NavPreviewRenderer in compose-nav-graph-testing parses this
    // line format, and the plugin auto-wires that artifact at its own version, so both sides move in step.
    // The locale field is emitted only when present, keeping locale-less lines byte-identical to the
    // 4-field format an older, explicitly-pinned testing artifact still parses correctly.
    out.writeText(
      selected.joinToString("") { s ->
        "${s.methodFqn}\t${s.nodeId}\t${s.previewName}\t${s.primary}" +
          "${s.locale?.let { "\t$it" }.orEmpty()}\n"
      },
    )
    logger.lifecycle(
      "navgraph: robolectric render-list — ${selected.size}/${shots.size} preview(s) " +
        (if (auto) "(layoutlib fallback)" else "(robolectric-only)") + ".",
    )
  }
}
