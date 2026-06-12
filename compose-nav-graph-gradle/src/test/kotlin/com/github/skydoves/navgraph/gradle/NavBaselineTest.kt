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

/** Pins the `.nav` baseline renderer — the determinism, escaping, and collision cases the sample can't exercise. */
class NavBaselineTest {

  @Test
  fun rendersSortedFlatBaseline() {
    val graph = HGraph(
      nodes = listOf(
        HNode(
          id = "x.Profile",
          route = "Profile",
          args = listOf(HArg(name = "userId", type = "kotlin.String")),
        ),
        HNode(id = "x.Home", route = "Home", start = true),
      ),
      edges = listOf(HEdge(from = "x.Home", to = "x.Profile", label = "go")),
    )
    // Sorted by route regardless of input order; start flag + args + quoted label rendered.
    assertEquals(
      listOf(
        "dest Home  start",
        "dest Profile  args=(userId: String)",
        "edge Home -> Profile  \"go\"",
      ),
      baselineContent(renderBaseline(graph)),
    )
  }

  @Test
  fun rendersArgFlagsAndGenerics() {
    val graph = HGraph(
      nodes = listOf(
        HNode(
          id = "x.A",
          route = "A",
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
    )
    assertEquals(
      listOf("dest A  args=(tags: List<String>, q: String? = …)"),
      baselineContent(renderBaseline(graph)),
    )
  }

  @Test
  fun disambiguatesSameSimpleNameByFqn() {
    val graph = HGraph(
      nodes = listOf(HNode(id = "a.Foo", route = "Foo"), HNode(id = "b.Foo", route = "Foo")),
      edges = listOf(HEdge(from = "a.Foo", to = "b.Foo")),
    )
    val out = baselineContent(renderBaseline(graph))
    assertTrue("dest Foo (a.Foo)" in out)
    assertTrue("dest Foo (b.Foo)" in out)
    assertTrue("edge Foo (a.Foo) -> Foo (b.Foo)" in out)
  }

  @Test
  fun escapesQuotesAndNewlinesInLabels() {
    val graph = HGraph(
      nodes = listOf(HNode(id = "x.A", route = "A"), HNode(id = "x.B", route = "B")),
      edges = listOf(HEdge(from = "x.A", to = "x.B", label = "say \"hi\"\nbye")),
    )
    val out = baselineContent(renderBaseline(graph))
    // One physical line, quotes + newline escaped → no format corruption, no false-negative collision.
    assertEquals(listOf("dest A", "dest B", "edge A -> B  \"say \\\"hi\\\"\\nbye\""), out)
  }

  @Test
  fun baselineContentStripsCommentsAndBlanks() {
    assertEquals(
      listOf("dest Home  start"),
      baselineContent("# header line\n\ndest Home  start\n\n"),
    )
  }

  @Test
  fun emptyGraphRendersOnlyHeader() {
    assertEquals(emptyList<String>(), baselineContent(renderBaseline(HGraph())))
  }
}
