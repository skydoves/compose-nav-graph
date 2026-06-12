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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The shared, in-memory mirror of `nav-graph.json` plus its parser and the two text renderers
 * (HTML arg display + the `.nav` baseline). Parsed via JsonElement surgery — no `@Serializable`, so this
 * needs no serialization compiler plugin and keeps the deliberate no-`compose-nav-graph-annotations` boundary.
 * Used by both [ExportNavGraphHtmlTask] and the baseline tasks ([NavDumpTask] / [NavCheckTask]).
 */
internal data class HGraph(
  val navVersion: String = "navgraph",
  val schemaVersion: Int = 1,
  val nodes: List<HNode> = emptyList(),
  val edges: List<HEdge> = emptyList(),
)

internal data class HNode(
  val id: String = "",
  val route: String = "",
  val module: String? = null,
  val clickTargetFqn: String? = null,
  val sourceFile: String? = null,
  val sourceLine: Int? = null,
  val args: List<HArg> = emptyList(),
  val previews: List<HPreview> = emptyList(),
  val start: Boolean = false,
)

internal data class HArg(
  val name: String = "",
  val type: String = "",
  val typeArguments: List<String> = emptyList(),
  val nullable: Boolean = false,
  val optional: Boolean = false,
  val enum: Boolean = false,
)

internal data class HPreview(
  val previewName: String = "",
  val previewFqn: String? = null,
  val previewMethodFqn: String? = null,
  val previewParameters: List<HPreviewParam> = emptyList(),
  val thumbnail: String? = null,
  val primary: Boolean = false,
)

internal data class HPreviewParam(val name: String = "", val provider: String = "")

internal data class HEdge(val from: String = "", val to: String = "", val label: String? = null)

internal fun parseGraph(text: String): HGraph {
  val root = Json.parseToJsonElement(text).jsonObject
  val nodes = root.arr("nodes").map { el ->
    val o = el.jsonObject
    HNode(
      id = o.str("id") ?: "",
      route = o.str("route") ?: "",
      module = o.str("module"),
      clickTargetFqn = o.str("clickTargetFqn"),
      sourceFile = o.str("sourceFile"),
      sourceLine = o.int("sourceLine"),
      args = o.arr("args").map { a ->
        val ao = a.jsonObject
        HArg(
          name = ao.str("name") ?: "",
          type = ao.str("type") ?: "",
          typeArguments = ao.arr("typeArguments").mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
          },
          nullable = ao.bool("nullable"),
          optional = ao.bool("optional"),
          enum = ao.bool("enum"),
        )
      },
      previews = o.arr("previews").map { p ->
        val po = p.jsonObject
        HPreview(
          previewName = po.str("previewName") ?: "",
          previewFqn = po.str("previewFqn"),
          previewMethodFqn = po.str("previewMethodFqn"),
          previewParameters = po.arr("previewParameters").map { pp ->
            val ppo = pp.jsonObject
            HPreviewParam(name = ppo.str("name") ?: "", provider = ppo.str("provider") ?: "")
          },
          thumbnail = po.str("thumbnail"),
          primary = po.bool("primary"),
        )
      },
      start = o.bool("start"),
    )
  }
  val edges = root.arr("edges").map { el ->
    val o = el.jsonObject
    HEdge(from = o.str("from") ?: "", to = o.str("to") ?: "", label = o.str("label"))
  }
  return HGraph(
    navVersion = root.str("navVersion") ?: "navgraph",
    schemaVersion = root.int("schemaVersion") ?: 1,
    nodes = nodes,
    edges = edges,
  )
}

/**
 * Node title: the navigation-target composable's simple name (e.g. `SearchScreen`), falling back to the route
 * when no click target resolved. Shared so the canvas, PNG, and HTML renderers all label nodes identically.
 */
internal fun HNode.displayLabel(): String =
  clickTargetFqn?.substringAfterLast('.')?.ifBlank { null } ?: route

/** Kotlin-ish display type: last `.`-segment + generics + `?` if nullable (matches NavGraphCanvas). */
internal fun displayType(arg: HArg): String {
  val base = arg.type.substringAfterLast('.')
  val generics = if (arg.typeArguments.isNotEmpty()) {
    "<" + arg.typeArguments.joinToString(", ") { it.substringAfterLast('.') } + ">"
  } else {
    ""
  }
  return base + generics + (if (arg.nullable) "?" else "")
}

/**
 * The committed `.nav` baseline: a flat, sorted, deterministic, structure-only snapshot — one `dest`/`edge`
 * fact per line. Volatile fields (thumbnails, source locations, click targets, preview FQNs) are excluded so
 * the file only changes when navigation actually changes. `# ` comment lines are ignored by [NavCheckTask].
 */
internal fun renderBaseline(graph: HGraph): String = buildString {
  appendLine("# Navigation 3 baseline — schema ${graph.schemaVersion}")
  appendLine(
    "# Generated by the navgraph 'navDump' task. Commit this file; 'navCheck' fails if it drifts. Do not edit by hand.",
  )
  appendLine()
  // Disambiguate by FQN only when a simple route name is shared by >1 destination (rare) — keeps the common
  // case readable while staying injective, so navCheck can't miss a change between same-simple-name classes.
  val routeCounts = graph.nodes.groupingBy { it.route }.eachCount()
  fun displayName(n: HNode): String = if ((routeCounts[n.route] ?: 0) >
    1
  ) {
    "${n.route} (${n.id})"
  } else {
    n.route
  }
  val nameById = graph.nodes.associate { it.id to displayName(it) }
  graph.nodes.sortedWith(compareBy({ it.route }, { it.id })).forEach { n ->
    append("dest ").append(displayName(n))
    if (n.start) append("  start")
    if (n.args.isNotEmpty()) {
      append("  args=(")
      append(
        n.args.joinToString(", ") {
          oneLine(it.name) + ": " + displayType(it) +
            (if (it.optional) " = …" else "")
        },
      )
      append(")")
    }
    appendLine()
  }
  graph.edges
    .map { e -> Triple(nameById[e.from] ?: e.from, nameById[e.to] ?: e.to, e.label ?: "") }
    .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
    .forEach { (from, to, label) ->
      append("edge ").append(from).append(" -> ").append(to)
      if (label.isNotEmpty()) append("  \"").append(escapeLabel(label)).append('"')
      appendLine()
    }
}

/** Keep a token on one line (defensive against pathological arg names / SerialNames). */
private fun oneLine(s: String): String =
  s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

/** Quote-safe edge label: escapes `\`, newlines, and `"` so a literal quote/newline can't corrupt the line. */
private fun escapeLabel(s: String): String = oneLine(s).replace("\"", "\\\"")

/** The baseline content that [NavCheckTask] compares — drops `# ` comments + blank lines (header-agnostic). */
internal fun baselineContent(text: String): List<String> =
  text.lineSequence().map { it.trimEnd() }.filter {
    it.isNotBlank() &&
      !it.startsWith("#")
  }.toList()

// JsonElement accessors (null/absent-safe), shared by parseGraph.
internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.bool(k: String): Boolean =
  (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
internal fun JsonObject.arr(k: String): JsonArray = this[k] as? JsonArray ?: JsonArray(emptyList())
