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
package com.github.skydoves.navgraph.ksp

import com.github.skydoves.navgraph.model.EdgeConfidence
import com.tschuchort.compiletesting.SourceFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavGraphProcessorTest {

  @Test
  fun navDestination_yieldsNodeWithFqnIdSimpleRouteAndClickTarget() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Routes.kt",
        """
        package com.app
        import androidx.navigation3.runtime.NavKey
        import com.github.skydoves.navgraph.annotations.NavDestination
        class Profile : NavKey
        @NavDestination(route = Profile::class)
        fun ProfileScreen() {}
        """.trimIndent(),
      ),
    )

    val node = graph.node("Profile")
    assertEquals("com.app.Profile", node.id)
    assertEquals("Profile", node.route)
    assertEquals("com.app.ProfileScreen", node.clickTargetFqn)
    assertFalse(node.start)
  }

  @Test
  fun navKeyWithoutNavDestination_stillYieldsNodeWithNullClickTarget() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Routes.kt",
        """
        package com.app
        import androidx.navigation3.runtime.NavKey
        class Lonely : NavKey
        """.trimIndent(),
      ),
    )

    val node = graph.node("Lonely")
    assertEquals("com.app.Lonely", node.id)
    assertNull(node.clickTargetFqn)
    assertNull(node.sourceFile)
    assertTrue(node.args.isEmpty())
  }

  @Test
  fun annotatedOnly_seedsNodesFromAnnotationsAndEdgesOnly_skippingUnreferencedNavKeys() {
    val source = SourceFile.kotlin(
      "Routes.kt",
      """
      package com.app
      import androidx.navigation3.runtime.NavKey
      import com.github.skydoves.navgraph.annotations.NavDestination
      import com.github.skydoves.navgraph.annotations.NavEdge
      class A : NavKey
      class B : NavKey
      data object C : NavKey
      @NavEdge(to = B::class)
      @NavDestination(route = A::class)
      fun AScreen() {}
      @NavDestination(route = B::class)
      fun BScreen() {}
      """.trimIndent(),
    )

    val graph = compileNavGraph(source, options = mapOf("navgraph.annotatedOnly" to "true"))

    assertTrue("annotated route A is a node", graph.nodes.any { it.id == "com.app.A" })
    assertTrue("edge endpoint B is a node", graph.nodes.any { it.id == "com.app.B" })
    assertFalse("unreferenced NavKey C is skipped", graph.nodes.any { it.id == "com.app.C" })
    assertEquals(setOf("com.app.A", "com.app.B"), graph.nodes.map { it.id }.toSet())

    val edge = graph.edges.single()
    assertEquals("com.app.A", edge.from)
    assertEquals("com.app.B", edge.to)
  }

  @Test
  fun withoutAnnotatedOnly_unreferencedNavKeyStillBecomesANode() {
    val source = SourceFile.kotlin(
      "Routes.kt",
      """
      package com.app
      import androidx.navigation3.runtime.NavKey
      import com.github.skydoves.navgraph.annotations.NavDestination
      import com.github.skydoves.navgraph.annotations.NavEdge
      class A : NavKey
      class B : NavKey
      data object C : NavKey
      @NavEdge(to = B::class)
      @NavDestination(route = A::class)
      fun AScreen() {}
      @NavDestination(route = B::class)
      fun BScreen() {}
      """.trimIndent(),
    )

    val graph = compileNavGraph(source)

    assertTrue(
      "unreferenced NavKey C is a node by default",
      graph.nodes.any {
        it.id == "com.app.C"
      },
    )
  }

  @Test
  fun navEdge_resolvesTargetFqnAndLabelWithAnnotatedConfidence() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Routes.kt",
        """
        package com.app
        import androidx.navigation3.runtime.NavKey
        import com.github.skydoves.navgraph.annotations.NavEdge
        class Feed : NavKey
        class Profile : NavKey
        @NavEdge(to = Profile::class, label = "open profile")
        class FeedKey : NavKey
        """.trimIndent(),
      ),
    )

    val edge = graph.edges.single()
    assertEquals("com.app.FeedKey", edge.from)
    assertEquals("com.app.Profile", edge.to)
    assertEquals("open profile", edge.label)
    assertEquals(EdgeConfidence.ANNOTATED, edge.confidence)
  }

  @Test
  fun navEdge_onComposableUsesNavDestinationRouteAsSource() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Routes.kt",
        """
        package com.app
        import androidx.navigation3.runtime.NavKey
        import com.github.skydoves.navgraph.annotations.NavDestination
        import com.github.skydoves.navgraph.annotations.NavEdge
        class Feed : NavKey
        class Profile : NavKey
        @NavEdge(to = Profile::class)
        @NavDestination(route = Feed::class)
        fun FeedScreen() {}
        """.trimIndent(),
      ),
    )

    val edge = graph.edges.single()
    assertEquals("com.app.Feed", edge.from)
    assertEquals("com.app.Profile", edge.to)
    assertNull(edge.label)
  }

  @Test
  fun repeatableNavEdge_yieldsMultipleEdgesSortedByFromThenTo() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Routes.kt",
        """
        package com.app
        import androidx.navigation3.runtime.NavKey
        import com.github.skydoves.navgraph.annotations.NavEdge
        class Settings : NavKey
        class Profile : NavKey
        @NavEdge(to = Profile::class)
        @NavEdge(to = Settings::class, label = "menu")
        class Feed : NavKey
        """.trimIndent(),
      ),
    )

    assertEquals(2, graph.edges.size)
    assertEquals(listOf("com.app.Profile", "com.app.Settings"), graph.edges.map { it.to })
    assertEquals("com.app.Feed", graph.edges[0].from)
    assertEquals("com.app.Feed", graph.edges[1].from)
    assertEquals("menu", graph.edges.single { it.to == "com.app.Settings" }.label)
  }

  @Test
  fun navEdge_toNonNavKeyClass_keepsEdgeAndMakesTargetANodeWithoutArgs() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Routes.kt",
        """
        package com.app
        import androidx.navigation3.runtime.NavKey
        import com.github.skydoves.navgraph.annotations.NavEdge
        class DetailActivity(val ignored: String)
        @NavEdge(to = DetailActivity::class)
        class Feed : NavKey
        """.trimIndent(),
      ),
    )

    val edge = graph.edges.single()
    assertEquals("com.app.Feed", edge.from)
    assertEquals("com.app.DetailActivity", edge.to)

    val target = graph.nodeById("com.app.DetailActivity")
    assertNull(target.clickTargetFqn)
    assertTrue("non-NavKey node carries no args", target.args.isEmpty())
  }

  @Test
  fun navEdge_toUnresolvableRoute_isDropped() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Routes.kt",
        """
        package com.app
        import androidx.navigation3.runtime.NavKey
        import com.github.skydoves.navgraph.annotations.NavEdge
        import com.github.skydoves.navgraph.annotations.NavDestination
        class Feed : NavKey
        class Phantom : NavKey
        @NavEdge(to = Phantom::class, from = Feed::class)
        @NavDestination(route = Feed::class)
        fun FeedScreen() {}
        """.trimIndent(),
      ),
    )

    assertTrue(graph.nodes.any { it.id == "com.app.Feed" })
    assertTrue(graph.nodes.any { it.id == "com.app.Phantom" })
    assertEquals(1, graph.edges.size)
    assertEquals("com.app.Phantom", graph.edges.single().to)
  }

  @Test
  fun navGraphRoot_setsStartFlagOnItsNodeOnly() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Routes.kt",
        """
        package com.app
        import androidx.navigation3.runtime.NavKey
        import com.github.skydoves.navgraph.annotations.NavGraphRoot
        @NavGraphRoot
        class Home : NavKey
        class Other : NavKey
        """.trimIndent(),
      ),
    )

    assertTrue(graph.node("Home").start)
    assertFalse(graph.node("Other").start)
  }

  @Test
  fun navGraphRoot_onComposableMarksNavDestinationRoute() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Routes.kt",
        """
        package com.app
        import androidx.navigation3.runtime.NavKey
        import com.github.skydoves.navgraph.annotations.NavDestination
        import com.github.skydoves.navgraph.annotations.NavGraphRoot
        class Home : NavKey
        @NavGraphRoot
        @NavDestination(route = Home::class)
        fun HomeScreen() {}
        """.trimIndent(),
      ),
    )

    assertTrue(graph.node("Home").start)
  }

  @Test
  fun navPreview_linksPreviewToRouteWithPrimaryAndFacadeMethodFqn() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Previews.kt",
        """
        package com.app
        import androidx.navigation3.runtime.NavKey
        import com.github.skydoves.navgraph.annotations.NavPreview
        class Profile : NavKey
        @NavPreview(route = Profile::class, primary = true)
        fun ProfilePreview() {}
        """.trimIndent(),
      ),
    )

    val preview = graph.node("Profile").previews.single()
    assertEquals("ProfilePreview", preview.previewName)
    assertEquals("com.app.ProfilePreview", preview.previewFqn)
    assertEquals("com.app.PreviewsKt.ProfilePreview", preview.previewMethodFqn)
    assertTrue(preview.primary)
    assertEquals("a preview without @Preview(locale) carries none", null, preview.locale)
  }

  @Test
  fun navPreview_carriesDirectPreviewLocale() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Previews.kt",
        """
        package com.app
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.navigation3.runtime.NavKey
        import com.github.skydoves.navgraph.annotations.NavPreview
        class Profile : NavKey
        @Preview(locale = "ko")
        @NavPreview(route = Profile::class, primary = true)
        fun ProfilePreview() {}
        """.trimIndent(),
      ),
      PREVIEW_STUB,
    )

    assertEquals("ko", graph.node("Profile").previews.single().locale)
  }

  @Test
  fun navPreview_carriesLocaleFromMultipreviewMetaAnnotation() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Previews.kt",
        """
        package com.app
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.navigation3.runtime.NavKey
        import com.github.skydoves.navgraph.annotations.NavPreview

        @Preview(name = "Light", locale = "fr-rFR")
        @Preview(name = "Dark", locale = "fr-rFR")
        annotation class DevicePreview

        class Profile : NavKey
        @DevicePreview
        @NavPreview(route = Profile::class, primary = true)
        fun ProfilePreview() {}
        """.trimIndent(),
      ),
      PREVIEW_STUB,
    )

    assertEquals("fr-rFR", graph.node("Profile").previews.single().locale)
  }

  @Test
  fun navPreview_multiplePreviewsSortPrimaryFirst() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Previews.kt",
        """
        package com.app
        import androidx.navigation3.runtime.NavKey
        import com.github.skydoves.navgraph.annotations.NavPreview
        class Profile : NavKey
        @NavPreview(route = Profile::class)
        fun ProfileLight() {}
        @NavPreview(route = Profile::class, primary = true)
        fun ProfileDark() {}
        """.trimIndent(),
      ),
    )

    val previews = graph.node("Profile").previews
    assertEquals(2, previews.size)
    assertEquals("ProfileDark", previews.first().previewName)
    assertTrue(previews.first().primary)
  }

  @Test
  fun args_extractsConstructorPropsInOrderWithNullabilityAndOptionality() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Routes.kt",
        """
        package com.app
        import androidx.navigation3.runtime.NavKey
        class Detail(
          val id: Int,
          val title: String?,
          val page: Int = 0,
        ) : NavKey
        """.trimIndent(),
      ),
    )

    val args = graph.node("Detail").args
    assertEquals(listOf("id", "title", "page"), args.map { it.name })

    val id = args[0]
    assertEquals("kotlin.Int", id.type)
    assertFalse(id.nullable)
    assertFalse(id.optional)

    val title = args[1]
    assertEquals("kotlin.String", title.type)
    assertTrue(title.nullable)
    assertFalse(title.optional)

    val page = args[2]
    assertTrue(page.optional)
  }

  @Test
  fun args_bodyBackingFieldPropsAppendedAsOptional() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Routes.kt",
        """
        package com.app
        import androidx.navigation3.runtime.NavKey
        class Detail(val id: Int) : NavKey {
          var draft: String = ""
        }
        """.trimIndent(),
      ),
    )

    val args = graph.node("Detail").args
    assertEquals(listOf("id", "draft"), args.map { it.name })
    assertFalse(args[0].optional)
    assertTrue(args[1].optional)
  }

  @Test
  fun args_enumPropertyIsFlaggedEnum() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Routes.kt",
        """
        package com.app
        import androidx.navigation3.runtime.NavKey
        enum class Section { A, B }
        class Detail(val section: Section) : NavKey
        """.trimIndent(),
      ),
    )

    val arg = graph.node("Detail").args.single()
    assertEquals("com.app.Section", arg.type)
    assertTrue(arg.enum)
  }

  @Test
  fun args_genericTypeRecordsRawTypeAndTypeArguments() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Routes.kt",
        """
        package com.app
        import androidx.navigation3.runtime.NavKey
        class Tag
        class Detail(val tags: List<Tag>) : NavKey
        """.trimIndent(),
      ),
    )

    val arg = graph.node("Detail").args.single()
    assertEquals("kotlin.collections.List", arg.type)
    assertEquals(listOf("com.app.Tag"), arg.typeArguments)
  }

  @Test
  fun args_transientExcludedAndSerialNameRenames() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Routes.kt",
        """
        package com.app
        import androidx.navigation3.runtime.NavKey
        import kotlinx.serialization.SerialName
        import kotlinx.serialization.Transient
        class Detail(
          @SerialName("identifier") val id: Int,
          @Transient val secret: String = "",
        ) : NavKey
        """.trimIndent(),
      ),
    )

    val args = graph.node("Detail").args
    assertEquals(listOf("identifier"), args.map { it.name })
  }

  @Test
  fun navDestination_recordsSourceFileAndLine() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Screen.kt",
        """
        package com.app
        import androidx.navigation3.runtime.NavKey
        import com.github.skydoves.navgraph.annotations.NavDestination
        class Profile : NavKey
        @NavDestination(route = Profile::class)
        fun ProfileScreen() {}
        """.trimIndent(),
      ),
    )

    val node = graph.node("Profile")
    assertTrue(node.sourceFile!!.endsWith("Screen.kt"))
    assertEquals(6, node.sourceLine)
  }

  @Test
  fun nestedNavKey_resolvesToFullNestedFqnWithNoBareDuplicate() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Routes.kt",
        """
        package com.app
        import androidx.navigation3.runtime.NavKey
        import com.github.skydoves.navgraph.annotations.NavDestination
        sealed interface AboutItemNavKey : NavKey {
          data object ContributorsNavKey : AboutItemNavKey
          data object SettingsNavKey : AboutItemNavKey
        }
        @NavDestination(route = AboutItemNavKey.ContributorsNavKey::class)
        fun ContributorsScreen() {}
        """.trimIndent(),
      ),
    )

    val contributors = graph.nodes.filter { it.route == "ContributorsNavKey" }
    assertEquals(1, contributors.size)
    assertEquals("com.app.AboutItemNavKey.ContributorsNavKey", contributors.single().id)
    assertEquals("com.app.ContributorsScreen", contributors.single().clickTargetFqn)
    assertTrue(graph.nodes.none { it.id == "com.app.ContributorsNavKey" })

    assertEquals(
      listOf(
        "com.app.AboutItemNavKey.ContributorsNavKey",
        "com.app.AboutItemNavKey.SettingsNavKey",
      ),
      graph.nodes.map { it.id }.sorted(),
    )
  }

  @Test
  fun graphMetadata_carriesNavVersionAndSchemaVersion() {
    val graph = compileNavGraph(
      SourceFile.kotlin(
        "Routes.kt",
        """
        package com.app
        import androidx.navigation3.runtime.NavKey
        class Home : NavKey
        """.trimIndent(),
      ),
    )

    assertEquals("navgraph", graph.navVersion)
    assertEquals(1, graph.schemaVersion)
  }
}
