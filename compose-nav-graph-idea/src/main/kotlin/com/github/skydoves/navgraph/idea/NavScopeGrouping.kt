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

/**
 * Groups navigation-bearing modules into **app scopes** purely from the Gradle module dependency graph — no
 * IntelliJ types, so it is unit-testable. This is what keeps a single app split across feature modules
 * (e.g. nowinandroid's `:app` + `:feature:*`) as ONE graph, while two independent apps in the same repo
 * (e.g. `:sample` + `:sample-kmp`) stay SEPARATE. Path hierarchy can't tell them apart — both are siblings —
 * so dependency reachability is the only reliable signal.
 *
 * **App-rooted reachability.** A *carrier* is any module that owns a manifest OR transitively depends on one —
 * every module that takes part in navigation, INCLUDING the application module that merely wires feature modules
 * together (it commonly owns no manifest of its own). A carrier is a *root* (an app) when no OTHER carrier
 * transitively depends on it; its scope is the root plus every nav module it can reach (intermediate non-nav
 * modules still relay the connection). Consequences that match real projects:
 *  - independent apps → each is its own root → separate scopes;
 *  - one app over many feature modules → only the app is a root, features fold into it → one scope — and this
 *    holds even when the app module has NO manifest (the common multi-module case, e.g. nowinandroid `:app`);
 *  - a feature shared by two apps → reachable from both roots → it appears in BOTH scopes (correct: it really
 *    is part of both apps).
 */
internal object NavScopeGrouping {

  /** @property root the app module of this scope; @property members every nav module shown under it (incl. root). */
  data class ModuleScope(val root: String, val members: Set<String>)

  /**
   * @param navModules ids of modules that own a `nav-graph.json` manifest.
   * @param directDeps moduleId → the module ids it *directly* depends on (the full graph; may name non-nav modules).
   * @return one [ModuleScope] per app, deterministically ordered by root id. Empty in ⇒ empty out.
   */
  fun computeScopes(
    navModules: Set<String>,
    directDeps: Map<String, Set<String>>,
  ): List<ModuleScope> {
    if (navModules.isEmpty()) return emptyList()
    if (navModules.size == 1) {
      val only = navModules.first()
      return listOf(ModuleScope(only, setOf(only)))
    }

    // Transitive reachability over the FULL graph, memoized. A non-nav module between an app and a feature
    // (e.g. an aggregator library) still has to relay the edge, so we don't restrict the walk to nav modules.
    val reach = HashMap<String, Set<String>>()
    fun reachOf(start: String): Set<String> = reach.getOrPut(start) {
      val seen = LinkedHashSet<String>()
      val stack = ArrayDeque(directDeps[start].orEmpty().toList())
      while (stack.isNotEmpty()) {
        val m = stack.removeLast()
        if (seen.add(m)) stack.addAll(directDeps[m].orEmpty())
      }
      seen
    }

    // The modules that CARRY navigation: a nav module itself, or any module that transitively reaches one. The
    // application module that merely wires features together is here too — even though it owns NO manifest (it
    // doesn't apply navgraph). Restricting roots to manifest-owning modules was the bug: nowinandroid's `:app` was
    // then never a root, so its feature modules — which don't depend on each other — each became a separate graph.
    val carriers = (directDeps.keys + navModules)
      .filter { it in navModules || reachOf(it).any { dep -> dep in navModules } }
      .toSet()

    // A carrier is "depended upon" (⇒ not a root) when some OTHER carrier can transitively reach it.
    val dependedUpon = HashSet<String>()
    for (m in carriers) {
      for (r in reachOf(m)) {
        if (r != m && r in carriers) dependedUpon.add(r)
      }
    }

    val roots = carriers.filter { it !in dependedUpon }
    // Every carrier is depended upon ⇒ a dependency cycle among apps; treat the tangle as one scope.
    if (roots.isEmpty()) return listOf(ModuleScope(navModules.min(), navModules.toSortedSet()))

    val scopes = roots.sorted().map { root ->
      val members = LinkedHashSet<String>()
      if (root in navModules) members.add(root)
      members.addAll(reachOf(root).filter { it in navModules })
      ModuleScope(root, members)
    }.toMutableList()

    // Safety net: a nav module reached by no root (e.g. it only sits inside a cycle that isn't a root) becomes
    // its own scope, so nothing is ever silently dropped from the tool window.
    val covered = scopes.flatMapTo(HashSet()) { it.members }
    for (m in navModules.sorted()) {
      if (m !in covered) scopes.add(ModuleScope(m, setOf(m)))
    }
    return scopes
  }
}
