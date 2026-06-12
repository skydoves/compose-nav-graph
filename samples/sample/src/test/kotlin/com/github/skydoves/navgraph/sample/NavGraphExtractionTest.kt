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
package com.github.skydoves.navgraph.sample

import com.github.skydoves.navgraph.model.NavGraph
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Automated regression gate for `compose-nav-graph-ksp`: parses the manifest the processor emitted for this module
 * (with the real `@Serializable` model) and asserts the hard extraction cases stay correct. Runs as a
 * plain JVM unit test; `testDebugUnitTest` depends on `kspDebugKotlin`, so the manifest exists.
 *
 * (Isolated kctfork processor tests remain a follow-up — see `tasks/todo.md`.)
 */
class NavGraphExtractionTest {

  private val graph: NavGraph by lazy {
    val file = File("build/generated/ksp/debug/resources/nav-graph.json")
    assertTrue("KSP manifest not found at ${file.absolutePath} — run kspDebugKotlin", file.isFile)
    Json.decodeFromString(NavGraph.serializer(), file.readText())
  }

  @Test
  fun extractsConcreteNavKeysAsNodes() {
    val routes = graph.nodes.map { it.route }.toSet()
    // the concrete NavKey destinations are nodes (subset — tolerates extra screens added to the sample)...
    assertTrue(
      routes.containsAll(setOf("Home", "Feed", "Profile", "Settings", "Article", "Orphan")),
    )
    // ...the sealed interface + the Section enum are NOT nodes.
    assertFalse(routes.contains("AppKey"))
    assertFalse(routes.contains("Section"))
  }

  @Test
  fun homeIsTheStartDestination() {
    assertTrue(graph.nodes.single { it.route == "Home" }.start)
    assertFalse(graph.nodes.single { it.route == "Feed" }.start)
  }

  @Test
  fun articleArgsHonorSerializationSemantics() {
    val article = graph.nodes.single { it.route == "Article" }
    // @SerialName("id"), section (enum + default), query (nullable + default), bookmarked (body prop);
    // @Transient analyticsTag is EXCLUDED.
    assertEquals(listOf("id", "section", "query", "bookmarked"), article.args.map { it.name })
    assertFalse(article.args.any { it.name == "analyticsTag" })

    val section = article.args.single { it.name == "section" }
    assertTrue("section should be enum", section.enum)
    assertTrue("section has a default", section.optional)

    val query = article.args.single { it.name == "query" }
    assertTrue("query is nullable", query.nullable)
    assertTrue("query has a default", query.optional)
  }

  @Test
  fun clickTargetsAndPreviewsAreWired() {
    val profile = graph.nodes.single { it.route == "Profile" }
    assertEquals("com.github.skydoves.navgraph.sample.ProfileScreen", profile.clickTargetFqn)
    assertTrue(
      "Profile preview present",
      profile.previews.any {
        it.previewName ==
          "ProfileScreenPreview"
      },
    )

    // Orphan has no @NavDestination → no click target.
    assertNull(graph.nodes.single { it.route == "Orphan" }.clickTargetFqn)

    // Article has two previews; the primary sorts first.
    val article = graph.nodes.single { it.route == "Article" }
    assertEquals(2, article.previews.size)
    assertTrue(article.previews.first().primary)
  }

  @Test
  fun edgesIncludeRepeatableAndExplicitFrom() {
    fun has(from: String, to: String) = graph.edges.any {
      it.from.endsWith(".$from") &&
        it.to.endsWith(".$to")
    }

    // HomeScreen's two @NavEdge (the @Repeatable container) + Profile's two.
    assertTrue(has("Home", "Feed"))
    assertTrue(has("Home", "Settings"))
    assertTrue(has("Profile", "Article"))
    assertTrue(has("Profile", "Settings"))

    // Explicit from= with a label.
    val logout = graph.edges.single { it.from.endsWith(".Settings") && it.to.endsWith(".Home") }
    assertEquals("home", logout.label)
  }
}
