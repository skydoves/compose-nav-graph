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
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the decisions [InferNavEdgesTask] makes once [KotlinNavScanner] has read the call sites. */
class NavEdgeInferenceTest {

  private fun scan(pkg: String = "com.app", imports: Map<String, String> = emptyMap()) =
    FileScan(packageName = pkg, imports = imports, edges = emptyList(), mentions = emptyList())

  // --- resolution -------------------------------------------------------------------------------

  @Test
  fun resolvesAnAlreadyQualifiedReference() {
    assertEquals(
      "com.other.Detail",
      resolveRouteReference("com.other.Detail", scan(), setOf("com.other.Detail")),
    )
  }

  @Test
  fun resolvesThroughTheFilesImports() {
    val fileScan = scan(imports = mapOf("Detail" to "com.other.Detail"))
    assertEquals(
      "com.other.Detail",
      resolveRouteReference("Detail", fileScan, setOf("com.other.Detail")),
    )
  }

  @Test
  fun resolvesWithinTheFilesOwnPackage() {
    assertEquals("com.app.Home", resolveRouteReference("Home", scan(), setOf("com.app.Home")))
  }

  @Test
  fun resolvesAUniqueSimpleNameForStarImports() {
    // No import entry and a different package — the star-import fallback still finds the one candidate.
    assertEquals("com.routes.Feed", resolveRouteReference("Feed", scan(), setOf("com.routes.Feed")))
  }

  @Test
  fun refusesToGuessBetweenSameNamedRoutes() {
    val ambiguous = setOf("com.a.Detail", "com.b.Detail")
    assertNull(resolveRouteReference("Detail", scan(), ambiguous))
  }

  @Test
  fun dropsAReferenceThatIsNotARoute() {
    assertNull(resolveRouteReference("it.categoryId", scan(), setOf("com.app.Home")))
    assertNull(resolveRouteReference("someLocal", scan(), setOf("com.app.Home")))
  }

  @Test
  fun resolvesANestedRouteThroughItsOuterClassImport() {
    // `AppRoute.Detail` is imported by its OUTER name, so a whole-reference lookup finds nothing.
    val fileScan =
      scan(pkg = "com.app.ui", imports = mapOf("AppRoute" to "com.app.routes.AppRoute"))
    assertEquals(
      "com.app.routes.AppRoute.Detail",
      resolveRouteReference("AppRoute.Detail", fileScan, setOf("com.app.routes.AppRoute.Detail")),
    )
  }

  @Test
  fun anOuterClassImportBeatsASameNamedClassInThisPackage() {
    val fileScan = scan(pkg = "com.app", imports = mapOf("AppRoute" to "com.other.AppRoute"))
    val known = setOf("com.other.AppRoute.Detail", "com.app.AppRoute.Detail")
    assertEquals(
      "com.other.AppRoute.Detail",
      resolveRouteReference("AppRoute.Detail", fileScan, known),
    )
  }

  @Test
  fun importWinsOverAnUnrelatedSamePackageClass() {
    val fileScan = scan(imports = mapOf("Detail" to "com.other.Detail"))
    val known = setOf("com.other.Detail", "com.app.Detail")
    assertEquals("com.other.Detail", resolveRouteReference("Detail", fileScan, known))
  }

  // --- reconciliation ---------------------------------------------------------------------------

  @Test
  fun anExplicitAnnotationWinsOverAnInferredDuplicate() {
    val result = reconcileInferredEdges(
      scanned = setOf("A" to "B", "A" to "C"),
      annotated = setOf("A" to "B"),
    )
    assertEquals(listOf("A" to "C"), result.fresh)
    assertEquals(1, result.alreadyAnnotated)
  }

  @Test
  fun freshEdgesAreSortedForADeterministicManifest() {
    val result = reconcileInferredEdges(setOf("B" to "Z", "A" to "Y", "A" to "X"), emptySet())
    assertEquals(listOf("A" to "X", "A" to "Y", "B" to "Z"), result.fresh)
  }

  @Test
  fun reportsAnAnnotationWhoseCallSiteIsGone() {
    // A navigates in this module (A → B was found), so the unmatched A → C annotation is genuine drift.
    val result = reconcileInferredEdges(scanned = setOf("A" to "B"), annotated = setOf("A" to "C"))
    assertEquals(listOf("A" to "C"), result.stale)
  }

  @Test
  fun neverReportsDriftForANodeThisModuleDoesNotWireUp() {
    // The classic multi-module shape: :feature declares @NavEdge, :app holds the entry<T> { } blocks. Nothing was
    // scanned for D here, so its annotations must not be called stale.
    val result = reconcileInferredEdges(scanned = setOf("A" to "B"), annotated = setOf("D" to "E"))
    assertEquals(emptyList<EdgeKey>(), result.stale)
  }

  @Test
  fun neverReportsDriftWhenTheTargetIsNamedInTheSourcesBlock() {
    // The sample's `Profile → ProfileDetailActivity`: navigation happens via
    // `startActivity(Intent(context, ProfileDetailActivity::class.java))`, which is not a nav call — but the target
    // IS named inside `entry<Profile> { }`, so the annotation is corroborated rather than blamed.
    val result = reconcileInferredEdges(
      scanned = setOf("Profile" to "Article"),
      annotated = setOf("Profile" to "Article", "Profile" to "ProfileDetailActivity"),
      corroborated = setOf("Profile" to "ProfileDetailActivity"),
    )
    assertEquals(emptyList<EdgeKey>(), result.stale)
  }

  @Test
  fun corroborationDoesNotCreateEdges() {
    // A merely-named target is never promoted to a transition; only a real call site is.
    val result = reconcileInferredEdges(
      scanned = setOf("A" to "B"),
      annotated = emptySet(),
      corroborated = setOf("A" to "Z"),
    )
    assertEquals(listOf("A" to "B"), result.fresh)
  }

  @Test
  fun nothingScannedMeansNothingInferredAndNothingBlamed() {
    val result = reconcileInferredEdges(emptySet(), setOf("A" to "B", "C" to "D"))
    assertEquals(emptyList<EdgeKey>(), result.fresh)
    assertEquals(emptyList<EdgeKey>(), result.stale)
    assertEquals(0, result.alreadyAnnotated)
  }
}
