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

import org.gradle.api.logging.Logger

/** How one destination or transition changed since the committed `.nav` baseline. */
internal enum class DiffStatus { ADDED, REMOVED, CHANGED, UNCHANGED }

/**
 * Per-element verdicts for a graph rendered against a baseline, indexed the way the renderers already walk it: nodes
 * by id, edges by **position** in the returned graph's edge list.
 *
 * Position, not content: two transitions can share `(from, to, label)` — an annotated one and an inferred one, which
 * `AggregateNavGraphTask` unions as distinct JSON objects — and a content key would let one silently overwrite the
 * other's verdict, painting an untouched edge as newly added.
 */
internal class GraphDiff(
  private val nodes: Map<String, DiffStatus>,
  private val edges: List<DiffStatus>,
) {
  fun node(id: String): DiffStatus = nodes[id] ?: DiffStatus.UNCHANGED
  fun edge(index: Int): DiffStatus = edges.getOrElse(index) { DiffStatus.UNCHANGED }

  /** Whether anything actually moved — a clean diff still renders, but reports "no changes". */
  val hasChanges: Boolean
    get() = (nodes.values + edges).any { it != DiffStatus.UNCHANGED }

  fun count(status: DiffStatus): Int =
    nodes.values.count { it == status } + edges.count { it == status }

  /** `"2 added, 1 removed"` — or `"no changes"` when the graph still matches the baseline exactly. */
  fun summary(): String = listOfNotNull(
    count(DiffStatus.ADDED).takeIf { it > 0 }?.let { "$it added" },
    count(DiffStatus.REMOVED).takeIf { it > 0 }?.let { "$it removed" },
    count(DiffStatus.CHANGED).takeIf { it > 0 }?.let { "$it changed" },
  ).joinToString(", ").ifEmpty { "no changes" }
}

/** A destination that exists only in the baseline keeps this id prefix, so it can never collide with a real node. */
private const val GHOST_PREFIX = "navgraph.removed:"

/**
 * The graph to draw for a diff, plus the verdict on every element.
 *
 * The returned [HGraph] is the current graph **augmented** with ghost nodes and edges for anything only the
 * baseline still has — so the existing layout, which only knows how to place `HGraph.nodes`, positions removals
 * alongside the rest with no changes to it at all.
 *
 * Comparison runs on the exact `dest`/`edge` lines [renderBaseline] emits, via the shared [destLine] / [edgeLine]
 * helpers. That is deliberate: the picture and `navCheck` are then reading the same facts, so a PR can never show a
 * green diff while the build fails, or vice versa.
 */
internal fun diffAgainstBaseline(
  current: HGraph,
  baselineText: String,
  includeInferred: Boolean = false,
  logger: Logger? = null,
): Pair<HGraph, GraphDiff> {
  val baseline = parseBaseline(baselineText)
  // A hand-edited or truncated baseline parses to nothing and would silently render the whole app as "added".
  if (baseline.dests.isEmpty() && baseline.edges.isEmpty()) {
    logger?.warn(
      "navgraph: the baseline holds no destinations or transitions — every screen will read " +
        "as added. If that is not intended, the file is truncated or hand-edited; re-run navDump.",
    )
  }
  val names = baselineNames(current)

  // A node's name in the baseline may differ from its name now: `renderBaseline` disambiguates by FQN only when two
  // destinations share a simple name, so deleting one of a colliding pair silently renames the survivor. Match each
  // node against every form it could have been written as, claiming baseline entries one-to-one.
  val claimed = HashSet<String>()
  val baselineNameOf = HashMap<String, String>()
  current.nodes.forEach { node ->
    val candidates = listOf(names.getValue(node.id), "${node.route} (${node.id})", node.route)
    candidates.firstOrNull { it in baseline.dests && it !in claimed }?.let {
      baselineNameOf[node.id] = it
      claimed += it
    }
  }
  fun nameOf(id: String): String = baselineNameOf[id] ?: names[id] ?: id

  val nodeStatus = LinkedHashMap<String, DiffStatus>()
  current.nodes.forEach { node ->
    val before = baselineNameOf[node.id]?.let { baseline.dests[it] }
    nodeStatus[node.id] = when {
      before == null -> DiffStatus.ADDED
      before == destLine(node, baselineNameOf.getValue(node.id)) -> DiffStatus.UNCHANGED
      else -> DiffStatus.CHANGED
    }
  }

  // Keep each ghost paired with the baseline name it came from, so a removed transition can resolve its endpoints.
  val ghosts = baseline.dests.keys
    .filterNot { it in claimed }
    .map { name -> name to ghostNode(name, baseline.dests.getValue(name)) }
  val ghostNodes = ghosts.map { it.second }
  ghostNodes.forEach { nodeStatus[it.id] = DiffStatus.REMOVED }
  val idForName = current.nodes.associate { nameOf(it.id) to it.id } +
    ghosts.associate { (name, node) -> name to node.id }

  // Statuses are indexed by position in the augmented edge list, never by the edge's contents: two edges can share
  // (from, to, label) — an annotated one and an inferred one after aggregation — and a content key would let one
  // silently overwrite the other's verdict.
  val edgeStatus = mutableListOf<DiffStatus>()
  // Inferred transitions are shown but not compared unless the baseline records them too; otherwise every one of
  // them would read as "added" forever. They stay in the picture so the diff shows the same arrows the graph does.
  val comparable = current.edges.map { includeInferred || !it.inferred }
  val unmatchedBefore = baseline.edges.toMutableList()
  val verdicts = arrayOfNulls<DiffStatus>(current.edges.size)
  // Matched in two passes against a shrinking pool of baseline lines: exact-line first, then same-endpoints. That
  // ordering is what keeps parallel transitions (same screens, different labels) independent — a one-per-pair map
  // would report a genuinely new second arrow as a relabel of the first.
  current.edges.forEachIndexed { index, edge ->
    if (!comparable[index]) return@forEachIndexed
    val line = edgeLine(nameOf(edge.from), nameOf(edge.to), edge.label, edge.inferred)
    val exact = unmatchedBefore.indexOfFirst { it.line == line }
    if (exact >= 0) {
      unmatchedBefore.removeAt(exact)
      verdicts[index] = DiffStatus.UNCHANGED
    }
  }
  current.edges.forEachIndexed { index, edge ->
    if (!comparable[index] || verdicts[index] != null) return@forEachIndexed
    val from = nameOf(edge.from)
    val to = nameOf(edge.to)
    val sameEndpoints = unmatchedBefore.indexOfFirst { it.from == from && it.to == to }
    verdicts[index] = if (sameEndpoints >= 0) {
      unmatchedBefore.removeAt(sameEndpoints)
      DiffStatus.CHANGED
    } else {
      DiffStatus.ADDED
    }
  }
  verdicts.forEach { edgeStatus += it ?: DiffStatus.UNCHANGED }

  val ghostEdges = unmatchedBefore.mapNotNull { before ->
    val fromId = idForName[before.from] ?: return@mapNotNull null
    val toId = idForName[before.to] ?: return@mapNotNull null
    HEdge(
      from = fromId,
      to = toId,
      label = before.label,
      confidence = if (before.inferred) INFERRED else ANNOTATED,
    )
  }
  ghostEdges.forEach { edgeStatus += DiffStatus.REMOVED }

  val augmented = current.copy(
    nodes = current.nodes + ghostNodes,
    edges = current.edges + ghostEdges,
  )
  return augmented to GraphDiff(nodeStatus, edgeStatus)
}

/** A destination that only the baseline has: no thumbnail, but its recorded args so the box still says what it was. */
private fun ghostNode(name: String, line: String): HNode = HNode(
  // The display name is the baseline's own key, so it is unique — two ghosts can never collapse into one box.
  id = GHOST_PREFIX + name,
  // `Route (com.app.Route)` is the disambiguated form; show just the simple name.
  route = name.substringBefore(" ("),
  args = parseBaselineArgs(line),
  start = "  start" in line,
)

/** One `edge …` line from a `.nav` file, split into the facts it states plus the verbatim line it came from. */
internal data class BaselineEdge(
  val from: String,
  val to: String,
  val label: String?,
  val line: String,
  /** Whether the line carried [INFERRED_MARKER], so a ghost of it redraws dashed rather than solid. */
  val inferred: Boolean = false,
)

/** Everything a `.nav` file states. Destinations are unique by display name; transitions are kept as a list so
 *  parallel ones survive. */
internal class BaselineSnapshot(
  /** display name → its full `dest …` line. */
  val dests: Map<String, String>,
  val edges: List<BaselineEdge>,
)

/**
 * Reads a committed `.nav` file back into the facts it records — the inverse of [renderBaseline].
 *
 * Optional fields are appended after a double space, so an `edge` line is peeled from the right (the `(inferred)`
 * marker, then the quoted label) rather than split on `"  "` — a label is allowed to contain a double space, and
 * splitting would tear it in half.
 */
internal fun parseBaseline(text: String): BaselineSnapshot {
  val dests = LinkedHashMap<String, String>()
  val edges = mutableListOf<BaselineEdge>()
  baselineContent(text).forEach { line ->
    when {
      line.startsWith("dest ") -> dests[line.removePrefix("dest ").split("  ").first()] = line

      line.startsWith("edge ") -> {
        val body = line.removePrefix("edge ")
        val inferred = body.endsWith("  $INFERRED_MARKER")
        var rest = body.removeSuffix("  $INFERRED_MARKER")
        var label: String? = null
        if (rest.endsWith('"')) {
          // Anchor on the label's OPENING `  "`, which is the first one: route display names never contain a
          // quote, while a label ending in two spaces (`"retry  "`) puts another `  "` right at the end — matching
          // that one instead yields a reversed substring range.
          val at = rest.indexOf("  \"")
          if (at >= 0 && at + 3 <= rest.length - 1) {
            label = unescapeLabel(rest.substring(at + 3, rest.length - 1))
            rest = rest.substring(0, at)
          }
        }
        val endpoints = rest.split(" -> ")
        if (endpoints.size == 2) {
          edges += BaselineEdge(endpoints[0], endpoints[1], label, line, inferred)
        }
      }
    }
  }
  return BaselineSnapshot(dests, edges)
}

/** Inverse of the `\`/newline/quote escaping `renderBaseline` applies. Read left to right — chained `replace`s
 *  would mangle a label that literally contains a backslash followed by a quote. */
private fun unescapeLabel(text: String): String = buildString {
  var i = 0
  while (i < text.length) {
    val c = text[i]
    val next = text.getOrNull(i + 1)
    if (c == '\\' && next != null) {
      when (next) {
        'n' -> append('\n')
        'r' -> append('\r')
        '\\' -> append('\\')
        '"' -> append('"')
        else -> append(c).append(next)
      }
      i += 2
    } else {
      append(c)
      i++
    }
  }
}

/**
 * The args a `dest …` line records, back as [HArg]s the node box can draw. Types are recovered as the baseline's
 * already-display form, so they are stored verbatim in [HArg.type] (which [displayType] passes through unchanged).
 */
private fun parseBaselineArgs(line: String): List<HArg> {
  val inside = line.substringAfter("  args=(", "").substringBeforeLast(')', "")
  if (inside.isEmpty()) return emptyList()
  return splitTopLevel(inside).mapNotNull { entry ->
    val name = entry.substringBefore(": ", "").trim()
    if (name.isEmpty()) return@mapNotNull null
    val rest = entry.substringAfter(": ")
    val optional = rest.endsWith(" = …")
    HArg(name = name, type = rest.removeSuffix(" = …"), optional = optional)
  }
}

/** Split on `, ` at generic depth 0, so `Map<String, Int>` stays one argument. */
private fun splitTopLevel(text: String): List<String> {
  val parts = mutableListOf<String>()
  var depth = 0
  var start = 0
  text.forEachIndexed { index, c ->
    when {
      c == '<' -> depth++

      c == '>' -> depth--

      c == ',' && depth == 0 -> {
        parts += text.substring(start, index).trim()
        start = index + 1
      }
    }
  }
  parts += text.substring(start).trim()
  return parts.filter { it.isNotEmpty() }
}
