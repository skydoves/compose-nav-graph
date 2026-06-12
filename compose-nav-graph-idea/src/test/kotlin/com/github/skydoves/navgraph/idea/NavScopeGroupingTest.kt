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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [NavScopeGrouping.computeScopes] — the heart of the multi-app fix. Modeled on the two
 * real shapes the user cited: independent apps (`:sample` + `:sample-kmp`) must split; one app spread over
 * feature modules (nowinandroid `:app` + `:feature:*`) must stay merged.
 */
class NavScopeGroupingTest {

  private fun scopesOf(nav: Set<String>, deps: Map<String, Set<String>>) =
    NavScopeGrouping.computeScopes(nav, deps).map {
      it.members
    }.toSet()

  @Test
  fun empty_in_empty_out() {
    assertEquals(
      emptyList<NavScopeGrouping.ModuleScope>(),
      NavScopeGrouping.computeScopes(emptySet(), emptyMap()),
    )
  }

  @Test
  fun single_module_is_one_scope() {
    val scopes = NavScopeGrouping.computeScopes(setOf(":app"), emptyMap())
    assertEquals(1, scopes.size)
    assertEquals(":app", scopes[0].root)
    assertEquals(setOf(":app"), scopes[0].members)
  }

  /** compose-nav-graph's own case: two apps that don't depend on each other → two separate graphs. */
  @Test
  fun independent_apps_stay_separate() {
    val scopes = scopesOf(
      nav = setOf(":sample", ":sample-kmp"),
      deps = emptyMap(),
    )
    assertEquals(setOf(setOf(":sample"), setOf(":sample-kmp")), scopes)
  }

  /** nowinandroid's case: one app depends on every feature module → all merge into the app's single scope. */
  @Test
  fun one_app_over_features_merges() {
    val scopes = NavScopeGrouping.computeScopes(
      navModules = setOf(":app", ":feature:foryou", ":feature:bookmarks", ":feature:interests"),
      directDeps = mapOf(
        ":app" to setOf(":feature:foryou", ":feature:bookmarks", ":feature:interests"),
      ),
    )
    assertEquals(1, scopes.size)
    assertEquals(":app", scopes[0].root)
    assertEquals(
      setOf(":app", ":feature:foryou", ":feature:bookmarks", ":feature:interests"),
      scopes[0].members,
    )
  }

  /**
   * nowinandroid's ACTUAL shape: the `:app` application module owns NO manifest (it doesn't apply navgraph) yet
   * depends on every `:feature:*:impl`; the impls depend only on `:api` modules, never on each other. They must
   * still merge into ONE scope rooted at `:app`. The bug: an app without its own manifest was never a root, so
   * each impl became a separate graph.
   */
  @Test
  fun app_without_manifest_still_unifies_features() {
    val scopes = NavScopeGrouping.computeScopes(
      navModules = setOf(
        ":feature:foryou:impl",
        ":feature:bookmarks:impl",
        ":feature:interests:impl",
        ":feature:search:impl",
        ":feature:topic:impl",
      ),
      directDeps = mapOf(
        ":app" to setOf(
          ":feature:foryou:impl",
          ":feature:bookmarks:impl",
          ":feature:interests:impl",
          ":feature:search:impl",
          ":feature:topic:impl",
        ),
        ":feature:foryou:impl" to setOf(":feature:foryou:api", ":feature:topic:api"),
        ":feature:bookmarks:impl" to setOf(":feature:bookmarks:api", ":feature:topic:api"),
        ":feature:search:impl" to
          setOf(":feature:search:api", ":feature:topic:api", ":feature:interests:api"),
      ),
    )
    assertEquals(1, scopes.size)
    assertEquals(":app", scopes[0].root)
    assertEquals(
      setOf(
        ":feature:foryou:impl",
        ":feature:bookmarks:impl",
        ":feature:interests:impl",
        ":feature:search:impl",
        ":feature:topic:impl",
      ),
      scopes[0].members,
    )
  }

  /** A non-nav aggregator/core module between the app and a feature must still relay the connection. */
  @Test
  fun transitive_through_non_nav_module_still_merges() {
    val scopes = scopesOf(
      nav = setOf(":app", ":feature:topic"), // :core is NOT a nav module
      deps = mapOf(
        ":app" to setOf(":core"),
        ":core" to setOf(":feature:topic"),
      ),
    )
    assertEquals(setOf(setOf(":app", ":feature:topic")), scopes)
  }

  /** A feature shared by two apps appears in BOTH scopes (it genuinely belongs to both). */
  @Test
  fun feature_shared_by_two_apps_appears_in_both() {
    val scopes = scopesOf(
      nav = setOf(":app1", ":app2", ":feature:shared"),
      deps = mapOf(
        ":app1" to setOf(":feature:shared"),
        ":app2" to setOf(":feature:shared"),
      ),
    )
    assertEquals(
      setOf(setOf(":app1", ":feature:shared"), setOf(":app2", ":feature:shared")),
      scopes,
    )
  }

  /** nowinandroid also has a second app (`:app-nia-catalog`) that shares NO feature → fully separate. */
  @Test
  fun second_app_with_no_shared_features_is_separate() {
    val scopes = scopesOf(
      nav = setOf(":app", ":app-catalog", ":feature:foryou"),
      deps = mapOf(
        ":app" to setOf(":feature:foryou"),
        ":app-catalog" to setOf(":core:designsystem"), // depends only on non-nav core
      ),
    )
    assertEquals(setOf(setOf(":app", ":feature:foryou"), setOf(":app-catalog")), scopes)
  }

  /** Pathological dependency cycle among apps → collapse to one scope rather than crash or drop nodes. */
  @Test
  fun dependency_cycle_collapses_to_one_scope() {
    val scopes = NavScopeGrouping.computeScopes(
      navModules = setOf(":a", ":b"),
      directDeps = mapOf(":a" to setOf(":b"), ":b" to setOf(":a")),
    )
    assertEquals(1, scopes.size)
    assertEquals(setOf(":a", ":b"), scopes[0].members)
  }

  /** Deterministic ordering: scopes come back sorted by root id (stable tool-window selector). */
  @Test
  fun scopes_are_ordered_by_root() {
    val scopes = NavScopeGrouping.computeScopes(
      navModules = setOf(":zebra", ":alpha", ":mango"),
      directDeps = emptyMap(),
    )
    assertEquals(listOf(":alpha", ":mango", ":zebra"), scopes.map { it.root })
  }

  /** Every nav module always lands in at least one scope — nothing silently disappears. */
  @Test
  fun no_nav_module_is_ever_dropped() {
    val nav = setOf(":app", ":a", ":b", ":c", ":lonely")
    val scopes = NavScopeGrouping.computeScopes(
      navModules = nav,
      directDeps = mapOf(":app" to setOf(":a", ":b", ":c")),
    )
    val covered = scopes.flatMap { it.members }.toSet()
    assertTrue(nav.all { it in covered })
  }
}
