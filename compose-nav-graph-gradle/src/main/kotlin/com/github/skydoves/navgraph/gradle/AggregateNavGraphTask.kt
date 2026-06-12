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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.DefaultTask
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/** Tags the consumable configuration ([NAVGRAPH_GRAPH_CONFIGURATION]) that carries a module's generated nav-graph
 *  (`nav-graph.json` + `thumbs/`), so an umbrella module can re-select + aggregate the graphs of its dependencies. */
internal val NAVGRAPH_GRAPH_ATTRIBUTE: Attribute<String> =
  Attribute.of("com.github.skydoves.navgraph.navgraph", String::class.java)
internal const val NAVGRAPH_GRAPH_VALUE: String = "nav-graph"
internal const val NAVGRAPH_GRAPH_CONFIGURATION: String = "navgraphGraphElements"

// The preview gallery shares [NAVGRAPH_GRAPH_ATTRIBUTE]'s key but a DIFFERENT value, so variant reselection picks a
// dependency's gallery artifact independently of its nav-graph artifact (two consumable configs, one per value).
internal const val NAVGRAPH_GALLERY_VALUE: String = "nav-gallery"
internal const val NAVGRAPH_GALLERY_CONFIGURATION: String = "navgraphGalleryElements"

/**
 * Merges several per-module `nav-graph.json` manifests (this module's own, plus each dependency module's pulled as
 * a Gradle artifact) into ONE combined graph. navgraph's KSP runs per module, so a cross-module `@NavEdge` target shows
 * as a no-preview stub in the declaring module's graph; aggregation re-unites it with the real node (+ thumbnail)
 * from the module that actually declares it. Nodes are unioned by id (a node carrying a rendered thumbnail beats a
 * bare stub), edges are unioned, and every referenced thumbnail is copied into one combined `thumbs/` with a
 * per-source prefix so identically-named PNGs from different modules can't clobber each other. Pure JsonElement
 * surgery — same as [MergeNavGraphTask], no typed-model serializer needed.
 */
@CacheableTask
public abstract class AggregateNavGraphTask : DefaultTask() {

  /** Each dependency module's nav-graph output directory (`nav-graph.json` + `thumbs/`), pulled via the
   *  [NAVGRAPH_GRAPH_CONFIGURATION] artifact off this module's runtime classpath. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val dependencyGraphDirs: ConfigurableFileCollection

  /** This module's OWN merged manifest, when it also declares `@NavDestination`s (optional — an umbrella module
   *  usually has none). */
  @get:InputFile
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val ownManifest: RegularFileProperty

  /** This module's OWN thumbnails directory (may be empty or absent). */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val ownThumbs: ConfigurableFileCollection

  @get:OutputDirectory
  public abstract val outputDir: DirectoryProperty

  /** The manifest filename read from each source dir and written to [outputDir]: `nav-graph.json` for the nav
   *  graph, `preview-gallery.json` for the preview gallery. Set explicitly per pipeline at registration. */
  @get:Input
  public abstract val manifestFileName: Property<String>

  @TaskAction
  public fun aggregate() {
    val json = Json { prettyPrint = true }
    val manifestName = manifestFileName.get()
    val outDir = outputDir.get().asFile
    val outThumbs = File(outDir, "thumbs")
    // Authoritative output — clear stale thumbnails so a removed module/preview doesn't linger.
    if (outThumbs.exists()) outThumbs.deleteRecursively()
    outThumbs.mkdirs()

    // (manifest, thumbsDir?) sources: this module's own first, then each dependency module's navgraph dir
    // (resolved deterministically so the per-source thumbnail prefix is stable build-to-build).
    val sources: List<Pair<File, File?>> = buildList {
      ownManifest.orNull?.asFile?.takeIf { it.isFile }?.let { manifest ->
        add(manifest to ownThumbs.files.firstOrNull { it.isDirectory })
      }
      dependencyGraphDirs.files
        .mapNotNull {
          when {
            it.isDirectory -> it
            it.name == manifestName -> it.parentFile
            else -> null
          }
        }
        .distinct()
        .sortedBy { it.absolutePath }
        .forEach { dir ->
          val manifest = File(dir, manifestName)
          if (manifest.isFile) add(manifest to File(dir, "thumbs").takeIf { it.isDirectory })
        }
    }

    val nodesById = LinkedHashMap<String, JsonObject>()
    val startIds = mutableSetOf<String>()
    val edges = LinkedHashSet<JsonObject>()

    sources.forEachIndexed { index, (manifestFile, thumbsRoot) ->
      val root = json.parseToJsonElement(manifestFile.readText()).jsonObject
      (root["nodes"] as? JsonArray).orEmptyArray().forEach { element ->
        val node = element.jsonObject
        val id = node["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
        if (node["start"]?.jsonPrimitive?.booleanOrNull == true) startIds += id
        val rewritten = copyThumbnails(node, index, thumbsRoot, outThumbs)
        nodesById[id] = richer(nodesById[id], rewritten)
      }
      (root["edges"] as? JsonArray).orEmptyArray().forEach { edges += it.jsonObject }
    }

    // Union the start flag: a node is the start if ANY contributing module marked it so.
    val nodes = nodesById.values.map { node ->
      val id = node["id"]?.jsonPrimitive?.contentOrNull
      if (id != null && id in startIds && node["start"]?.jsonPrimitive?.booleanOrNull != true) {
        JsonObject(node + ("start" to JsonPrimitive(true)))
      } else {
        node
      }
    }

    val merged = JsonObject(
      mapOf(
        "navVersion" to JsonPrimitive("navgraph"),
        "schemaVersion" to JsonPrimitive(1),
        "nodes" to JsonArray(nodes),
        "edges" to JsonArray(edges.toList()),
      ),
    )
    val out = File(outDir, manifestName)
    out.writeText(json.encodeToString(JsonElement.serializer(), merged))
    logger.lifecycle(
      "navgraph: aggregated ${nodes.size} node(s), ${edges.size} edge(s) from " +
        "${sources.size} module(s) → ${out.path}.",
    )
  }

  /** Copy every thumbnail this node references into the combined [outThumbs] — prefixed by [sourceIndex] so
   *  same-named PNGs from different modules can't collide — rewriting the manifest paths to match. */
  private fun copyThumbnails(
    node: JsonObject,
    sourceIndex: Int,
    thumbsRoot: File?,
    outThumbs: File,
  ): JsonObject {
    val previews = node["previews"] as? JsonArray ?: return node
    val rewritten = previews.map { element ->
      val preview = element.jsonObject
      val thumb = preview["thumbnail"]?.jsonPrimitive?.contentOrNull ?: return@map element
      val fileName = thumb.substringAfterLast('/')
      val src = thumbsRoot?.resolve(fileName)
      if (src == null || !src.isFile) return@map element
      val unique = "m${sourceIndex}_$fileName"
      src.copyTo(File(outThumbs, unique), overwrite = true)
      JsonObject(preview + ("thumbnail" to JsonPrimitive("thumbs/$unique")))
    }
    return JsonObject(node + ("previews" to JsonArray(rewritten)))
  }

  /** Of two copies of the same node id, keep the one carrying more rendered thumbnails (a real node beats a
   *  no-preview stub); ties keep the incumbent. */
  private fun richer(current: JsonObject?, candidate: JsonObject): JsonObject {
    if (current == null) return candidate
    return if (thumbnailCount(candidate) > thumbnailCount(current)) candidate else current
  }

  private fun thumbnailCount(node: JsonObject): Int =
    (node["previews"] as? JsonArray).orEmptyArray().count {
      it.jsonObject["thumbnail"]?.jsonPrimitive?.contentOrNull != null
    }
}

private fun JsonArray?.orEmptyArray(): JsonArray = this ?: JsonArray(emptyList())
