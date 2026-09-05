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

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Checks the *shape* of the merged navigation graph — unreachable screens, a missing or duplicated start
 * destination, routes no `@NavDestination` binds — and reports what it finds.
 *
 * Complements the `.nav` baseline, which reviews **change**: `navCheck` is happy as long as an unreachable screen
 * stays unreachable, because the baseline it compares against records it too. This task looks at the graph's
 * current state instead.
 *
 * Warns by default; `navgraph { failOnNavLint = true }` gates CI on it. Not wired into `check` unless
 * `navgraph { lintOnCheck = true }` — it reads the aggregated graph, which would otherwise pull every dependency
 * module's thumbnail render into `check`.
 */
public abstract class NavLintTask : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val manifest: RegularFileProperty

  @get:Input
  public abstract val failOnNavLint: Property<Boolean>

  /** Rule ids to skip — see [NavLintRule.id]. */
  @get:Input
  public abstract val disabledRules: SetProperty<String>

  /** Route FQNs that report nothing, for a screen left unwired on purpose. */
  @get:Input
  public abstract val ignoredRoutes: SetProperty<String>

  /**
   * Whether [manifest] is the aggregated graph of every module. When false the graph is one module's own, where a
   * route owned by another module appears as a stub, so findings are noted as possibly incomplete rather than
   * presented as the whole truth.
   */
  @get:Input
  public abstract val aggregated: Property<Boolean>

  init {
    failOnNavLint.convention(false)
    aggregated.convention(false)
  }

  @TaskAction
  public fun lint() {
    val graph = parseGraph(manifest.get().asFile.readText())
    val findings = analyzeGraph(graph, disabledRules.get(), ignoredRoutes.get())

    if (findings.isEmpty()) {
      logger.lifecycle(
        "navgraph: navigation lint found no issues " +
          "(${graph.nodes.size} destination(s), ${graph.edges.size} transition(s)).",
      )
      return
    }

    val message = buildString {
      appendLine("navgraph: ${findings.size} navigation lint finding(s):")
      appendLine()
      findings.forEach { finding ->
        appendLine("  [${finding.rule.id}] ${finding.displayName}")
        appendLine("      ${finding.detail}")
      }
      appendLine()
      if (!aggregated.get()) {
        appendLine(
          "This is ${modulePath()}'s own graph, not the whole app's — a route another " +
            "module owns reads as unbound here. Run this on the module that aggregates " +
            "them for the complete picture.",
        )
      }
      append("Silence a rule with navgraph { navLintDisabledRules }, ")
      append("or one route with navgraph { navLintIgnoredRoutes }.")
    }
    if (failOnNavLint.get()) throw GradleException(message) else logger.warn(message)
  }

  /** ":app:navLint" → ":app" (or ":" at the root). Read off the task path, never `project`, which the
   *  configuration cache forbids touching at execution time. */
  private fun modulePath(): String = path.substringBeforeLast(':').ifEmpty { ":" }
}
