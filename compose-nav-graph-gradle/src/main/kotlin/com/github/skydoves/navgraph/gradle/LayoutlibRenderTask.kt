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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.JavaVersion
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The navgraph render backend: renders every `@NavPreview` to a PNG **device-free, on API 36**, by driving Google's
 * standalone `compose-preview-renderer` CLI ourselves — no emulator, no Robolectric, no Compose-Screenshot-Testing
 * source set / experimental flags. The consumer adds nothing beyond the plugin + `@NavPreview`; this task gathers
 * the variant's classpath + resources, writes the renderer's `PreviewRendering` JSON from the KSP manifest, runs
 * it, and joins each PNG back to its node, emitting `thumbs/<previewName>.png` + `preview-index.txt` for
 * [MergeNavGraphTask].
 */
@DisableCachingByDefault(
  because = "Drives an external renderer JVM; input/output up-to-date checks suffice",
)
public abstract class LayoutlibRenderTask : DefaultTask() {

  /** The KSP manifest — `previews[].previewMethodFqn` is the JVM method the renderer hosts. */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val kspManifest: RegularFileProperty

  /** `compose-preview-renderer` + the Layoutlib framework classes (`layoutlib`) — the renderer's own classpath. */
  @get:Classpath
  public abstract val rendererClasspath: ConfigurableFileCollection

  /** The unpacked Layoutlib data dir from [PrepareLayoutlibTask]; pinned + constant, so tracked by version only. */
  @get:Internal
  public abstract val layoutlibDir: DirectoryProperty

  @get:Input
  public abstract val layoutlibVersion: Property<String>

  /** The module's runtime dependencies as `android-classes-jar`s (the same view AGP uses for unit tests). */
  @get:Classpath
  public abstract val appClasspath: ConfigurableFileCollection

  /** This module's compiled classes (`kotlin-classes/<variant>`, under `build/tmp`) — tracked, so a code edit
   *  re-renders the thumbnails. */
  @get:Classpath
  public abstract val projectClasspath: ConfigurableFileCollection

  /** The module's generated `R.jar`(s). `@Internal` because they live under the shared `build/intermediates`
   *  tree; declaring that as a tracked input trips Gradle's overlapping-output validation when a render is
   *  scheduled alongside the unit tests. Ordering is guaranteed by the task's explicit dependencies instead. */
  @get:Internal
  public abstract val rClassPath: ConfigurableFileCollection

  /** The linked resources `.ap_` (from the unit-test resource link) so Layoutlib resolves `@string`/themes/etc.
   *  `@Internal` for the same `build/intermediates` overlap reason as [rClassPath]. */
  @get:Internal
  public abstract val resourceApk: ConfigurableFileCollection

  @get:Input
  public abstract val namespace: Property<String>

  @get:Input
  public abstract val apiLevel: Property<String>

  /** Scratch dir for the generated JSON + raw PNGs + results.json (not the consumed thumbnails). */
  @get:Internal
  public abstract val workDir: DirectoryProperty

  @get:OutputDirectory
  public abstract val thumbsDir: DirectoryProperty

  @get:OutputFile
  public abstract val previewIndex: RegularFileProperty

  private data class Shot(
    val nodeId: String,
    val name: String,
    val primary: Boolean,
    val methodFqn: String,
    val id: String,
    val params: List<HPreviewParam>,
    val locale: String?,
  )

  @TaskAction
  public fun render() {
    check(JavaVersion.current() >= JavaVersion.VERSION_17) {
      "navgraph: the device-free Layoutlib renderer needs JDK 17+ to run Gradle " +
        "(current: ${JavaVersion.current()}). " +
        "Point your Gradle JVM (org.gradle.java.home, or a Java toolchain) at 17 or newer."
    }
    val graph = parseGraph(kspManifest.get().asFile.readText())
    val shots = buildList {
      var i = 0
      graph.nodes.forEach { node ->
        node.previews.forEach { pv ->
          val method = pv.previewMethodFqn
          if (method.isNullOrBlank()) {
            logger.warn(
              "navgraph: @NavPreview '${pv.previewName}' has no JVM methodFqn (regenerate KSP) — skipping.",
            )
          } else {
            add(
              Shot(
                node.id, pv.previewName, pv.primary, method, "nav${i++}",
                pv.previewParameters, pv.locale,
              ),
            )
          }
        }
      }
    }

    val thumbs = thumbsDir.get().asFile.apply { mkdirs() }
    // This render is authoritative for the thumbnails — drop stale PNGs (renamed/removed previews) so they don't
    // linger after a renderer rename/move.
    thumbs.listFiles()?.forEach { if (it.name.endsWith(".png")) it.delete() }
    val indexFile = previewIndex.get().asFile

    if (shots.isEmpty()) {
      indexFile.writeText("")
      logger.lifecycle("navgraph: no @NavPreview to render (layoutlib).")
      return
    }

    if (resourceApk.files.none { it.isFile }) {
      logger.warn(
        "navgraph: no unit-test linked resources (.ap_) found — previews " +
          "using @string/@drawable/themes may render blank. Enable " +
          "android.testOptions.unitTests.isIncludeAndroidResources = true.",
      )
    }
    if (namespace.get().isBlank()) {
      logger.warn(
        "navgraph: could not resolve this module's android " +
          "namespace — its own R resources may not render.",
      )
    }

    val work = workDir.get().asFile.apply { mkdirs() }
    val pngDir = work.resolve("png").apply { mkdirs() }
    val resultsFile = work.resolve("results.json").apply { delete() }

    val input = buildJsonObject {
      put("fontsPath", "")
      put("layoutlibPath", layoutlibDir.get().asFile.absolutePath)
      put("outputFolder", pngDir.absolutePath)
      put("metaDataFolder", work.resolve("meta").absolutePath)
      putJsonArray("classPath") { appClasspath.files.forEach { add(it.absolutePath) } }
      putJsonArray("projectClassPath") {
        (projectClasspath.files + rClassPath.files).forEach { add(it.absolutePath) }
      }
      put("namespace", namespace.get())
      put("resourceApkPath", resourceApk.files.firstOrNull { it.isFile }?.absolutePath ?: "")
      put("resultsFilePath", resultsFile.absolutePath)
      putJsonArray("screenshots") {
        shots.forEach { s ->
          addJsonObject {
            put("methodFQN", s.methodFqn)
            // @PreviewParameter args: the renderer instantiates the composable's sample value from each provider.
            // limit=1 ⇒ render just the first value (one thumbnail, not one per provider element).
            putJsonArray("methodParams") {
              s.params.forEach { p ->
                addJsonObject {
                  put("provider", p.provider)
                  put("name", p.name)
                  put("limit", "1")
                }
              }
            }
            // A default phone device so EVERY preview has a bounded canvas. Without it, a plain `@Preview` (no
            // device) on an unsized lazy layout (LazyColumn/Grid) measures to 0 → a 1×1 blank thumbnail; a
            // `@DevicePreviews`-annotated preview already carries its own device and renders fine either way.
            putJsonObject("previewParams") {
              put("apiLevel", apiLevel.get())
              put("device", PREVIEW_DEVICE)
              // The @Preview(locale = …) qualifier, extracted by KSP (a multipreview's meta-annotation isn't
              // visible to the renderer itself, so it must be passed explicitly).
              s.locale?.let { put("locale", it) }
            }
            put("previewId", s.id)
          }
        }
      }
    }
    val inputFile = work.resolve("input.json")
    inputFile.writeText(Json.encodeToString(JsonObject.serializer(), input))

    // Run the renderer as a child process. It keeps its JVM alive after finishing (non-daemon IntelliJ/Layoutlib
    // threads never return from main), so blocking on exit would stall the build for minutes. Instead we poll
    // results.json — the renderer's true completion signal, one entry per previewId — and terminate the process
    // the moment every preview is in. A hard cap bounds a genuinely stuck render. (`--enable-native-access` is
    // valid on JDK 17+ and silences the Layoutlib native-memory warning.)
    val javaBin = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
    val cp = rendererClasspath.files.joinToString(File.pathSeparator) { it.absolutePath }
    val command =
      listOf(
        javaBin,
        "--enable-native-access=ALL-UNNAMED",
        "-cp",
        cp,
        RENDERER_MAIN,
        inputFile.absolutePath,
      )
    logger.lifecycle("navgraph: layoutlib rendering ${shots.size} preview(s)…")
    val started = System.currentTimeMillis()
    val process = ProcessBuilder(command)
      .redirectErrorStream(true)
      .redirectOutput(work.resolve("renderer.log"))
      .start()
    val wantedIds = shots.map { it.id }.toSet()
    var exitedOnOwn = false
    var stalled = false
    var landed = 0
    var lastProgressAt = started
    while (System.currentTimeMillis() - started < RENDER_TIMEOUT_MINUTES * 60_000L) {
      if (process.waitFor(2, TimeUnit.SECONDS)) {
        exitedOnOwn = true
        break
      }
      val results = if (resultsFile.isFile) readLayoutlibResults(resultsFile) else emptyMap()
      if (results.keys.containsAll(wantedIds)) break
      // Stall guard: a Compose-Multiplatform preview Layoutlib can't draw hangs the renderer mid-preview, so
      // results.json stops growing. If no NEW preview has landed for RENDER_STALL_SECONDS, give up (in AUTO the
      // Robolectric pass renders these) instead of burning the full hard cap on a render that will never finish.
      if (results.size > landed) {
        landed = results.size
        lastProgressAt = System.currentTimeMillis()
      } else if (System.currentTimeMillis() - lastProgressAt > RENDER_STALL_SECONDS * 1000L) {
        stalled = true
        break
      }
    }
    if (process.isAlive) process.destroyForcibly()
    val elapsed = (System.currentTimeMillis() - started) / 1000
    // The renderer writes results.json atomically at the very end then lingers (non-daemon threads), so it is
    // almost always force-killed above; report that honestly rather than a meaningless "exit=0".
    val exitInfo = when {
      exitedOnOwn -> "exit=${runCatching {
        process.exitValue()
      }.getOrDefault(-1)}"

      stalled -> "killed after stalling ${RENDER_STALL_SECONDS}s with no new preview"

      else -> "killed after completion"
    }

    val byId = readLayoutlibResults(resultsFile)
    val index = StringBuilder()
    var ok = 0
    var failed = 0
    shots.forEach { s ->
      val res = byId[s.id]
      val img = res?.imagePath?.let { pngDir.resolve(it) }
      val success = isLayoutlibSuccess(res, img)
      if (success && img != null) {
        // Filename keyed by route + preview name (not the preview name alone) so two routes whose preview
        // functions share a simple name don't clobber each other's thumbnail. Stable across builds (unlike the id).
        val dest = "${s.nodeId.substringAfterLast('.')}_${s.name}.png"
        img.copyTo(thumbs.resolve(dest), overwrite = true)
        index.appendLine("${s.name}|${s.nodeId}|$dest|${s.primary}")
        ok++
      } else {
        failed++
        val why = when {
          res == null -> "no result"

          res.brokenClasses.isNotEmpty() ->
            "missing classes on the render classpath — " +
              "${res.brokenClasses.joinToString()}"

          res.problems.isNotEmpty() -> "render problem — ${res.problems.first()}"

          res.message != null -> res.message

          res.status != null && res.status != "SUCCESS" -> res.status

          else -> "no image produced"
        }
        logger.warn("navgraph: layoutlib render failed for '${s.name}' (${s.methodFqn}): $why")
      }
    }
    indexFile.writeText(index.toString())

    if (ok == 0) {
      logger.warn(
        "navgraph: layoutlib rendered 0/${shots.size} thumbnail(s) in ${elapsed}s ($exitInfo). " +
          "See ${resultsFile.absolutePath} and ${work.resolve("renderer.log")}.",
      )
    } else {
      logger.lifecycle(
        "navgraph: layoutlib rendered $ok/${shots.size} thumbnail(s) in ${elapsed}s" +
          (if (failed > 0) " ($failed failed)" else "") + ".",
      )
    }
  }

  private companion object {
    const val RENDERER_MAIN: String = "com.android.tools.render.common.MainKt"

    /** Hard cap on a single render run — bounds a genuinely stuck renderer (completion is detected far sooner). */
    const val RENDER_TIMEOUT_MINUTES: Int = 10

    /** Give up if no new preview lands for this long. Layoutlib that can't draw a Compose-Multiplatform screen hangs
     *  mid-preview; without this an all-CMP app burns the full [RENDER_TIMEOUT_MINUTES] before AUTO falls back to
     *  Robolectric. Generous enough to clear renderer startup plus the slowest legitimate single preview. */
    const val RENDER_STALL_SECONDS: Int = 60

    /** Default preview device (Compose Studio's "Phone") — a bounded canvas so unsized/lazy previews render. */
    const val PREVIEW_DEVICE: String = "spec:width=411dp,height=891dp,dpi=420"
  }
}
