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
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * Assembles the Layoutlib **data directory** the standalone renderer points at (`layoutlibPath`) — entirely
 * inside the plugin, so a consumer only applies `com.github.skydoves.navgraph` + adds `@NavPreview`:
 *
 *  1. unpack the OS-specific `com.android.tools.layoutlib:layoutlib-runtime:<version>:<os>` jar, which ships
 *     `data/` at its top level (native render libs, fonts, icu, hyphenation, `build.prop` → `ro.build.version.sdk=36`);
 *  2. inject the framework resources — `com.android.tools.layoutlib:layoutlib-resources:<version>` copied
 *     verbatim as `data/framework_res.jar` (the runtime jar deliberately omits it so the per-OS native jar and
 *     the OS-independent resources can version + cache separately).
 *
 * Pinned + cached: the inputs are immutable published artifacts, so after the first run Gradle's up-to-date
 * check skips it. Not `@CacheableTask` — the ~80 MB unpacked tree shouldn't bloat a shared build cache.
 */
public abstract class PrepareLayoutlibTask : DefaultTask() {

  /** The OS-classifier `layoutlib-runtime` jar (`mac-arm`/`mac`/`linux`/`win`) — unpacked to [layoutlibDir]. */
  @get:Classpath
  public abstract val runtimeJar: ConfigurableFileCollection

  /** The `layoutlib-resources` jar — copied verbatim to `data/framework_res.jar`. */
  @get:Classpath
  public abstract val resourcesJar: ConfigurableFileCollection

  @get:OutputDirectory
  public abstract val layoutlibDir: DirectoryProperty

  @get:Inject
  public abstract val archives: ArchiveOperations

  @get:Inject
  public abstract val fs: FileSystemOperations

  @TaskAction
  public fun prepare() {
    val out = layoutlibDir.get().asFile
    val runtime = runtimeJar.files.firstOrNull { it.name.endsWith(".jar") }
      ?: error(
        "navgraph: no layoutlib-runtime jar resolved (com.android.tools.layoutlib:layoutlib-runtime:<os>).",
      )
    val resources = resourcesJar.files.firstOrNull { it.name.endsWith(".jar") }
      ?: error(
        "navgraph: no layoutlib-resources jar resolved (com.android.tools.layoutlib:layoutlib-resources).",
      )

    // Clean + unpack the runtime jar (yields data/ at the top level), then drop in the framework resources.
    // (`kotlin-dsl` enables SAM-with-receiver for org.gradle.api.Action, so these configure by receiver.)
    fs.delete { delete(out) }
    fs.copy {
      from(archives.zipTree(runtime))
      into(out)
    }
    fs.copy {
      from(resources)
      into(out.resolve("data"))
      rename { "framework_res.jar" }
    }
    logger.lifecycle("navgraph: prepared Layoutlib data at ${out.absolutePath}")
  }
}
