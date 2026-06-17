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
import com.google.gson.Gson
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Discovers every Gradle module's `build/navgraph/nav-graph.json`, groups them into **app
 * scopes** by module dependencies (see [NavScopeGrouping]), and merges each scope into one
 * graph (nodes deduped by FQN `id`; edges concatenated + deduped). So a repo with two
 * independent apps shows two selectable graphs, while one app split across feature modules
 * still shows as a single merged graph. Pure file IO + a read action for the module model —
 * call [loadScopes] off the EDT.
 */
internal object NavGraphReader {

  private val gson = Gson()
  private val log = Logger.getInstance(NavGraphReader::class.java)

  /**
   * The `NavGraph.schemaVersion` this plugin build understands; a higher manifest version warns.
   */
  private const val SUPPORTED_SCHEMA = 1

  /** Bounded recursion for the no-Gradle-model fallback scan (deep enough for `feature/name/impl` nesting). */
  private const val MAX_FALLBACK_DEPTH = 6

  /** Dirs the fallback scan never descends into (build outputs, VCS/IDE metadata, sources). */
  private val SKIP_DIRS = setOf("build", ".gradle", ".git", ".idea", "node_modules", "src")

  /** Manifest path relative to a Gradle module dir (written by the `generateNavGraph` task). */
  private const val MANIFEST_REL = "build/navgraph/nav-graph.json"

  /** @property thumbs node id → its primary thumbnail image (decoded off-EDT). */
  data class Loaded(val graph: NavGraphDto, val thumbs: Map<String, BufferedImage>)

  /**
   * One selectable graph in the tool window.
   * @property id the app/root module path (persisted selection key).
   */
  data class LoadedScope(
    val id: String,
    val name: String,
    val graph: NavGraphDto,
    val thumbs: Map<String, BufferedImage>,
  )

  /**
   * Loads one [LoadedScope] per independent app. Single-app repos return a one-element list
   * (the tool window then hides its selector). Empty when no manifest exists anywhere. Order is
   * stable (by display name).
   */
  fun loadScopes(project: Project): List<LoadedScope> {
    val model = readModuleModel(project)
    val navProjects = model.navProjects.ifEmpty { fallbackNavProjects(project) }
    if (navProjects.isEmpty()) return emptyList()

    // Shared thumbnail index (both pipelines) so a node missing its own render reuses one the
    // gallery produced.
    val fqnIndex = NavGraphPreviewThumbnails.indexByFqn(project)
    return NavScopeGrouping.computeScopes(navProjects.keys, model.directDeps)
      .mapNotNull { scope ->
        val manifests = scope.members.mapNotNull { navProjects[it]?.manifest }
        if (manifests.isEmpty()) return@mapNotNull null
        val merged = mergeManifests(manifests, fqnIndex)
        if (merged.graph.nodes.isEmpty()) return@mapNotNull null
        LoadedScope(
          id = scope.root,
          name = navProjects[scope.root]?.name ?: moduleLabel(scope.root, project.basePath),
          graph = merged.graph,
          thumbs = merged.thumbs,
        )
      }
      .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
  }

  /** A Gradle module that owns a nav manifest. [id] is its external (Gradle) project path. */
  private data class NavProject(val id: String, val name: String, val manifest: File)

  /**
   * Snapshot of the IntelliJ module model taken under a single read action (no IO held under
   * the lock).
   */
  private data class ModuleModel(
    val navProjects: Map<String, NavProject>,
    val directDeps: Map<String, Set<String>>,
  )

  /**
   * Walks the IntelliJ modules once, keyed by Gradle project path
   * ([ExternalSystemApiUtil.getExternalProjectPath] — so a module's
   * `.main`/`.test`/`.androidTest` source-set splits all collapse to one id). Records which
   * Gradle projects own a manifest and the module dependency edges between them.
   */
  private fun readModuleModel(project: Project): ModuleModel = runReadAction {
    val navProjects = LinkedHashMap<String, NavProject>()
    val directDeps = HashMap<String, MutableSet<String>>()
    for (module in ModuleManager.getInstance(project).modules) {
      val path = ExternalSystemApiUtil.getExternalProjectPath(module) ?: continue
      if (path !in navProjects) {
        val manifest = File(path, MANIFEST_REL)
        if (manifest.isFile) {
          navProjects[path] = NavProject(path, moduleLabel(path, project.basePath), manifest)
        }
      }
      val deps = directDeps.getOrPut(path) { LinkedHashSet() }
      for (dep in ModuleRootManager.getInstance(module).dependencies) {
        val depPath = ExternalSystemApiUtil.getExternalProjectPath(dep) ?: continue
        if (depPath != path) deps.add(depPath)
      }
    }
    ModuleModel(navProjects, directDeps)
  }

  /**
   * Fallback when the IntelliJ external-system model is unavailable (project not yet imported
   * as Gradle): walk the source tree (bounded depth) for module manifests, including NESTED
   * feature modules, each its own scope. No dependency info ⇒ no merging.
   */
  private fun fallbackNavProjects(project: Project): Map<String, NavProject> {
    val base = project.basePath ?: return emptyMap()
    val out = LinkedHashMap<String, NavProject>()

    // Walk the source tree (NOT just the root's direct children) so NESTED feature modules like
    // `feature/name/impl` are found when the Gradle model is unavailable. Bounded depth + skip build/VCS dirs.
    fun visit(dir: File, depth: Int) {
      val manifest = File(dir, MANIFEST_REL)
      if (manifest.isFile) {
        out[dir.path] =
          NavProject(dir.path, moduleLabel(dir.path, base), manifest)
      }
      if (depth >= MAX_FALLBACK_DEPTH) return
      dir.listFiles()?.forEach { child ->
        if (child.isDirectory && child.name !in SKIP_DIRS && !child.name.startsWith(".")) {
          visit(child, depth + 1)
        }
      }
    }
    visit(File(base), 0)
    return out
  }

  /**
   * A disambiguating label for a module dir: its Gradle-style path relative to the project root
   * (`:feature:name:impl`), so nested modules that share a last segment (`impl`, `sample`) stay distinct.
   * Falls back to the dir name when the module isn't under the root.
   */
  private fun moduleLabel(path: String, basePath: String?): String {
    val base = basePath ?: return File(path).name
    if (File(path) == File(base)) return File(path).name
    val rel = runCatching {
      File(base).toPath().relativize(File(path).toPath()).toString()
    }.getOrNull()
    return if (rel.isNullOrEmpty() || rel.startsWith("..")) {
      File(path).name
    } else {
      ":" + rel.replace(File.separatorChar, ':')
    }
  }

  /**
   * Merge a set of manifests into one graph: dedup nodes by FQN id, concat+dedup edges, decode
   * thumbnails.
   */
  private fun mergeManifests(manifests: List<File>, fqnIndex: Map<String, File>): Loaded {
    val nodes = LinkedHashMap<String, NavNodeDto>()
    val edges = LinkedHashSet<NavEdgeDto>()
    val thumbs = HashMap<String, BufferedImage>()

    for (manifest in manifests) {
      val graph = runCatching { gson.fromJson(manifest.readText(), NavGraphDto::class.java) }
        .getOrNull() ?: continue
      if (graph.schemaVersion > SUPPORTED_SCHEMA) {
        log.warn(
          "${manifest.path}: nav-graph schemaVersion ${graph.schemaVersion} " +
            "> supported $SUPPORTED_SCHEMA — " +
            "update the NavGraph Graph plugin; some data may not render.",
        )
      }
      val dir = manifest.parentFile
      for (node in graph.nodes) {
        if (node.id.isBlank()) continue
        // Cross-module: the same node can appear as a bare edge-target in one module and the
        // real screen (with click target / args / preview / start) in another. Keep the richer
        // fields from either.
        nodes[node.id] = nodes[node.id]?.let { mergeRicher(it, node) } ?: node
        if (node.id !in thumbs) {
          val primary = node.previews.firstOrNull { it.primary } ?: node.previews.firstOrNull()
          val own = primary?.thumbnail?.let { File(dir, it) }?.takeIf { it.isFile }
          // Reuse a thumbnail the preview gallery already rendered for the same function when
          // this graph has none.
          val png = own ?: primary?.previewFqn?.let { fqnIndex[it] }
          if (png != null) {
            runCatching { ImageIO.read(png) }.getOrNull()?.let { thumbs[node.id] = it }
          }
        }
      }
      edges.addAll(graph.edges)
    }
    return Loaded(NavGraphDto(nodes = nodes.values.toList(), edges = edges.toList()), thumbs)
  }

  /**
   * Combine two manifests' versions of one node id, preferring whichever side actually carries
   * each field.
   */
  private fun mergeRicher(a: NavNodeDto, b: NavNodeDto): NavNodeDto = a.copy(
    route = a.route.ifBlank { b.route },
    module = a.module ?: b.module,
    clickTargetFqn = a.clickTargetFqn ?: b.clickTargetFqn,
    sourceFile = a.sourceFile ?: b.sourceFile,
    sourceLine = a.sourceLine ?: b.sourceLine,
    args = a.args.ifEmpty { b.args },
    previews = a.previews.ifEmpty { b.previews },
    start = a.start || b.start,
  )
}
