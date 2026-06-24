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

import com.github.skydoves.navgraph.idea.model.NavGraphDto
import com.github.skydoves.navgraph.idea.model.NavNodeDto

/**
 * Splits one graph into **feature groups by package** — the within-module counterpart of [NavScopeGrouping]
 * (which groups by Gradle module). For apps that keep every screen in a single `:app` module and organize them
 * by feature package (`feature.feed`, `feature.profile`, …), this powers the tool window's "Feature:" selector
 * so the user can view/export one feature's subgraph at a time. No IntelliJ types, so it is unit-testable, and
 * the filter mirrors the Gradle `filterGraphByPackage` exactly so the on-screen view and an export agree.
 *
 * **Auto-discovery (no configuration).** Anchor at the longest package prefix every destination shares (the
 * common root, e.g. `com.app.feature`), then bucket by the single segment that follows it (`feed`, `profile`).
 * Screens deeper in a feature (`com.app.feature.feed.detail.DetailScreen`) fold into that feature. When fewer
 * than two buckets emerge there is nothing to choose between, so [computeFeatures] returns empty and the caller
 * hides the selector. A destination sitting directly under the common root (no further segment) has no feature
 * of its own — it only appears under "All features".
 */
internal object NavFeatureGrouping {

  /** @property name the segment shown in the selector; @property packagePrefix what [filterByPackage] keys on. */
  data class Feature(val name: String, val packagePrefix: String)

  /**
   * The feature groups discovered in [graph], sorted by name. Empty when fewer than two distinct features
   * exist (single-feature or flat apps) — the selector is then pointless and stays hidden.
   */
  fun computeFeatures(graph: NavGraphDto): List<Feature> {
    val packages = graph.nodes.mapNotNull {
      it.featureFqn()?.let(::packageOf)
    }.filter { it.isNotBlank() }
    if (packages.size < 2) return emptyList()

    val common = commonPackagePrefix(packages)
    val rootDepth = if (common.isEmpty()) 0 else common.split('.').size
    // The segment right after the shared root is the feature; a package with nothing past the root contributes
    // no feature key (those destinations live only under "All features").
    val keys = LinkedHashSet<String>()
    for (pkg in packages) {
      val segments = pkg.split('.')
      if (segments.size > rootDepth) keys.add(segments[rootDepth])
    }
    if (keys.size < 2) return emptyList()

    return keys.sorted().map { key ->
      Feature(name = key, packagePrefix = if (common.isEmpty()) key else "$common.$key")
    }
  }

  /**
   * Restrict [graph] to the destinations under [prefix] — a node is kept when its route FQN ([NavNodeDto.id])
   * OR its screen FQN ([NavNodeDto.clickTargetFqn]) is the prefix or nested under it, and an edge survives only
   * when both endpoints do. A blank [prefix] returns [graph] unchanged. Byte-for-byte the Gradle export filter.
   */
  fun filterByPackage(graph: NavGraphDto, prefix: String): NavGraphDto {
    if (prefix.isBlank()) return graph
    fun underPackage(fqn: String?): Boolean =
      fqn != null && (fqn == prefix || fqn.startsWith("$prefix."))
    val nodes = graph.nodes.filter { underPackage(it.id) || underPackage(it.clickTargetFqn) }
    val ids = nodes.mapTo(HashSet()) { it.id }
    return graph.copy(nodes = nodes, edges = graph.edges.filter { it.from in ids && it.to in ids })
  }

  /** The screen FQN drives the feature (it is where the code lives); the route id is the fallback when the
   *  screen FQN is absent OR blank (a hand-authored manifest may carry an empty `clickTargetFqn`). */
  private fun NavNodeDto.featureFqn(): String? =
    (clickTargetFqn?.ifBlank { null } ?: id).ifBlank { null }

  /** Package of a fully-qualified name: everything before the final segment (the simple type name). */
  private fun packageOf(fqn: String): String = fqn.substringBeforeLast('.', "")

  /** The longest run of leading package segments shared by every entry (segment-wise, never mid-segment). */
  private fun commonPackagePrefix(packages: List<String>): String {
    val split = packages.map { it.split('.') }
    val first = split.first()
    var depth = 0
    while (depth < first.size && split.all { it.size > depth && it[depth] == first[depth] }) depth++
    return first.take(depth).joinToString(".")
  }
}
