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
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes the committed `.nav` baseline from the current KSP manifest — a flat, deterministic, structure-only
 * snapshot of the nav graph (destinations + typed args + transitions). Reads the KSP manifest directly, so it
 * does NOT need the render/merge. Commit the output; [NavCheckTask] verifies it.
 */
public abstract class NavDumpTask : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val manifest: RegularFileProperty

  @get:OutputFile
  public abstract val baseline: RegularFileProperty

  @TaskAction
  public fun dump() {
    val graph = parseGraph(manifest.get().asFile.readText())
    val out = baseline.get().asFile
    out.parentFile?.mkdirs()
    out.writeText(renderBaseline(graph))
    logger.lifecycle(
      "navgraph: wrote baseline ${out.path} — ${graph.nodes.size} destinations, ${graph.edges.size} edges.",
    )
  }
}
