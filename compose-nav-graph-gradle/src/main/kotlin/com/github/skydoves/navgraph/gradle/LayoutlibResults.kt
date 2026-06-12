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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * One entry of the Layoutlib renderer's `results.json` (`screenshotResults[]`), keyed by `previewId`. The
 * single source of truth for "which previews Layoutlib failed" — shared by [LayoutlibRenderTask] (which copies
 * the successful PNGs) and the `prepareNavGraphRobolectricRenderList` action (which re-renders the failures under
 * Robolectric in `AUTO` mode).
 */
internal data class ShotResult(
  val imagePath: String?,
  val status: String?,
  val brokenClasses: List<String>,
  val problems: List<String>,
  val message: String?,
)

/**
 * Whether [result] is a real, full-fidelity render. The renderer reports `status:SUCCESS` even with an empty
 * error object, so success additionally requires: no broken classes (a placeholder image), no `problems` (e.g.
 * "View measure failed" wrapping a `Resources$NotFoundException` → a 1×1 blank), and a non-empty PNG on disk.
 */
internal fun isLayoutlibSuccess(result: ShotResult?, image: File?): Boolean =
  result != null && (result.status == null || result.status == "SUCCESS") &&
    result.brokenClasses.isEmpty() && result.problems.isEmpty() &&
    image != null && image.isFile && image.length() > 0L

/**
 * Parse the renderer's `results.json` (`screenshotResults[]`) → `previewId` → image + render status. The `error`
 * object is present even on success (status SUCCESS); `brokenClasses` lists classes Layoutlib failed to load
 * (⇒ an error placeholder, not a real render), and `problems` carries non-fatal-looking layout/measure failures.
 */
internal fun readLayoutlibResults(file: File): Map<String, ShotResult> {
  if (!file.isFile) return emptyMap()
  val root =
    runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
      ?: return emptyMap()
  val arr = root["screenshotResults"] as? JsonArray ?: return emptyMap()
  return arr.mapNotNull { el ->
    val o = el.jsonObject
    val id = o.str("previewId") ?: return@mapNotNull null
    val err = o["error"] as? JsonObject
    val broken =
      (err?.get("brokenClasses") as? JsonArray)?.mapNotNull {
        (it as? JsonObject)?.str("className")
      }
        ?: emptyList()
    val problems = (err?.get("problems") as? JsonArray)?.mapNotNull { p ->
      (p as? JsonObject)?.let {
        it.str("stackTrace")?.lineSequence()?.firstOrNull()?.takeIf { l -> l.isNotBlank() }
          ?: it.str("html")
      }
    } ?: emptyList()
    val message =
      err?.str("message")?.takeIf { it.isNotBlank() }
        ?: err?.str("stackTrace")?.takeIf { it.isNotBlank() }
    id to ShotResult(o.str("imagePath"), err?.str("status"), broken, problems, message)
  }.toMap()
}
