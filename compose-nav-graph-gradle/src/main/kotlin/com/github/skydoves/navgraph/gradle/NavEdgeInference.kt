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

/** A transition as node ids. */
internal typealias EdgeKey = Pair<String, String>

/**
 * What [InferNavEdgesTask] should do with one module's scan — kept free of Gradle types so the decisions it encodes
 * (annotated wins, and when a missing call site is worth reporting) are directly testable.
 *
 * @property fresh transitions to publish as `EdgeConfidence.INFERRED`, sorted for a deterministic manifest.
 * @property stale `@NavEdge`s with no matching call site — likely drift, reported as a warning only.
 * @property alreadyAnnotated how many scanned transitions an explicit `@NavEdge` already covers.
 */
internal data class InferenceResult(
  val fresh: List<EdgeKey>,
  val stale: List<EdgeKey>,
  val alreadyAnnotated: Int,
)

/**
 * Reconciles the scanned call sites with the transitions KSP already extracted.
 *
 * An explicit `@NavEdge` always wins: a scanned pair that is already annotated is dropped rather than duplicated, so
 * hand-written labels and intent survive untouched.
 *
 * The [InferenceResult.stale] report is deliberately narrow, because a warning on a correct annotation is worse than
 * no warning at all. Two guards:
 *  - the source screen must have produced at least one inferred transition here, so a feature module whose
 *    `entry<T> { }` blocks live in `:app` is never blamed for annotations it correctly declares;
 *  - the target must not merely be *named* inside that screen's block ([corroborated]) — that covers every
 *    transition taken through something the scanner cannot classify, such as
 *    `startActivity(Intent(context, DetailActivity::class.java))`.
 */
internal fun reconcileInferredEdges(
  scanned: Set<EdgeKey>,
  annotated: Set<EdgeKey>,
  corroborated: Set<EdgeKey> = emptySet(),
): InferenceResult {
  val fresh = scanned.filterNot { it in annotated }.sortedWith(edgeOrder)
  val wiredHere = scanned.mapTo(mutableSetOf()) { it.first }
  val stale = annotated
    .filter { it.first in wiredHere && it !in scanned && it !in corroborated }
    .sortedWith(edgeOrder)
  return InferenceResult(fresh, stale, alreadyAnnotated = scanned.size - fresh.size)
}

/**
 * A route reference exactly as written at a call site → a node id, or null when it cannot be tied to a known route.
 *
 * Tried in order: an already-qualified hit, the file's imports, the file's own package, then a **unique** simple-name
 * match across the route set (which covers star imports and same-package references). Ambiguity resolves to null:
 * guessing between two same-named routes would draw an edge between the wrong two screens, and a missing dashed edge
 * is a far better failure than a wrong one.
 */
internal fun resolveRouteReference(
  reference: String,
  scan: FileScan,
  knownIds: Set<String>,
  bySimpleName: Map<String, String> = uniqueSimpleNames(knownIds),
): String? {
  if (reference in knownIds) return reference
  scan.imports[reference]?.let { if (it in knownIds) return it }
  // A nested route (`AppRoute.Detail`) is imported by its OUTER name, so resolve the head and re-join the rest.
  // This runs before the same-package probe: an explicit import always beats a same-named class in this package.
  if ('.' in reference) {
    val outer = scan.imports[reference.substringBefore('.')]
    val nested = "$outer.${reference.substringAfter('.')}"
    if (outer != null && nested in knownIds) return nested
  }
  if (scan.packageName.isNotEmpty()) {
    val samePackage = "${scan.packageName}.$reference"
    if (samePackage in knownIds) return samePackage
  }
  if ('.' in reference) return null
  return bySimpleName[reference]
}

/**
 * Simple name → the single route that carries it. A name shared by two routes is absent, so an ambiguous reference
 * resolves to null exactly as scanning for a `singleOrNull` match did.
 *
 * Built once per module by [InferNavEdgesTask] rather than per reference: every capitalised token inside a
 * navigation scope is offered here (`Modifier`, `MaterialTheme`, …), and almost all of them miss, so the linear
 * scan this replaces ran the whole route set — allocating a substring per candidate — for each one.
 */
internal fun uniqueSimpleNames(knownIds: Set<String>): Map<String, String> =
  knownIds.groupBy { it.substringAfterLast('.') }
    .mapNotNull { (simple, ids) -> ids.singleOrNull()?.let { simple to it } }
    .toMap()

private val edgeOrder = compareBy<EdgeKey>({ it.first }, { it.second })
