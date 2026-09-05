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
  val locale: String? = null,
  val widthDp: Int? = null,
  val heightDp: Int? = null,
  val device: String? = null,
)

internal data class HPreviewParam(val name: String = "", val provider: String = "")

internal data class HEdge(
  val from: String = "",
  val to: String = "",
  val label: String? = null,
  /** `EdgeConfidence` as written in the manifest — `"ANNOTATED"` (a `@NavEdge`) or `"INFERRED"` (a call site
   *  recovered by [KotlinNavScanner]). Unknown values from a newer manifest read as not-inferred. */
  val confidence: String = ANNOTATED,
)

internal const val ANNOTATED: String = "ANNOTATED"
internal const val INFERRED: String = "INFERRED"

internal val HEdge.inferred: Boolean get() = confidence == INFERRED

/** Restrict [graph] to a feature package [prefix] (e.g. `com.app.feature.feed`): keep nodes whose route FQN
 *  (`id`) or screen FQN (`clickTargetFqn`) is under that package, plus edges with both endpoints kept. A blank
 *  prefix returns [graph] unchanged. Backs the `-Pnavgraph.export.package=` filter for single-module apps that
 *  organize screens by feature package. */
internal fun filterGraphByPackage(graph: HGraph, prefix: String): HGraph {
  if (prefix.isBlank()) return graph
  fun underPackage(fqn: String?): Boolean =
    fqn != null && (fqn == prefix || fqn.startsWith("$prefix."))
  val nodes = graph.nodes.filter { underPackage(it.id) || underPackage(it.clickTargetFqn) }
  val ids = nodes.mapTo(HashSet()) { it.id }
  return graph.copy(nodes = nodes, edges = graph.edges.filter { it.from in ids && it.to in ids })
}

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
          locale = po.str("locale"),
          widthDp = po.int("widthDp"),
          heightDp = po.int("heightDp"),
          device = po.str("device"),
        )
      },
      start = o.bool("start"),
    )
  }
  val edges = root.arr("edges").map { el ->
    val o = el.jsonObject
    HEdge(
      from = o.str("from") ?: "",
      to = o.str("to") ?: "",
      label = o.str("label"),
      confidence = o.str("confidence") ?: ANNOTATED,
    )
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
 *
 * Inferred transitions are left out unless [includeInferred] (`navgraph { baselineIncludesInferred }`), so turning
 * edge inference on never invalidates a committed baseline; when included they carry a trailing `(inferred)` so the
 * two kinds stay distinguishable in a diff.
 */
internal fun renderBaseline(graph: HGraph, includeInferred: Boolean = false): String = buildString {
  appendLine("# Navigation 3 baseline — schema ${graph.schemaVersion}")
  appendLine(
    "# Generated by the navgraph 'navDump' task. Commit this file; 'navCheck' fails if it drifts. Do not edit by hand.",
  )
  appendLine()
  val names = baselineNames(graph)
  graph.nodes.sortedWith(compareBy({ it.route }, { it.id })).forEach { node ->
    appendLine(destLine(node, names.getValue(node.id)))
  }
  graph.edges
    .filter { includeInferred || !it.inferred }
    .map { e ->
      RenderedEdge(names[e.from] ?: e.from, names[e.to] ?: e.to, e.label ?: "", e.inferred)
    }
    .sortedWith(compareBy({ it.from }, { it.to }, { it.label }, { it.inferred }))
    .forEach { appendLine(edgeLine(it.from, it.to, it.label, it.inferred)) }
}

/**
 * The name each node goes by in the baseline, keyed by node id. Disambiguated by FQN only when a simple route name
 * is shared by more than one destination (rare) — that keeps the common case readable while staying injective, so
 * [NavCheckTask] can't miss a change between two same-simple-name classes.
 */
internal fun baselineNames(graph: HGraph): Map<String, String> {
  val routeCounts = graph.nodes.groupingBy { it.route }.eachCount()
  return graph.nodes.associate { node ->
    node.id to if ((routeCounts[node.route] ?: 0) > 1) "${node.route} (${node.id})" else node.route
  }
}

/** One `dest …` baseline line. Shared with [diffAgainstBaseline] so the picture and `navCheck` can never disagree. */
internal fun destLine(node: HNode, name: String): String = buildString {
  append("dest ").append(name)
  if (node.start) append("  start")
  if (node.args.isNotEmpty()) {
    append("  args=(")
    append(
      node.args.joinToString(", ") {
        oneLine(it.name) + ": " + displayType(it) + (if (it.optional) " = …" else "")
      },
    )
    append(")")
  }
}

/** One `edge …` baseline line. Shared with [diffAgainstBaseline], same reason as [destLine]. */
internal fun edgeLine(from: String, to: String, label: String?, inferred: Boolean): String =
  buildString {
    append("edge ").append(from).append(" -> ").append(to)
    if (!label.isNullOrEmpty()) append("  \"").append(escapeLabel(label)).append('"')
    if (inferred) append("  ").append(INFERRED_MARKER)
  }

/** One transition, resolved to the names the baseline uses, ready to sort and render. */
private data class RenderedEdge(
  val from: String,
  val to: String,
  val label: String,
  val inferred: Boolean,
)

/** Marks an inferred transition in the `.nav` baseline. */
internal const val INFERRED_MARKER: String = "(inferred)"

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
