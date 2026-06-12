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
package com.github.skydoves.navgraph.testing

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.tooling.ComposableInvoker
import java.io.File

public object NavPreviewRenderer {

  @OptIn(ExperimentalComposeUiApi::class)
  public fun renderFromSystemProperties(rule: AndroidComposeTestRule<*, *>) {
    // One or more render JOBS. A single navgraph render run can drive two pipelines — the nav graph AND the preview
    // gallery — through this one unit-test task: when `navgraph.jobCount` is set, each job `i` reads its own
    // `navgraph.renderList.<i>` / `navgraph.thumbsDir.<i>` / `navgraph.previewIndex.<i>` and writes to its OWN thumbs dir +
    // index. When absent, the legacy single-job sysprops are used (behaviour-identical to before).
    val jobCount = System.getProperty("navgraph.jobCount")?.toIntOrNull()
    val entries = if (jobCount == null) {
      readJob("navgraph.renderList", "navgraph.thumbsDir", "navgraph.previewIndex")
    } else {
      (0 until jobCount).flatMap { i ->
        readJob("navgraph.renderList.$i", "navgraph.thumbsDir.$i", "navgraph.previewIndex.$i")
      }
    }
    if (entries.isEmpty()) {
      println("compose-nav-graph-testing: render list is empty — nothing to render.")
      return
    }
    entries.forEach {
      it.thumbsDir.mkdirs()
      it.previewIndex.parentFile?.mkdirs()
    }

    // A SINGLE setContent hosts every entry across all jobs (a test rule allows only one). `current` is swapped
    // per preview and the settled frame captured into that entry's own thumbs dir / index.
    var current: RenderEntry? by mutableStateOf(null)
    rule.setContent {
      val entry = current
      if (entry != null) {
        key(entry.methodFqn) {
          NavPreviewWrapper(locale = entry.locale) {
            ComposableInvoker.invokeComposable(
              entry.methodFqn.substringBeforeLast('.'),
              entry.methodFqn.substringAfterLast('.'),
              currentComposer,
            )
          }
        }
      }
    }

    for (entry in entries) {
      val fileName = "${entry.nodeId.substringAfterLast('.')}_${entry.previewName}.png"
      val outFile = File(entry.thumbsDir, fileName)

      rule.runOnUiThread { current = entry }
      // A screen with a perpetual animation never goes idle (AppNotIdleException) — capture the settled frame
      // anyway. Any OTHER failure (e.g. an optional dependency missing at runtime → NoClassDefFoundError) would
      // otherwise blank the render silently, so surface it instead of swallowing.
      val idleError = runCatching { rule.waitForIdle() }.exceptionOrNull()
      if (idleError != null && idleError.javaClass.simpleName != "AppNotIdleException") {
        val reason = idleError.message?.take(140)
        println(
          "compose-nav-graph-testing: '${entry.previewName}' did not render cleanly " +
            "(${idleError.javaClass.simpleName}: $reason); its thumbnail may be blank.",
        )
      }

      val view = rule.activity.window.decorView
      val width = view.width.takeIf { it > 0 } ?: 1080
      val height = view.height.takeIf { it > 0 } ?: 2280
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      view.draw(Canvas(bitmap))
      outFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

      entry.previewIndex.appendText(
        "${entry.previewName}|${entry.nodeId}|$fileName|${entry.primary}\n",
      )
      println("compose-nav-graph-testing: wrote ${outFile.path} for ${entry.methodFqn}")
    }
  }

  /** Read one render job's TSV (`methodFqn\tnodeId\tpreviewName\tprimary[\tlocale]`, one preview per line —
   *  fields are trailing-additive; a 4-field line from an older plugin still parses) plus its output thumbs
   *  dir + preview index from the given system properties; an absent/blank list or dir yields no entries
   *  (the job is skipped). */
  private fun readJob(listProp: String, thumbsProp: String, indexProp: String): List<RenderEntry> {
    val listPath = System.getProperty(listProp)?.takeIf { it.isNotBlank() } ?: return emptyList()
    val thumbs = System.getProperty(thumbsProp)?.takeIf { it.isNotBlank() }?.let(::File)
      ?: return emptyList()
    val index = System.getProperty(indexProp)?.takeIf { it.isNotBlank() }?.let(::File)
      ?: return emptyList()
    return File(listPath).takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { line ->
      if (line.isBlank()) return@mapNotNull null
      val parts = line.split("\t", limit = 5)
      if (parts.size < 4) return@mapNotNull null
      RenderEntry(parts[0], parts[1], parts[2], parts[3], parts.getOrNull(4), thumbs, index)
    }
  }

  private data class RenderEntry(
    val methodFqn: String,
    val nodeId: String,
    val previewName: String,
    val primary: String,
    val locale: String?,
    val thumbsDir: File,
    val previewIndex: File,
  )
}
