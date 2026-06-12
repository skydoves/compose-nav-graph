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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Joins the KSP-emitted manifest with the render's `preview-index.txt` (lines:
 * `methodName|routeFqn|file|primary`), filling each `NavNode.previews[].thumbnail`, and writes the
 * consumed manifest to [outputManifest]. Pure JsonElement surgery — no dependency on the typed model.
 *
 * The join key is **(routeFqn = node.id, methodName = preview.previewName)** — robust because the KSP
 * `previewFqn` (`pkg.Fn`) and the JVM facade name (`pkg.FileKt.Fn`) forms differ for top-level funcs.
 */
@CacheableTask
public abstract class MergeNavGraphTask : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val kspManifest: RegularFileProperty

  // Optional: a module with no `@NavPreview`s renders no index (graceful → no thumbnails merged).
  @get:InputFile
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val previewIndex: RegularFileProperty

  @get:OutputFile
  public abstract val outputManifest: RegularFileProperty

  @TaskAction
  public fun merge() {
    val json = Json { prettyPrint = true }
    val root = json.parseToJsonElement(kspManifest.get().asFile.readText()).jsonObject

    // (routeFqn, methodName) -> "thumbs/<file>"
    val thumbByKey: Map<Pair<String, String>, String> = previewIndex.orNull?.asFile?.readLines()
      .orEmpty()
      .filter { it.isNotBlank() }
      .mapNotNull { line ->
        // a future field must never re-slice methodName/route/file
        val parts = line.split("|", limit = 4)
        if (parts.size < 3) return@mapNotNull null
        val methodName = parts[0]
        val routeFqn = parts[1]
        val file = parts[2]
        (routeFqn to methodName) to "thumbs/$file"
      }
      .toMap()

    val nodes = (root["nodes"] as? JsonArray ?: JsonArray(emptyList())).map { nodeEl ->
      val node = nodeEl.jsonObject
      val id = node["id"]?.jsonPrimitive?.content ?: return@map nodeEl
      val previews = node["previews"] as? JsonArray ?: return@map nodeEl
      val newPreviews = previews.map { previewEl ->
        val preview = previewEl.jsonObject
        val name = preview["previewName"]?.jsonPrimitive?.content
        val thumbnail = name?.let { thumbByKey[id to it] }
        if (thumbnail == null) {
          previewEl
        } else {
          JsonObject(preview + ("thumbnail" to JsonPrimitive(thumbnail)))
        }
      }
      JsonObject(node + ("previews" to JsonArray(newPreviews)))
    }

    val merged: JsonElement = JsonObject(root + ("nodes" to JsonArray(nodes)))
    val out = outputManifest.get().asFile
    out.parentFile.mkdirs()
    out.writeText(json.encodeToString(JsonElement.serializer(), merged))

    val filled = nodes.sumOf { node ->
      (node.jsonObject["previews"] as? JsonArray).orEmpty().count {
        it.jsonObject.containsKey("thumbnail")
      }
    }
    logger.lifecycle("navgraph: wrote ${out.path} — ${nodes.size} nodes, $filled thumbnails merged.")
  }
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
