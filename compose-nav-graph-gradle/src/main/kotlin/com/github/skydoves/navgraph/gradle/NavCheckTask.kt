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
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Re-renders the `.nav` baseline from the current KSP manifest and compares it (ignoring `# ` comments) to
 * the committed baseline. On drift it prints a `- removed` / `+ added` diff and fails the build
 * (`failOnNavChange`, default true) so un-reviewed navigation changes can't slip through CI. Wired into `check`.
 */
public abstract class NavCheckTask : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val manifest: RegularFileProperty

  // @Internal, NOT @InputFile: the committed baseline may not exist yet (first run / a fresh consumer), and
  // the action does its own missing-file handling — an @InputFile would fail at input snapshotting first.
  @get:Internal
  public abstract val baseline: RegularFileProperty

  @get:Input
  public abstract val failOnNavChange: Property<Boolean>

  @get:Input
  public abstract val allowMissingBaseline: Property<Boolean>

  @TaskAction
  public fun check() {
    val actual = renderBaseline(parseGraph(manifest.get().asFile.readText()))
    val file = baseline.get().asFile

    if (!file.isFile) {
      val msg = "No nav baseline at ${file.path}. Run ${navDumpPath()} to create it."
      if (allowMissingBaseline.get()) {
        logger.lifecycle("navgraph: $msg (allowMissingBaseline — skipping)")
        return
      }
      throw GradleException("navgraph: $msg")
    }

    val expected = baselineContent(file.readText())
    if (baselineContent(actual) == expected) {
      logger.lifecycle("navgraph: navigation graph matches the baseline (${file.path}).")
      return
    }

    val message = buildString {
      appendLine("navgraph: navigation graph changed — ${file.path} is out of date:")
      appendLine()
      append(diff(expected, baselineContent(actual)))
      appendLine()
      appendLine()
      append("Run ${navDumpPath()} to update the baseline, then review the diff.")
    }
    if (failOnNavChange.get()) throw GradleException(message) else logger.warn(message)
  }

  /** ":app:navCheck" → ":app:navDump" (or ":navDump" at the root). */
  private fun navDumpPath(): String = path.substringBeforeLast(':') + ":navDump"

  private companion object {
    // Multiset diff (handles duplicate lines): subtract one occurrence per matching line on each side.
    fun diff(expected: List<String>, actual: List<String>): String {
      val removed = expected.toMutableList().apply { actual.forEach { remove(it) } }
      val added = actual.toMutableList().apply { expected.forEach { remove(it) } }
      return buildString {
        removed.forEach { appendLine("  - $it") }
        added.forEach { appendLine("  + $it") }
      }.trimEnd()
    }
  }
}
