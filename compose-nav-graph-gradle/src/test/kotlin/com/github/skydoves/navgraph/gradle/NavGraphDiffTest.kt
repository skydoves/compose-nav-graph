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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the visual diff. The contract that matters: it reads the exact `dest`/`edge` lines `renderBaseline` writes,
 * so the picture and `navCheck` can never disagree about whether navigation changed.
 */
class NavGraphDiffTest {

  private val home = HNode(id = "x.Home", route = "Home", start = true)
  private val profile = HNode(
    id = "x.Profile",
    route = "Profile",
    args = listOf(HArg(name = "userId", type = "kotlin.String")),
  )

  private fun baselineOf(graph: HGraph) = renderBaseline(graph)

  @Test
  fun anUnchangedGraphDiffsToNothing() {
    val graph = HGraph(
      nodes = listOf(home, profile),
      edges = listOf(HEdge(from = "x.Home", to = "x.Profile", label = "go")),
    )
    val (augmented, diff) = diffAgainstBaseline(graph, baselineOf(graph))
    assertFalse(diff.hasChanges)
    assertEquals("no changes", diff.summary())
    assertEquals(graph.nodes.size, augmented.nodes.size)
    assertEquals(DiffStatus.UNCHANGED, diff.node("x.Home"))
  }

  @Test
  fun aRemovedInferredTransitionKeepsItsInferredConfidence() {
    // The ghost of a deleted transition is redrawn from the baseline line, so it has to carry back the
    // `(inferred)` marker that line recorded — otherwise a removed dashed edge reappears solid.
    val before = HGraph(
      nodes = listOf(home, profile),
      edges = listOf(HEdge(from = "x.Home", to = "x.Profile", confidence = INFERRED)),
    )
    val after = HGraph(nodes = listOf(home, profile))
    val (augmented, diff) = diffAgainstBaseline(
      after,
      renderBaseline(before, includeInferred = true),
      true,
    )
    val ghost = augmented.edges.single()
    assertTrue(ghost.inferred)
    assertEquals(DiffStatus.REMOVED, diff.edge(0))
  }

  @Test
  fun aRemovedDeclaredTransitionStaysAnnotated() {
    val before = HGraph(
      nodes = listOf(home, profile),
      edges = listOf(HEdge(from = "x.Home", to = "x.Profile", label = "go")),
    )
    val after = HGraph(nodes = listOf(home, profile))
    val (augmented, _) = diffAgainstBaseline(after, renderBaseline(before))
    assertFalse(augmented.edges.single().inferred)
  }

  @Test
  fun aNewDestinationAndTransitionAreAdded() {
    val before = HGraph(nodes = listOf(home))
    val after = HGraph(
      nodes = listOf(home, profile),
      edges = listOf(HEdge(from = "x.Home", to = "x.Profile")),
    )
    val (_, diff) = diffAgainstBaseline(after, baselineOf(before))
    assertEquals(DiffStatus.UNCHANGED, diff.node("x.Home"))
    assertEquals(DiffStatus.ADDED, diff.node("x.Profile"))
    assertEquals(DiffStatus.ADDED, diff.edge(0))
    assertEquals("2 added", diff.summary())
  }

  @Test
  fun aDeletedDestinationBecomesAGhostNodeCarryingItsArgs() {
    val before = HGraph(
      nodes = listOf(home, profile),
      edges = listOf(HEdge(from = "x.Home", to = "x.Profile", label = "go")),
    )
    val after = HGraph(nodes = listOf(home))
    val (augmented, diff) = diffAgainstBaseline(after, baselineOf(before))

    // The removal is laid out alongside the surviving screens rather than silently vanishing.
    val ghost = augmented.nodes.single { it.route == "Profile" }
    assertEquals(DiffStatus.REMOVED, diff.node(ghost.id))
    assertEquals(listOf("userId" to "String"), ghost.args.map { it.name to displayType(it) })
    // …and so does the transition that pointed at it, label included.
    val ghostEdge = augmented.edges.single()
    assertEquals(DiffStatus.REMOVED, diff.edge(0))
    assertEquals("go", ghostEdge.label)
    assertEquals("2 removed", diff.summary())
  }

  @Test
  fun changedArgumentsMarkTheDestinationChangedNotReplaced() {
    val before = HGraph(nodes = listOf(home, profile))
    val after = HGraph(
      nodes = listOf(
        home,
        profile.copy(
          args = profile.args + HArg(name = "tab", type = "kotlin.Int", optional = true),
        ),
      ),
    )
    val (augmented, diff) = diffAgainstBaseline(after, baselineOf(before))
    assertEquals(DiffStatus.CHANGED, diff.node("x.Profile"))
    assertEquals(2, augmented.nodes.size)
    assertEquals("1 changed", diff.summary())
  }

  @Test
  fun aRelabelledTransitionIsChanged() {
    val before = HGraph(
      nodes = listOf(home, profile),
      edges = listOf(HEdge(from = "x.Home", to = "x.Profile", label = "go")),
    )
    val after = before.copy(
      edges = listOf(HEdge(from = "x.Home", to = "x.Profile", label = "open profile")),
    )
    val (_, diff) = diffAgainstBaseline(after, baselineOf(before))
    assertEquals(DiffStatus.CHANGED, diff.edge(0))
  }

  @Test
  fun inferredTransitionsAreDrawnButNotCompared() {
    // They are not in the baseline either (baselineIncludesInferred defaults to false), so comparing them would
    // report every inferred edge as "added" forever. They still have to appear, or the diff page would show fewer
    // arrows than the graph page for the very same graph.
    val before = HGraph(nodes = listOf(home, profile))
    val after = before.copy(
      edges = listOf(HEdge(from = "x.Home", to = "x.Profile", confidence = INFERRED)),
    )
    val (augmented, diff) = diffAgainstBaseline(after, baselineOf(before))
    assertFalse(diff.hasChanges)
    assertEquals(1, augmented.edges.size)
    assertEquals(DiffStatus.UNCHANGED, diff.edge(0))
  }

  @Test
  fun anInferredTransitionRecordedInTheBaselineIsNotReportedRemoved() {
    // With baselineIncludesInferred = true, navDump writes `edge Home -> Profile  (inferred)`. The diff has to be
    // told, or it compares an inferred-free graph against an inferred-bearing baseline and calls it a removal —
    // a red diff on a repo where navCheck is green.
    val graph = HGraph(
      nodes = listOf(home, profile),
      edges = listOf(HEdge(from = "x.Home", to = "x.Profile", confidence = INFERRED)),
    )
    val (_, diff) = diffAgainstBaseline(
      graph,
      renderBaseline(graph, includeInferred = true),
      includeInferred = true,
    )
    assertFalse(diff.hasChanges)
    assertEquals(DiffStatus.UNCHANGED, diff.edge(0))
  }

  @Test
  fun anAnnotatedAndAnInferredEdgeBetweenTheSameScreensAreJudgedSeparately() {
    // Aggregation unions them as distinct entries; a content-keyed verdict map let one overwrite the other and
    // painted the untouched annotated edge as newly added.
    val before = HGraph(
      nodes = listOf(home, profile),
      edges = listOf(HEdge(from = "x.Home", to = "x.Profile")),
    )
    val after = before.copy(
      edges = listOf(
        HEdge(from = "x.Home", to = "x.Profile"),
        HEdge(from = "x.Home", to = "x.Profile", confidence = INFERRED),
      ),
    )
    val (_, diff) = diffAgainstBaseline(after, renderBaseline(before, includeInferred = true), true)
    assertEquals(DiffStatus.UNCHANGED, diff.edge(0))
    assertEquals(DiffStatus.ADDED, diff.edge(1))
  }

  @Test
  fun deletingOneOfTwoSameNamedDestinationsLeavesTheSurvivorUntouched() {
    // `renderBaseline` only disambiguates by FQN while the collision exists, so deleting b.Foo renames a.Foo from
    // `Foo (a.Foo)` to plain `Foo` — which used to read as "a.Foo added, both old ones removed".
    val a = HNode(id = "a.Foo", route = "Foo")
    val b = HNode(id = "b.Foo", route = "Foo")
    val (augmented, diff) = diffAgainstBaseline(
      HGraph(nodes = listOf(a)),
      renderBaseline(HGraph(nodes = listOf(a, b))),
    )
    assertEquals(DiffStatus.UNCHANGED, diff.node("a.Foo"))
    assertEquals(1, diff.count(DiffStatus.REMOVED))
    assertEquals(0, diff.count(DiffStatus.ADDED))
    assertEquals(2, augmented.nodes.size)
  }

  @Test
  fun aLabelEndingInTwoSpacesDoesNotCrashTheParser() {
    // The closing quote of `"retry  "` is itself preceded by two spaces, so anchoring on the LAST `  "` produced a
    // reversed substring range and threw.
    val graph = HGraph(
      nodes = listOf(home, profile),
      edges = listOf(HEdge(from = "x.Home", to = "x.Profile", label = "retry  ")),
    )
    val (_, diff) = diffAgainstBaseline(graph, baselineOf(graph))
    assertFalse(diff.hasChanges)
    assertEquals("retry  ", parseBaseline(baselineOf(graph)).edges.single().label)
  }

  @Test
  fun twoRemovedDestinationsWithTheSameFqnStayTwoGhosts() {
    // Only reachable from a hand-edited baseline, but two ghosts must never collapse into one box.
    val baseline = "dest Alpha (com.app.Route)\ndest Beta (com.app.Route)\n"
    val (augmented, diff) = diffAgainstBaseline(HGraph(nodes = listOf(home)), baseline)
    assertEquals(3, augmented.nodes.size)
    assertEquals(2, diff.count(DiffStatus.REMOVED))
  }

  @Test
  fun inferredTransitionsParticipateWhenTheBaselineRecordsThem() {
    val before = HGraph(nodes = listOf(home, profile))
    val after = before.copy(
      edges = listOf(HEdge(from = "x.Home", to = "x.Profile", confidence = INFERRED)),
    )
    val (_, diff) = diffAgainstBaseline(
      after,
      renderBaseline(before, includeInferred = true),
      includeInferred = true,
    )
    assertEquals(DiffStatus.ADDED, diff.edge(0))
  }

  @Test
  fun parallelTransitionsAreJudgedIndependently() {
    val before = HGraph(
      nodes = listOf(home, profile),
      edges = listOf(HEdge(from = "x.Home", to = "x.Profile", label = "a")),
    )
    val after = before.copy(
      edges = listOf(
        HEdge(from = "x.Home", to = "x.Profile", label = "a"),
        HEdge(from = "x.Home", to = "x.Profile", label = "b"),
      ),
    )
    val (_, diff) = diffAgainstBaseline(after, baselineOf(before))
    assertEquals(DiffStatus.UNCHANGED, diff.edge(0))
    assertEquals(DiffStatus.ADDED, diff.edge(1))
  }

  @Test
  fun sameSimpleNameDestinationsStayDistinct() {
    val a = HNode(id = "a.Foo", route = "Foo")
    val b = HNode(id = "b.Foo", route = "Foo")
    val before = HGraph(nodes = listOf(a, b))
    val after = HGraph(nodes = listOf(a, b, home))
    val (_, diff) = diffAgainstBaseline(after, baselineOf(before))
    assertEquals(DiffStatus.UNCHANGED, diff.node("a.Foo"))
    assertEquals(DiffStatus.UNCHANGED, diff.node("b.Foo"))
    assertEquals(DiffStatus.ADDED, diff.node("x.Home"))
  }

  // --- baseline parsing ---------------------------------------------------------------------------

  @Test
  fun readsBackEveryFieldRenderBaselineWrites() {
    val graph = HGraph(
      nodes = listOf(
        home,
        HNode(
          id = "x.Search",
          route = "Search",
          args = listOf(
            HArg(
              name = "tags",
              type = "kotlin.collections.List",
              typeArguments = listOf("kotlin.String"),
            ),
            HArg(name = "q", type = "kotlin.String", nullable = true, optional = true),
          ),
        ),
      ),
      edges = listOf(HEdge(from = "x.Home", to = "x.Search", label = "say \"hi\"")),
    )
    val snapshot = parseBaseline(renderBaseline(graph))
    assertEquals(setOf("Home", "Search"), snapshot.dests.keys)
    assertTrue("start" in snapshot.dests.getValue("Home"))
    assertEquals(
      BaselineEdge("Home", "Search", "say \"hi\"", "edge Home -> Search  \"say \\\"hi\\\"\""),
      snapshot.edges.single(),
    )
  }

  @Test
  fun aLabelWithADoubleSpaceSurvivesTheRoundTrip() {
    // Optional fields are appended after a double space, so the parser peels from the right instead of splitting.
    val graph = HGraph(
      nodes = listOf(home, profile),
      edges = listOf(HEdge(from = "x.Home", to = "x.Profile", label = "go  now")),
    )
    val snapshot = parseBaseline(renderBaseline(graph))
    assertEquals("go  now", snapshot.edges.single().label)
    assertEquals("Home" to "Profile", snapshot.edges.single().let { it.from to it.to })
  }

  @Test
  fun readsBackAnInferredMarker() {
    val graph = HGraph(
      nodes = listOf(home, profile),
      edges = listOf(HEdge(from = "x.Home", to = "x.Profile", confidence = INFERRED)),
    )
    val snapshot = parseBaseline(renderBaseline(graph, includeInferred = true))
    assertEquals("Home" to "Profile", snapshot.edges.single().let { it.from to it.to })
  }

  @Test
  fun recoversGenericArgumentsWithoutSplittingThem() {
    val graph = HGraph(
      nodes = listOf(
        HNode(
          id = "x.A",
          route = "A",
          args = listOf(
            HArg(
              name = "index",
              type = "kotlin.collections.Map",
              typeArguments = listOf("kotlin.String", "kotlin.Int"),
            ),
            HArg(name = "q", type = "kotlin.String"),
          ),
        ),
      ),
    )
    val after = HGraph(nodes = listOf(home))
    val (augmented, _) = diffAgainstBaseline(after, renderBaseline(graph))
    val ghost = augmented.nodes.single { it.route == "A" }
    assertEquals(
      listOf("index" to "Map<String, Int>", "q" to "String"),
      ghost.args.map { it.name to displayType(it) },
    )
  }

  @Test
  fun ignoresCommentsAndBlankLines() {
    val snapshot = parseBaseline("# a header\n\ndest Home  start\nedge Home -> Feed\n")
    assertEquals(setOf("Home"), snapshot.dests.keys)
    assertEquals(listOf("Home" to "Feed"), snapshot.edges.map { it.from to it.to })
  }

  @Test
  fun anEmptyBaselineMakesEverythingAdded() {
    val graph = HGraph(nodes = listOf(home, profile))
    val (_, diff) = diffAgainstBaseline(graph, "")
    assertEquals(2, diff.count(DiffStatus.ADDED))
    assertEquals(0, diff.count(DiffStatus.REMOVED))
  }
}
