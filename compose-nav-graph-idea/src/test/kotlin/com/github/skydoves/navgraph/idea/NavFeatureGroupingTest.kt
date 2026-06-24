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
package com.github.skydoves.navgraph.idea

import com.github.skydoves.navgraph.idea.model.NavEdgeDto
import com.github.skydoves.navgraph.idea.model.NavGraphDto
import com.github.skydoves.navgraph.idea.model.NavNodeDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [NavFeatureGrouping] — the within-module "view one feature at a time" feature (#19). The
 * shape under test is the reporter's: every screen in one `:app` module, organized by `feature.<name>` packages.
 */
class NavFeatureGroupingTest {

  private fun nodes(vararg fqns: String): NavGraphDto =
    NavGraphDto(nodes = fqns.map { NavNodeDto(id = it, clickTargetFqn = it) })

  private fun featuresOf(graph: NavGraphDto) =
    NavFeatureGrouping.computeFeatures(graph).map { it.name to it.packagePrefix }

  @Test
  fun discoversFeaturesByPackageAfterCommonRoot() {
    val graph = nodes(
      "com.app.feature.feed.FeedScreen",
      "com.app.feature.profile.ProfileScreen",
      "com.app.feature.settings.SettingsScreen",
    )
    assertEquals(
      listOf(
        "feed" to "com.app.feature.feed",
        "profile" to "com.app.feature.profile",
        "settings" to "com.app.feature.settings",
      ),
      featuresOf(graph),
    )
  }

  @Test
  fun nestedSubpackageFoldsIntoItsFeature() {
    val graph = nodes(
      "com.app.feed.FeedScreen",
      "com.app.feed.detail.DetailScreen",
      "com.app.profile.ProfileScreen",
    )
    // `feed.detail` is part of `feed`, not its own feature.
    assertEquals(
      listOf("feed" to "com.app.feed", "profile" to "com.app.profile"),
      featuresOf(graph),
    )
  }

  @Test
  fun featureKeysRespectSegmentBoundaries() {
    val graph = nodes("com.app.feed.FeedScreen", "com.app.feedback.FeedbackScreen")
    // `feed` and `feedback` are siblings, not one nested in the other.
    assertEquals(
      listOf("feed" to "com.app.feed", "feedback" to "com.app.feedback"),
      featuresOf(graph),
    )
  }

  @Test
  fun derivesFeatureFromScreenFqnNotRouteId() {
    val graph = NavGraphDto(
      nodes = listOf(
        NavNodeDto(id = "com.app.routes.FeedRoute", clickTargetFqn = "com.app.feed.FeedScreen"),
        NavNodeDto(
          id = "com.app.routes.ProfileRoute",
          clickTargetFqn = "com.app.profile.ProfileScreen",
        ),
      ),
    )
    assertEquals(
      listOf("feed" to "com.app.feed", "profile" to "com.app.profile"),
      featuresOf(graph),
    )
  }

  @Test
  fun fallsBackToRouteIdWhenScreenFqnNullOrBlank() {
    // clickTargetFqn null (no screen) and explicitly blank both fall back to the route id.
    val graph = NavGraphDto(
      nodes = listOf(
        NavNodeDto(id = "com.app.feed.FeedRoute", clickTargetFqn = null),
        NavNodeDto(id = "com.app.profile.ProfileRoute", clickTargetFqn = ""),
      ),
    )
    assertEquals(
      listOf("feed" to "com.app.feed", "profile" to "com.app.profile"),
      featuresOf(graph),
    )
  }

  @Test
  fun disjointTopLevelPackagesGroupByFirstSegment() {
    // No shared root at all — the first package segment is the only sensible feature key.
    val graph = nodes("com.app.FeedScreen", "org.lib.ProfileScreen")
    assertEquals(listOf("com" to "com", "org" to "org"), featuresOf(graph))
  }

  @Test
  fun singleFeatureProducesNoSelector() {
    val graph = nodes("com.app.feed.FeedScreen", "com.app.feed.detail.DetailScreen")
    assertTrue(NavFeatureGrouping.computeFeatures(graph).isEmpty())
  }

  @Test
  fun filterKeepsFeatureNodesAndDropsCrossFeatureEdges() {
    val graph = NavGraphDto(
      nodes = listOf(
        NavNodeDto(id = "com.app.feed.FeedScreen", clickTargetFqn = "com.app.feed.FeedScreen"),
        NavNodeDto(
          id = "com.app.feed.detail.DetailScreen",
          clickTargetFqn = "com.app.feed.detail.DetailScreen",
        ),
        NavNodeDto(
          id = "com.app.profile.ProfileScreen",
          clickTargetFqn = "com.app.profile.ProfileScreen",
        ),
      ),
      edges = listOf(
        NavEdgeDto(from = "com.app.feed.FeedScreen", to = "com.app.feed.detail.DetailScreen"),
        NavEdgeDto(from = "com.app.feed.FeedScreen", to = "com.app.profile.ProfileScreen"),
      ),
    )
    val feed = NavFeatureGrouping.filterByPackage(graph, "com.app.feed")
    assertEquals(
      listOf("com.app.feed.FeedScreen", "com.app.feed.detail.DetailScreen"),
      feed.nodes.map { it.id },
    )
    assertEquals(1, feed.edges.size)
    assertEquals("com.app.feed.detail.DetailScreen", feed.edges.single().to)
  }

  @Test
  fun filterMatchesViaScreenFqnAndIgnoresPartialSegments() {
    val graph = NavGraphDto(
      nodes = listOf(
        NavNodeDto(id = "com.app.routes.SearchRoute", clickTargetFqn = "com.app.feed.SearchScreen"),
        NavNodeDto(
          id = "com.app.feedback.FeedbackScreen",
          clickTargetFqn = "com.app.feedback.FeedbackScreen",
        ),
      ),
    )
    // Kept via clickTargetFqn; `feedback` is NOT under `feed`.
    assertEquals(
      listOf("com.app.routes.SearchRoute"),
      NavFeatureGrouping.filterByPackage(graph, "com.app.feed").nodes.map { it.id },
    )
  }

  @Test
  fun filterWithBlankPrefixReturnsGraphUnchanged() {
    val graph = nodes("com.app.A", "com.app.B")
    assertEquals(graph, NavFeatureGrouping.filterByPackage(graph, ""))
  }
}
