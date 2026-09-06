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

// Shape analysis over an HGraph — the health check behind NavLintTask, kept free of Gradle types so it can be
// tested directly.
//
// This is deliberately NOT the "Unconnected screens" split the exports draw. That predicate is degree-0: it asks
// whether a node touches any edge at all, ignoring direction and ignoring the start destination, so a cluster of
// screens that link to each other but that nothing reaches from the start counts as "connected" and is drawn
// inside the flow. `reachableFrom` answers the question that actually matters — can the user get there — so the
// two sets legitimately differ, and lint reports screens the picture shows as part of the graph.

/** A shape problem [analyzeGraph] can report. The `id` is what `navgraph { navLintDisabledRules }` matches. */
internal enum class NavLintRule(val id: String) {
  /** No destination is marked `start`: the app has no `@NavGraphRoot`, so nothing anchors the graph. */
  NO_START("no-start"),

  /** More than one destination is marked `start`. */
  MULTIPLE_STARTS("multiple-starts"),

  /** No directed path from a start destination reaches this screen. */
  UNREACHABLE("unreachable"),

  /** A route with no `@NavDestination` bound to it — declared, but no screen renders it. */
  UNBOUND_ROUTE("unbound-route"),
}

/** One reported problem. [displayName] comes from [baselineNames] so lint and the `.nav` baseline agree on names. */
internal data class NavLintFinding(
  val rule: NavLintRule,
  val nodeId: String,
  val displayName: String,
  val detail: String,
)

/**
 * Every node reachable from [startIds] by following edges **in their declared direction**, the seeds included.
 *
 * Direction is the whole point: an edge into a screen does not make it reachable, only a path out of a start
 * destination does.
 */
internal fun reachableFrom(graph: HGraph, startIds: Set<String>): Set<String> {
  val outgoing = HashMap<String, MutableList<String>>()
  graph.edges.forEach { edge ->
    outgoing.getOrPut(edge.from) { mutableListOf() }.add(edge.to)
  }
  val seen = LinkedHashSet<String>()
  val queue = ArrayDeque(startIds)
  seen.addAll(startIds)
  while (queue.isNotEmpty()) {
    outgoing[queue.removeFirst()]?.forEach { next ->
      if (seen.add(next)) queue.addLast(next)
    }
  }
  return seen
}

/**
 * Run the enabled rules over [graph].
 *
 * @param disabledRules rule ids (see [NavLintRule.id]) to skip entirely.
 * @param ignoredRoutes node ids (route FQNs) that report nothing, for the deliberately-unwired screen.
 */
internal fun analyzeGraph(
  graph: HGraph,
  disabledRules: Set<String> = emptySet(),
  ignoredRoutes: Set<String> = emptySet(),
): List<NavLintFinding> {
  val names = baselineNames(graph)
  val findings = mutableListOf<NavLintFinding>()
  fun enabled(rule: NavLintRule) = rule.id !in disabledRules
  fun report(rule: NavLintRule, node: HNode, detail: String) {
    if (enabled(rule) && node.id !in ignoredRoutes) {
      findings += NavLintFinding(rule, node.id, names[node.id] ?: node.route, detail)
    }
  }

  val starts = graph.nodes.filter { it.start }
  when {
    starts.isEmpty() && enabled(NavLintRule.NO_START) && graph.nodes.isNotEmpty() -> {
      findings += NavLintFinding(
        NavLintRule.NO_START,
        nodeId = "",
        displayName = "(graph)",
        detail = "no destination is marked start — annotate one route with @NavGraphRoot",
      )
    }

    starts.size > 1 -> starts.forEach { node ->
      report(
        NavLintRule.MULTIPLE_STARTS,
        node,
        "marked start, and so are ${starts.size - 1} other destination(s)",
      )
    }
  }

  // Needs a seed, so it only runs once a start exists; NO_START above already reported the reason it cannot.
  if (starts.isNotEmpty()) {
    val reachable = reachableFrom(graph, starts.mapTo(mutableSetOf()) { it.id })
    val startLabel = starts.joinToString(", ") { names[it.id] ?: it.route }
    graph.nodes.filter { it.id !in reachable }.forEach { node ->
      report(NavLintRule.UNREACHABLE, node, "no path from $startLabel")
    }
  }

  graph.nodes.filter { it.clickTargetFqn == null }.forEach { node ->
    report(
      NavLintRule.UNBOUND_ROUTE,
      node,
      "declared as a route but no @NavDestination binds a screen to it",
    )
  }

  return findings.sortedWith(compareBy({ it.rule.ordinal }, { it.displayName }, { it.nodeId }))
}
