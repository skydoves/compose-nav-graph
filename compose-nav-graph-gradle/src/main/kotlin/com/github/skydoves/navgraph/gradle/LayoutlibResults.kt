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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
  val missingClasses: List<String>,
  val problems: List<String>,
  val message: String?,
)

/** The renderer's preview host. When it isn't on the render classpath the renderer emits a gray
 *  "…ComposeViewAdapter" placeholder yet still reports `status:SUCCESS` (with the class in `missingClasses`), so it
 *  must be detected explicitly. Absent on KMP modules when Compose `ui-tooling` isn't on the render classpath. */
internal const val COMPOSE_VIEW_ADAPTER: String = "androidx.compose.ui.tooling.ComposeViewAdapter"

/**
 * Whether [result] is a real, full-fidelity render. The renderer reports `status:SUCCESS` even with an empty
 * error object, so success additionally requires: no broken classes (a placeholder image), no `problems` (e.g.
 * "View measure failed" wrapping a `Resources$NotFoundException` → a 1×1 blank), the preview host
 * [COMPOSE_VIEW_ADAPTER] not among `missingClasses` (its absence ⇒ a placeholder the renderer mislabels SUCCESS),
 * and a non-empty PNG on disk.
 */
internal fun isLayoutlibSuccess(result: ShotResult?, image: File?): Boolean =
  result != null && (result.status == null || result.status == "SUCCESS") &&
    result.brokenClasses.isEmpty() && result.problems.isEmpty() &&
    result.missingClasses.none { it.endsWith("ComposeViewAdapter") } &&
    image != null && image.isFile && image.length() > 0L

/**
 * Parse the renderer's `results.json` (`screenshotResults[]`) → `previewId` → image + render status. The `error`
 * object is present even on success (status SUCCESS); `brokenClasses` lists classes Layoutlib failed to load
 * (⇒ an error placeholder, not a real render), `missingClasses` lists classes the renderer couldn't find at all
 * (the preview host [COMPOSE_VIEW_ADAPTER] lands here), and `problems` carries non-fatal-looking layout/measure
 * failures.
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
    // The renderer reports an absent class (incl. the preview host ComposeViewAdapter) here — as bare strings, NOT
    // in `brokenClasses` — and still reports SUCCESS with a placeholder image, so this field must be parsed too.
    val missing =
      (err?.get("missingClasses") as? JsonArray)?.mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.str("className")
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
    id to ShotResult(o.str("imagePath"), err?.str("status"), broken, missing, problems, message)
  }.toMap()
}
