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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavManifestParseTest {

  @Test
  fun parsesNodesEdgesArgsAndStart() {
    val json = """
      {
        "navVersion": "navgraph",
        "schemaVersion": 2,
        "nodes": [
          {
            "id": "com.app.HomeScreen",
            "route": "Home",
            "module": ":app",
            "clickTargetFqn": "com.app.HomeScreen",
            "sourceFile": "Home.kt",
            "sourceLine": 12,
            "start": true,
            "args": [
              {
                "name": "userId",
                "type": "kotlin.String",
                "nullable": false,
                "optional": false,
                "enum": false
              }
            ]
          },
          {
            "id": "com.app.DetailScreen",
            "route": "Detail",
            "args": [
              {
                "name": "tags",
                "type": "kotlin.collections.List",
                "typeArguments": ["kotlin.String"],
                "nullable": true,
                "optional": true
              }
            ]
          }
        ],
        "edges": [
          { "from": "com.app.HomeScreen", "to": "com.app.DetailScreen", "label": "open" }
        ]
      }
    """.trimIndent()

    val graph = parseGraph(json)

    assertEquals("navgraph", graph.navVersion)
    assertEquals(2, graph.schemaVersion)
    assertEquals(2, graph.nodes.size)
    assertEquals(1, graph.edges.size)

    val home = graph.nodes.first { it.id == "com.app.HomeScreen" }
    assertEquals("Home", home.route)
    assertEquals(":app", home.module)
    assertEquals("com.app.HomeScreen", home.clickTargetFqn)
    assertEquals("Home.kt", home.sourceFile)
    assertEquals(12, home.sourceLine)
    assertTrue(home.start)
    assertEquals(1, home.args.size)
    assertEquals("userId", home.args[0].name)
    assertEquals("kotlin.String", home.args[0].type)
    assertTrue(home.args[0].typeArguments.isEmpty())
    assertFalse(home.args[0].nullable)
    assertFalse(home.args[0].optional)

    val detail = graph.nodes.first { it.id == "com.app.DetailScreen" }
    assertFalse(detail.start)
    assertNull(detail.module)
    assertNull(detail.clickTargetFqn)
    assertNull(detail.sourceLine)
    assertEquals(listOf("kotlin.String"), detail.args[0].typeArguments)
    assertTrue(detail.args[0].nullable)
    assertTrue(detail.args[0].optional)

    val edge = graph.edges[0]
    assertEquals("com.app.HomeScreen", edge.from)
    assertEquals("com.app.DetailScreen", edge.to)
    assertEquals("open", edge.label)
  }

  @Test
  fun parsesPreviewsWithParameters() {
    val json = """
      {
        "nodes": [
          {
            "id": "x.A",
            "route": "A",
            "previews": [
              {
                "previewName": "Light",
                "previewFqn": "x.APreview",
                "previewMethodFqn": "x.APreview.light",
                "thumbnail": "thumbs/a.png",
                "primary": true,
                "previewParameters": [
                  { "name": "user", "provider": "x.UserProvider" }
                ]
              }
            ]
          }
        ]
      }
    """.trimIndent()

    val node = parseGraph(json).nodes.single()
    assertEquals(1, node.previews.size)
    val preview = node.previews[0]
    assertEquals("Light", preview.previewName)
    assertEquals("x.APreview", preview.previewFqn)
    assertEquals("x.APreview.light", preview.previewMethodFqn)
    assertEquals("thumbs/a.png", preview.thumbnail)
    assertTrue(preview.primary)
    assertEquals(1, preview.previewParameters.size)
    assertEquals("user", preview.previewParameters[0].name)
    assertEquals("x.UserProvider", preview.previewParameters[0].provider)
  }

  @Test
  fun appliesDefaultsForAbsentFields() {
    val graph = parseGraph("""{ "nodes": [ { "id": "x.A" } ] }""")
    assertEquals("navgraph", graph.navVersion)
    assertEquals(1, graph.schemaVersion)
    assertTrue(graph.edges.isEmpty())
    val node = graph.nodes.single()
    assertEquals("", node.route)
    assertFalse(node.start)
    assertTrue(node.args.isEmpty())
    assertTrue(node.previews.isEmpty())
  }

  @Test
  fun parsesEmptyGraph() {
    val graph = parseGraph("{}")
    assertTrue(graph.nodes.isEmpty())
    assertTrue(graph.edges.isEmpty())
    assertEquals("navgraph", graph.navVersion)
    assertEquals(1, graph.schemaVersion)
  }

  @Test
  fun roundTripsManifestThroughBaseline() {
    val json = """
      {
        "nodes": [
          { "id": "x.Home", "route": "Home", "start": true },
          {
            "id": "x.Profile",
            "route": "Profile",
            "args": [ { "name": "id", "type": "kotlin.Int" } ]
          }
        ],
        "edges": [ { "from": "x.Home", "to": "x.Profile", "label": "go" } ]
      }
    """.trimIndent()
    assertEquals(
      listOf(
        "dest Home  start",
        "dest Profile  args=(id: Int)",
        "edge Home -> Profile  \"go\"",
      ),
      baselineContent(renderBaseline(parseGraph(json))),
    )
  }

  @Test
  fun displayTypeStripsPackageFromSimpleType() {
    assertEquals("String", displayType(HArg(type = "kotlin.String")))
  }

  @Test
  fun displayTypeAppendsQuestionMarkWhenNullable() {
    assertEquals("String?", displayType(HArg(type = "kotlin.String", nullable = true)))
  }

  @Test
  fun displayTypeRendersGenericsWithSimpleNames() {
    assertEquals(
      "List<String>",
      displayType(HArg(type = "kotlin.collections.List", typeArguments = listOf("kotlin.String"))),
    )
    assertEquals(
      "Map<String, Int>",
      displayType(
        HArg(
          type = "kotlin.collections.Map",
          typeArguments = listOf("kotlin.String", "kotlin.Int"),
        ),
      ),
    )
  }

  @Test
  fun displayTypeCombinesGenericsAndNullable() {
    assertEquals(
      "List<User>?",
      displayType(
        HArg(
          type = "kotlin.collections.List",
          typeArguments = listOf("com.app.User"),
          nullable = true,
        ),
      ),
    )
  }

  @Test
  fun displayTypeIgnoresOptionalAndEnumFlags() {
    assertEquals(
      "Direction",
      displayType(HArg(type = "com.app.Direction", optional = true, enum = true)),
    )
  }

  @Test
  fun displayTypeKeepsBareTypeWithoutPackage() {
    assertEquals("Int", displayType(HArg(type = "Int")))
  }
}
