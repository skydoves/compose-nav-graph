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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the graph shape rules behind `navLint`. The case that matters most is the cycle disconnected from the start:
 * it is exactly where lint has to disagree with the picture, whose "Unconnected screens" split is degree-0 and
 * would draw those screens inside the flow.
 */
class NavLintTest {

  private fun node(id: String, start: Boolean = false, bound: Boolean = true) = HNode(
    id = id,
    route = id.substringAfterLast('.'),
    start = start,
    clickTargetFqn = if (bound) id + "Screen" else null,
  )

  private fun edge(from: String, to: String) = HEdge(from = from, to = to)

  private fun rules(findings: List<NavLintFinding>) = findings.map { it.rule.id to it.displayName }

  @Test
  fun aFullyWiredGraphIsClean() {
    val graph = HGraph(
      nodes = listOf(node("x.Home", start = true), node("x.Feed"), node("x.Profile")),
      edges = listOf(edge("x.Home", "x.Feed"), edge("x.Feed", "x.Profile")),
    )
    assertEquals(emptyList<Pair<String, String>>(), rules(analyzeGraph(graph)))
  }

  @Test
  fun aScreenNothingLinksToIsUnreachable() {
    val graph = HGraph(
      nodes = listOf(node("x.Home", start = true), node("x.Feed"), node("x.Lost")),
      edges = listOf(edge("x.Home", "x.Feed")),
    )
    assertEquals(listOf("unreachable" to "Lost"), rules(analyzeGraph(graph)))
  }

  @Test
  fun aCycleDisconnectedFromStartIsStillUnreachable() {
    // A <-> B link to each other, so both have edges and the exports' degree-0 split draws them inside the flow.
    // Neither is reachable from Home, which is the question lint actually asks.
    val graph = HGraph(
      nodes = listOf(node("x.Home", start = true), node("x.A"), node("x.B")),
      edges = listOf(edge("x.A", "x.B"), edge("x.B", "x.A")),
    )
    assertEquals(listOf("unreachable" to "A", "unreachable" to "B"), rules(analyzeGraph(graph)))
  }

  @Test
  fun anIncomingEdgeAloneDoesNotMakeAScreenReachable() {
    // Direction matters: Home is the start, but the only edge points INTO Home from an unreachable screen.
    val graph = HGraph(
      nodes = listOf(node("x.Home", start = true), node("x.Deep")),
      edges = listOf(edge("x.Deep", "x.Home")),
    )
    assertEquals(listOf("unreachable" to "Deep"), rules(analyzeGraph(graph)))
  }

  @Test
  fun aStartDestinationIsNeverItselfUnreachable() {
    val graph = HGraph(nodes = listOf(node("x.Home", start = true)))
    assertEquals(emptyList<Pair<String, String>>(), rules(analyzeGraph(graph)))
  }

  @Test
  fun noStartIsReportedOnceAndSuppressesReachability() {
    // Without a seed every screen would read as unreachable, which buries the one finding that explains why.
    val graph = HGraph(
      nodes = listOf(node("x.Home"), node("x.Feed")),
      edges = listOf(edge("x.Home", "x.Feed")),
    )
    assertEquals(listOf("no-start" to "(graph)"), rules(analyzeGraph(graph)))
  }

  @Test
  fun anEmptyGraphReportsNothing() {
    assertEquals(emptyList<Pair<String, String>>(), rules(analyzeGraph(HGraph())))
  }

  @Test
  fun twoStartsAreBothReported() {
    val graph =
      HGraph(nodes = listOf(node("x.Home", start = true), node("x.Onboarding", start = true)))
    assertEquals(
      listOf("multiple-starts" to "Home", "multiple-starts" to "Onboarding"),
      rules(analyzeGraph(graph)),
    )
  }

  @Test
  fun aRouteWithNoDestinationIsUnbound() {
    val graph = HGraph(
      nodes = listOf(node("x.Home", start = true), node("x.Feed", bound = false)),
      edges = listOf(edge("x.Home", "x.Feed")),
    )
    assertEquals(listOf("unbound-route" to "Feed"), rules(analyzeGraph(graph)))
  }

  @Test
  fun oneScreenCanCarryTwoFindings() {
    val graph = HGraph(nodes = listOf(node("x.Home", start = true), node("x.Ghost", bound = false)))
    assertEquals(
      listOf("unreachable" to "Ghost", "unbound-route" to "Ghost"),
      rules(analyzeGraph(graph)),
    )
  }

  @Test
  fun aDisabledRuleReportsNothing() {
    val graph = HGraph(nodes = listOf(node("x.Home", start = true), node("x.Ghost", bound = false)))
    assertEquals(
      listOf("unreachable" to "Ghost"),
      rules(analyzeGraph(graph, disabledRules = setOf("unbound-route"))),
    )
  }

  @Test
  fun anIgnoredRouteReportsNothingFromAnyRule() {
    val graph = HGraph(nodes = listOf(node("x.Home", start = true), node("x.Ghost", bound = false)))
    assertEquals(
      emptyList<Pair<String, String>>(),
      rules(analyzeGraph(graph, ignoredRoutes = setOf("x.Ghost"))),
    )
  }

  @Test
  fun findingsAreOrderedDeterministically() {
    // Rule order first, then display name — so the report reads the same on every machine and every run.
    val graph = HGraph(
      nodes = listOf(
        node("x.Home", start = true),
        node("x.Zeta", bound = false),
        node("x.Alpha", bound = false),
      ),
    )
    assertEquals(
      listOf(
        "unreachable" to "Alpha",
        "unreachable" to "Zeta",
        "unbound-route" to "Alpha",
        "unbound-route" to "Zeta",
      ),
      rules(analyzeGraph(graph)),
    )
  }

  @Test
  fun sameNamedRoutesAreDisambiguatedLikeTheBaseline() {
    // baselineNames() qualifies a shared simple name by FQN; lint must name a screen the way the .nav file does.
    val graph = HGraph(
      nodes = listOf(
        node("x.Home", start = true),
        node("a.Detail", bound = false),
        node("b.Detail"),
      ),
      edges = listOf(edge("x.Home", "a.Detail"), edge("x.Home", "b.Detail")),
    )
    assertEquals(listOf("unbound-route" to "Detail (a.Detail)"), rules(analyzeGraph(graph)))
  }

  @Test
  fun reachabilityFollowsALongChain() {
    val ids = (0 until 50).map { "x.S$it" }
    val graph = HGraph(
      nodes = ids.mapIndexed { index, id -> node(id, start = index == 0) },
      edges = ids.zipWithNext().map { (from, to) -> edge(from, to) },
    )
    assertTrue(reachableFrom(graph, setOf("x.S0")).size == 50)
    assertEquals(emptyList<Pair<String, String>>(), rules(analyzeGraph(graph)))
  }

  @Test
  fun reachabilityTerminatesOnASelfLoop() {
    val graph = HGraph(
      nodes = listOf(node("x.Home", start = true)),
      edges = listOf(edge("x.Home", "x.Home")),
    )
    assertEquals(setOf("x.Home"), reachableFrom(graph, setOf("x.Home")))
  }

  @Test
  fun anEdgeToAnUnknownNodeDoesNotCrashReachability() {
    val graph = HGraph(
      nodes = listOf(node("x.Home", start = true)),
      edges = listOf(edge("x.Home", "x.Gone")),
    )
    assertEquals(setOf("x.Home", "x.Gone"), reachableFrom(graph, setOf("x.Home")))
    assertEquals(emptyList<Pair<String, String>>(), rules(analyzeGraph(graph)))
  }
}
