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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Adds the transitions KSP structurally cannot see. `NavGraphProcessor` reads declarations only — KSP exposes no
 * function bodies — so `entry<Home> { … backStack.add(Feed) }` is invisible to it and every transition otherwise has
 * to be hand-written as a `@NavEdge`. This scans the module's Kotlin sources with [KotlinNavScanner], resolves each
 * call site against the known route set, and appends the survivors to the manifest as `EdgeConfidence.INFERRED`.
 *
 * Precision comes from the route set, not from the scanner: a reference that does not resolve to a route already in
 * the graph is **dropped**. Nothing here ever creates a node, so a mis-read can add a dashed edge between two real
 * screens at worst — never a phantom destination.
 *
 * An explicit `@NavEdge` always wins: a pair that is already `ANNOTATED` is never re-added, so labels and
 * hand-authored intent survive untouched.
 *
 * Scoped to **this module's** routes on purpose. Reading the dependency modules' routes would take an artifact view
 * over the runtime classpath, and such a view carries the build dependencies of everything those modules publish —
 * including the nav-graph directory built by `mergeNavGraph`. `navCheck` is wired into `check`, so that quietly
 * pulled every dependency's thumbnail render and, with it, that module's AGP unit-test task, which the render's
 * `whenReady` hook filters down to the render test alone: the module's real unit tests would stop running and CI
 * would go green with them broken. A cross-module `entry<T> { }` whose route this module never names in an
 * annotation is therefore not inferred — declare that one with `@NavEdge`.
 *
 * Pure JsonElement surgery, matching [MergeNavGraphTask] and [AggregateNavGraphTask] — this module deliberately
 * carries no dependency on the typed `compose-nav-graph-annotations` model.
 */
@CacheableTask
public abstract class InferNavEdgesTask : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val kspManifest: RegularFileProperty

  /** This module's non-test Kotlin sources. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val sources: ConfigurableFileCollection

  @get:Input
  public abstract val navCalls: SetProperty<String>

  @get:OutputFile
  public abstract val outputManifest: RegularFileProperty

  init {
    navCalls.convention(KotlinNavScanner.DEFAULT_NAV_CALLS)
  }

  @TaskAction
  public fun infer() {
    val json = Json { prettyPrint = true }
    val root = json.parseToJsonElement(kspManifest.get().asFile.readText()).jsonObject
    val nodes = (root["nodes"] as? JsonArray).orEmptyArray()
    val existingEdges = (root["edges"] as? JsonArray).orEmptyArray()

    val knownIds = nodes.mapNotNullTo(mutableSetOf()) { it.jsonObject.text("id") }
    val annotated = edgePairs(existingEdges)

    val scan = scanSources(destinationsByFile(nodes), knownIds)
    val result = reconcileInferredEdges(scan.callSites.keys, annotated, scan.mentions)
    // Reported at info, never as a warning. A perfectly correct app can land here: the KotlinConf sample switches
    // between its top-level tabs from a scaffold-level navigation bar rather than from inside any `entry<T> { }`,
    // so four of its accurate @NavEdges have no call site to find. Until this can tell "wired up elsewhere" from
    // "genuinely dead" it must not cry wolf on every build.
    result.stale.forEach { (from, to) ->
      val source = from.substringAfterLast('.')
      val target = to.substringAfterLast('.')
      logger.info(
        "navgraph: no navigation call site found for @NavEdge $source → $target. " +
          "Expected when the transition is wired up outside this screen's block " +
          "(a tab bar, an Intent); otherwise the annotation may be stale.",
      )
    }

    val inferredEdges = result.fresh.map { (from, to) ->
      JsonObject(
        mapOf(
          "from" to JsonPrimitive(from),
          "to" to JsonPrimitive(to),
          "confidence" to JsonPrimitive(INFERRED),
        ),
      )
    }
    val merged = JsonObject(root + ("edges" to JsonArray(existingEdges + inferredEdges)))

    val out = outputManifest.get().asFile
    out.parentFile?.mkdirs()
    out.writeText(json.encodeToString(JsonElement.serializer(), merged))
    logger.lifecycle(
      "navgraph: inferred ${result.fresh.size} transition(s) from ${sources.files.count()} " +
        "source file(s) (${result.alreadyAnnotated} already annotated) → ${out.path}.",
    )
  }

  /**
   * Every `@NavDestination` composable indexed by the file that declares it, so a screen that navigates from its own
   * body (rather than from an `entry<T> { }` block) is still a scope the scanner can attribute calls to.
   *
   * KSP records an absolute path, which is the exact key on the machine that just ran it; the by-name fallback keeps
   * this working when the manifest was restored from a relocated build cache, and is limited to unambiguous names so
   * two `HomeScreen.kt` in different packages can never be cross-attributed.
   */
  private class DestinationIndex(hints: List<Pair<String, DestinationHint>>) {
    private val byPath = hints.groupBy({ it.first }, { it.second })
    private val byName = byPath.entries
      .groupBy({ File(it.key).name }, { it.value })
      .filterValues { it.size == 1 }
      .mapValues { (_, buckets) -> buckets.single() }

    fun forFile(file: File): List<DestinationHint> =
      byPath[file.absolutePath] ?: byName[file.name].orEmpty()
  }

  private fun destinationsByFile(nodes: JsonArray): DestinationIndex = DestinationIndex(
    nodes.mapNotNull { element ->
      val node = element.jsonObject
      val route = node.text("id") ?: return@mapNotNull null
      val clickTarget = node.text("clickTargetFqn") ?: return@mapNotNull null
      val file = node.text("sourceFile") ?: return@mapNotNull null
      val line = (node["sourceLine"] as? JsonPrimitive)?.intOrNull ?: 1
      file to DestinationHint(route, clickTarget.substringAfterLast('.'), line)
    },
  )

  /**
   * @property callSites transitions found at a navigation call, keyed by node-id pair and valued by where.
   * @property mentions pairs where the target is merely *named* inside the source's block — corroboration for the
   *   staleness check only, never a transition (see [FileScan.mentions]).
   */
  private class SourceScan(val callSites: Map<EdgeKey, String>, val mentions: Set<EdgeKey>)

  private fun scanSources(destinations: DestinationIndex, knownIds: Set<String>): SourceScan {
    // A blank entry would compile to `\b(?:)\s*\(`, matching EVERY call and turning any route mentioned anywhere
    // into a transition — so blanks are dropped, not merely the empty set.
    val requested = navCalls.get()
    val calls = requested.filter { it.isNotBlank() }.toSet()
      // Only when blanks were all that was asked for. An explicitly empty set means "match nothing", and the
      // scanner already returns nothing for it — silently reinstating the defaults there would ignore the user.
      .ifEmpty { if (requested.isEmpty()) emptySet() else KotlinNavScanner.DEFAULT_NAV_CALLS }
    val bySimpleName = uniqueSimpleNames(knownIds)
    val edges = LinkedHashMap<EdgeKey, String>()
    val mentions = mutableSetOf<EdgeKey>()
    sources.files.asSequence().filter { it.isFile && it.extension == "kt" }.forEach { file ->
      val text = runCatching { file.readText() }.getOrElse {
        logger.info("navgraph: skipping unreadable source ${file.path} (${it.message}).")
        return@forEach
      }
      val scan = KotlinNavScanner.scanKotlinFile(text, calls, destinations.forFile(file))
      fun resolvePair(raw: RawEdge): EdgeKey? {
        val from = resolveRouteReference(raw.fromRef, scan, knownIds, bySimpleName) ?: return null
        val to = resolveRouteReference(raw.toRef, scan, knownIds, bySimpleName) ?: return null
        return from to to
      }
      scan.edges.forEach { raw ->
        resolvePair(raw)?.let { edges.putIfAbsent(it, "${file.name}:${raw.line}") }
      }
      scan.mentions.forEach { raw -> resolvePair(raw)?.let(mentions::add) }
    }
    return SourceScan(edges, mentions)
  }

  private companion object {
    fun edgePairs(edges: JsonArray): Set<EdgeKey> = edges.mapNotNullTo(mutableSetOf()) { edge ->
      val o = edge.jsonObject
      val from = o.text("from") ?: return@mapNotNullTo null
      val to = o.text("to") ?: return@mapNotNullTo null
      from to to
    }

    fun JsonArray?.orEmptyArray(): JsonArray = this ?: JsonArray(emptyList())

    fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
  }
}
