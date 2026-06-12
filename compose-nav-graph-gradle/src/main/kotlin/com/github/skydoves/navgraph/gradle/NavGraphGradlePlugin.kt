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

import org.gradle.api.NamedDomainObjectCollection
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import java.io.File

/**
 * Wires the Navigation 3 graph pipeline, detecting the module type after plugins apply:
 *  - **Kotlin Multiplatform + Android** (`androidLibrary {}`): KSP over the Android compilation, plus a device-free
 *    **Layoutlib** render that reuses the consuming app's linked resources (a KMP library has no R/.ap_ of its own).
 *  - **Kotlin Multiplatform** without Android: KSP over the common-metadata pass, structure only (no render).
 *  - **Android**: KSP over the (auto-detected) debug variant, plus a device-free **Layoutlib** render of every
 *    `@NavPreview` into thumbnails — Google's standalone compose-preview-renderer + Maven Layoutlib (API 36),
 *    no emulator, no source set, no flags — toggled by `navgraph { renderThumbnails }`.
 *
 * Entry point: `./gradlew :<module>:generateNavGraph`.
 */
public class NavGraphGradlePlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      // Created eagerly so a consumer `navgraph { }` block can configure it before evaluation finishes.
      val ext = extensions.create("navgraph", NavGraphExtension::class.java)
      ext.baselineFile.convention(layout.projectDirectory.file("nav/$name.nav"))
      ext.failOnNavChange.convention(true)
      ext.allowMissingBaseline.convention(false)
      ext.renderThumbnails.convention(true)
      ext.renderBackend.convention(RenderBackend.AUTO)
      ext.robolectricApplication.convention("")
      ext.variant.convention("")
      ext.autoDependencies.convention(true)
      ext.aggregate.convention(true)
      ext.galleryEnabled.convention(true)
      ext.galleryRenderBackend.convention(RenderBackend.AUTO)
      ext.galleryAggregate.convention(true)

      // Flip `testOptions.unitTests.isIncludeAndroidResources` AS the Android plugin applies (not in afterEvaluate)
      // — AGP reads it while configuring the unit-test task, so a late toggle leaves that task's classpath
      // provider unset (its dependency resolution then throws). It is the merged-resources hook the Robolectric
      // render needs (CMP `Res.*` resolve), and is harmless otherwise.
      ANDROID_PLUGINS.forEach { id ->
        pluginManager.withPlugin(id) { setIncludeAndroidResources(target) }
      }

      // compose-nav-graph-ksp must reach the consumer's KSP configuration BEFORE KSP snapshots its processor classpath in its
      // own afterEvaluate (KSP applies before this plugin), so the afterEvaluate that runs autoWireDependencies is
      // too late — the dependency lands in the config but never reaches the KSP task, which then SKIPs (no
      // manifest). An eager reaction at config-creation adds it in time, for plain-Android and KMP+Android alike.
      wireNavGraphKspProcessor(target, ext)
      wireKspModuleArg(target)

      // Mode detection needs all plugins applied (order-independent), so resolve it at the end of config.
      afterEvaluate {
        // `renderThumbnails = false` → structure-only (no render); KMP-without-Android can't render anyway.
        val wantRender = ext.renderThumbnails.get()
        if (ext.autoDependencies.get()) autoWireDependencies(this, ext)
        when {
          // KMP + Android: KSP over the Android compilation (commonMain structure + androidMain @NavPreview), plus
          // the Layoutlib render off androidMain — reusing the consuming app's linked resources (wired in `wire`).
          // The KSP task + manifest path differ by which Android DSL is in use (resolved in `kmpAndroidKsp`): the new
          // `com.android.kotlin.multiplatform.library` (`androidLibrary {}`) emits a single `kspAndroidMain`, while
          // the legacy `com.android.library` + `kotlin { androidTarget() }` emits per-variant `ksp<V>KotlinAndroid`.
          plugins.hasPlugin(KMP_PLUGIN) && ANDROID_PLUGINS.any { plugins.hasPlugin(it) } -> {
            val (kspTask, manifest) = kmpAndroidKsp(this)
            wire(this, ext, kspTask, manifest, render = wantRender, kmp = true)
          }

          // KMP without an Android target (iOS/JS/wasm only): commonMain metadata, structure only, no render.
          plugins.hasPlugin(KMP_PLUGIN) ->
            wire(this, ext, KMP_KSP_TASK, KMP_MANIFEST, render = false)

          // Plain Android app/library: resolve the variant — an explicit `navgraph { variant }` or, if blank, the
          // auto-detected first `…DebugKotlin` KSP variant (so a flavored project like nowinandroid, whose task
          // is `kspDemoDebugKotlin` with output under generated/ksp/demoDebug/, works unconfigured).
          else -> {
            val variant = androidVariant(this, ext)
            wire(
              this,
              ext,
              kspTask = "ksp${variant.replaceFirstChar { it.uppercase() }}Kotlin",
              kspManifestPath = "generated/ksp/$variant/resources/nav-graph.json",
              render = wantRender,
              variant = variant,
            )
          }
        }
      }
    }
  }

  /** The Android variant to extract: an explicit `navgraph { variant }`, else the first `…DebugKotlin` KSP task's
   *  variant (so flavored projects work unconfigured), else `"debug"`. */
  private fun androidVariant(project: Project, ext: NavGraphExtension): String {
    val explicit = ext.variant.get()
    if (explicit.isNotBlank()) return explicit
    return project.tasks.names
      .filter { it.startsWith("ksp") && it.endsWith("DebugKotlin") && "Test" !in it }
      .minOrNull()
      ?.removePrefix("ksp")?.removeSuffix("Kotlin")?.replaceFirstChar { it.lowercase() }
      ?: "debug"
  }

  /** The plugin's own version, read from the generated `/navgraph.version` classpath resource (falls back to a sane
   *  default if absent). Used so the auto-added annotations + processor always match the applied plugin. */
  private fun navgraphVersion(): String =
    NavGraphGradlePlugin::class.java.getResourceAsStream("/navgraph.version")
      ?.bufferedReader()?.use { it.readText().trim() }
      ?.takeIf(String::isNotEmpty)
      ?: "0.1.0"

  /** Auto-adds the matching `compose-nav-graph-annotations` (to commonMain for KMP, else `implementation`) + `compose-nav-graph-testing`
   *  (to the Android unit-test classpath, for the Robolectric backend) so a consumer only applies the plugin;
   *  toggle with `navgraph { autoDependencies = false }`. The KSP processor itself is wired separately + eagerly in
   *  [wireNavGraphKspProcessor] (afterEvaluate is too late for KSP's classpath snapshot, for every module type). The
   *  KSP plugin must be applied — its version is pinned to the consumer's Kotlin version, so navgraph can't apply it;
   *  if it's missing we fail clearly. */
  private fun autoWireDependencies(project: Project, ext: NavGraphExtension) {
    with(project) {
      val v = navgraphVersion()
      val kmp = plugins.hasPlugin(KMP_PLUGIN)
      val android = ANDROID_PLUGINS.any { plugins.hasPlugin(it) }

      val annotationsCfg = if (kmp) "commonMainImplementation" else "implementation"
      if (configurations.findByName(annotationsCfg) != null) {
        dependencies.add(annotationsCfg, "com.github.skydoves:compose-nav-graph-annotations:$v")
      }

      // The Robolectric render backend runs on the Android unit-test classpath. compose-nav-graph-testing's transitive `api`
      // deps pull robolectric + compose-ui-test + ui-tooling + activity-compose + junit, so adding just
      // compose-nav-graph-testing equips the generated render test. KMP routes it to the `androidUnitTest` source set's
      // implementation config; plain-Android to `testImplementation`. Exclude compose-nav-graph-testing's Compose BOM so it
      // can't pin the consumer's androidx Compose versions — a Compose-Multiplatform consumer maps
      // `org.jetbrains.compose.*` onto `androidx.compose.*` at its OWN (often alpha) versions, and the BOM would
      // otherwise upgrade e.g. material3 to a stable release whose API differs from what the screens compiled
      // against (NoSuchMethodError at render). Preferring the consumer's versions is the right call here.
      val robolectric = ext.renderBackend.get() != RenderBackend.LAYOUTLIB
      if (robolectric && android && ext.renderThumbnails.get()) {
        val testCfg = if (kmp) "androidUnitTestImplementation" else "testImplementation"
        if (configurations.findByName(testCfg) != null) {
          (dependencies.add(testCfg, "com.github.skydoves:compose-nav-graph-testing:$v") as? ModuleDependency)
            ?.exclude(mapOf("group" to "androidx.compose", "module" to "compose-bom"))
        }
        // The render launches ComponentActivity via the compose test rule, which resolves it from the consumer's
        // MAIN debug manifest (not the unit-test one). ui-test-manifest is a manifest-only artifact that declares
        // ComponentActivity; adding it to the debug-main classpath lets the render run without the consumer
        // hand-declaring that activity. Manifest-only ⇒ no API/version skew with the consumer's Compose.
        val debugCfg = listOf("androidDebugImplementation", "debugImplementation")
          .firstOrNull { configurations.findByName(it) != null }
        if (debugCfg != null) {
          dependencies.add(debugCfg, "androidx.compose.ui:ui-test-manifest:1.11.2")
        }
      }

      if (!pluginManager.hasPlugin("com.google.devtools.ksp")) {
        logger.error(
          "navgraph: the KSP plugin 'com.google.devtools.ksp' is not applied, so navgraph can't " +
            "wire its processor. Apply KSP (its version must match your Kotlin version), " +
            "or set navgraph { autoDependencies = false } and add " +
            "'com.github.skydoves:compose-nav-graph-ksp' to your KSP configuration yourself.",
        )
        return
      }
      // The KSP processor itself is wired eagerly in apply() (see wireNavGraphKspProcessor) — afterEvaluate is too
      // late for KSP's classpath snapshot, for every module type.
    }
  }

  /** The (KSP task, KSP manifest path) for a KMP + Android module, selected by the Android DSL actually in use:
   *  - **new** `com.android.kotlin.multiplatform.library` (`androidLibrary {}`) emits a single `kspAndroidMain` whose
   *    manifest lands under `generated/ksp/android/androidMain/`.
   *  - **legacy** `com.android.library` + `kotlin { androidTarget() }` emits per-variant `ksp<V>KotlinAndroid` (e.g.
   *    `kspDebugKotlinAndroid`) whose manifest lands under `generated/ksp/android/android<V>/` (e.g. `androidDebug`).
   *  Detected by task presence (tasks are registered by `afterEvaluate`): prefer the new-DSL `kspAndroidMain`, else
   *  fall back to the first legacy `ksp…DebugKotlinAndroid` variant (mirroring [androidVariant]'s debug heuristic). */
  private fun kmpAndroidKsp(project: Project): Pair<String, String> {
    if (project.tasks.names.contains(KMP_ANDROID_KSP_TASK)) {
      return KMP_ANDROID_KSP_TASK to
        KMP_ANDROID_MANIFEST
    }
    val cap = legacyKmpAndroidVariant(project).replaceFirstChar { it.uppercase() }
    return "ksp${cap}KotlinAndroid" to
      "generated/ksp/android/android$cap/resources/nav-graph.json"
  }

  /** Adds `compose-nav-graph-ksp` to the consumer's KSP configuration via an eager reaction at config-creation time — it MUST
   *  run before KSP snapshots its processor classpath in its own `afterEvaluate` (KSP applies before this plugin),
   *  so adding it in [autoWireDependencies]'s `afterEvaluate` is too late: the dependency lands in the config but
   *  never reaches the KSP task, which is then SKIPPED with no manifest. This bites plain-Android (`ksp`) and KMP +
   *  Android (`kspAndroid…`) alike, so every KSP config is wired here (see [isNavGraphKspConfig]). Gated per-fire on
   *  `autoDependencies` so a `navgraph { autoDependencies = false }` set in the `navgraph { }` block still wins. */
  private fun wireNavGraphKspProcessor(project: Project, ext: NavGraphExtension) {
    project.configurations.configureEach {
      if (isNavGraphKspConfig(name) && ext.autoDependencies.get()) {
        dependencies.add(
          project.dependencies.create("com.github.skydoves:compose-nav-graph-ksp:${navgraphVersion()}"),
        )
      }
    }
  }

  /** Inject the consumer's Gradle module path into KSP as `navgraph.module` so the processor can group preview-gallery
   *  previews by module (KSP can't see Gradle module identity itself). Done reflectively via the `ksp { arg() }`
   *  extension so compose-nav-graph-gradle needs no compile dependency on the KSP Gradle plugin; KSP applies before this plugin,
   *  so the extension already exists when the reaction fires (set well before the KSP task reads its arguments). */
  private fun wireKspModuleArg(project: Project) {
    project.pluginManager.withPlugin("com.google.devtools.ksp") {
      val kspExt = project.extensions.findByName("ksp") ?: return@withPlugin
      runCatching {
        kspExt.javaClass.getMethod("arg", String::class.java, String::class.java)
          .invoke(kspExt, "navgraph.module", project.path)
      }.onFailure {
        project.logger.warn(
          "navgraph: could not pass the module path to KSP (${it.message}); the preview gallery will " +
            "group previews without module identity (single-module mode).",
        )
      }
    }
  }

  /** The KSP configurations navgraph wires its processor into: plain-Android/JVM base `ksp` (which propagates to its
   *  variants), KMP-only `kspCommonMainMetadata`, and KMP + Android debug — new-DSL `kspAndroidMain` or legacy
   *  per-variant `kspAndroid<Variant>Debug` (e.g. `kspAndroidDebug`, `kspAndroidDemoDebug`). Release / unit-test
   *  Android configs are excluded — navgraph extracts the debug variant only. */
  private fun isNavGraphKspConfig(name: String): Boolean = name == "ksp" ||
    name == "kspCommonMainMetadata" ||
    name == KMP_ANDROID_KSP_TASK ||
    name.matches(Regex("kspAndroid.*Debug"))

  /** The legacy KMP + Android KSP variant (e.g. `debug`), from the first `ksp<V>DebugKotlinAndroid` task. */
  private fun legacyKmpAndroidVariant(project: Project): String = project.tasks.names
    .filter { it.startsWith("ksp") && it.endsWith("DebugKotlinAndroid") && "Test" !in it }
    .minOrNull()
    ?.removePrefix("ksp")?.removeSuffix("KotlinAndroid")?.replaceFirstChar { it.lowercase() }
    ?: "debug"

  /** The `com.android.tools.layoutlib:layoutlib-runtime` native classifier for the host running Gradle. */
  private fun layoutlibOsClassifier(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
      "mac" in os || "darwin" in os -> if ("aarch64" in arch || "arm" in arch) "mac-arm" else "mac"
      "win" in os -> "win"
      else -> "linux"
    }
  }

  /** The module's resource namespace (`android { namespace }`), read reflectively so `compose-nav-graph-gradle` needs no AGP
   *  compile dependency. The renderer uses it to resolve the app's R class. */
  private fun Project.androidNamespace(): String? =
    extensions.findByName("android")?.let { android ->
      runCatching {
        android.javaClass.getMethod("getNamespace").invoke(android) as? String
      }.getOrNull()
    }

  /** The KMP-Android module namespace (`kotlin { androidLibrary { namespace } }`), read off the android target
   *  reflectively (no KGP/AGP compile dependency). The new `androidLibrary {}` DSL registers no top-level `android`
   *  extension, so [androidNamespace] can't see it; the android Kotlin target carries `getNamespace()` instead. */
  private fun Project.kmpAndroidNamespace(): String? {
    val kotlin = extensions.findByName("kotlin") ?: return null
    return runCatching {
      val targets = kotlin.javaClass.getMethod("getTargets").invoke(kotlin) as Iterable<*>
      targets.firstNotNullOfOrNull { target ->
        target?.let {
          runCatching { it.javaClass.getMethod("getNamespace").invoke(it) as? String }.getOrNull()
        }
          ?.takeIf(String::isNotBlank)
      }
    }.getOrNull()
  }

  /** Registers the pipeline tasks for the detected variant (Android Layoutlib render vs KMP structure-only). */
  private fun wire(
    project: Project,
    ext: NavGraphExtension,
    kspTask: String,
    kspManifestPath: String,
    render: Boolean,
    variant: String? = null,
    kmp: Boolean = false,
  ) {
    with(project) {
      val navgraphDir = layout.buildDirectory.dir("navgraph")
      val thumbsDir = navgraphDir.map { it.dir("thumbs") }
      val kspManifestFile = layout.buildDirectory.file(kspManifestPath)

      // The preview gallery is a PARALLEL pipeline writing to build/navgallery/ (vs the nav graph's
      // build/navgraph/). It reuses the same Layoutlib + Robolectric render engine via separate task instances,
      // pointed at the KSP-emitted preview-gallery.json. On-demand only (never wired into generateNavGraph/check).
      val galleryEnabled = ext.galleryEnabled.get()
      val galleryManifestFile = layout.buildDirectory.file(
        kspManifestPath.replace("nav-graph.json", "preview-gallery.json"),
      )
      val galleryDir = layout.buildDirectory.dir("navgallery")
      val galleryThumbsDir = galleryDir.map { it.dir("thumbs") }
      val previewIndexFile = navgraphDir.map { it.file("preview-index.txt") }
      val galleryPreviewIndexFile = galleryDir.map { it.file("preview-index.txt") }

      // Layoutlib runs for AUTO + LAYOUTLIB (not ROBOLECTRIC, which skips the device-free pass entirely).
      val doRender = render && ext.renderBackend.get() != RenderBackend.ROBOLECTRIC &&
        (variant != null || kmp)
      val doGalleryRender = render && galleryEnabled &&
        ext.galleryRenderBackend.get() != RenderBackend.ROBOLECTRIC && (variant != null || kmp)

      // The Layoutlib renderer + framework (renderer configs + the prepare task) are shared by both pipelines,
      // created ONCE when either renders. Each render is a LayoutlibRenderTask instance pointed at its OWN
      // manifest / scratch workDir / thumbs / index (the consumer adds nothing beyond the plugin + @Preview).
      val layoutlib = if (doRender ||
        doGalleryRender
      ) {
        prepareLayoutlib(this, variant, kmp)
      } else {
        null
      }
      val renderLayoutlib = if (doRender && layoutlib != null) {
        registerLayoutlibRender(
          this, "renderNavGraphLayoutlib", variant, kmp, kspTask, layoutlib, kspManifestFile,
          layout.buildDirectory.dir("navgraph-render"), thumbsDir, previewIndexFile,
        )
      } else {
        null
      }
      val renderGalleryLayoutlib = if (doGalleryRender && layoutlib != null) {
        registerLayoutlibRender(
          this, "renderNavGraphGalleryLayoutlib", variant, kmp, kspTask, layoutlib, galleryManifestFile,
          layout.buildDirectory.dir(
            "navgraph-gallery-render",
          ),
          galleryThumbsDir, galleryPreviewIndexFile,
        )
      } else {
        null
      }

      // Robolectric backend (AUTO / ROBOLECTRIC): render the (remaining) previews on the Android unit-test
      // classpath under Robolectric's native graphics, appending to each pipeline's own thumbs/ + preview-index.
      // BOTH pipelines share ONE unit-test run via indexed jobs; AUTO renders only what Layoutlib failed,
      // ROBOLECTRIC renders everything (no Layoutlib pass).
      val doRobolectric = render && ext.renderBackend.get() != RenderBackend.LAYOUTLIB &&
        (variant != null || kmp)
      val doGalleryRobolectric = render && galleryEnabled &&
        ext.galleryRenderBackend.get() != RenderBackend.LAYOUTLIB && (variant != null || kmp)
      val roboSpecs = buildList {
        if (doRobolectric) {
          add(
            RoboSpec(
              key = "nav",
              nameInfix = "",
              backend = ext.renderBackend.map { it.name },
              kspManifest = kspManifestFile,
              layoutlibWorkDir = layout.buildDirectory.dir("navgraph-render"),
              thumbsDir = thumbsDir,
              previewIndex = previewIndexFile,
              renderLayoutlib = renderLayoutlib,
            ),
          )
        }
        if (doGalleryRobolectric) {
          add(
            RoboSpec(
              key = "gallery",
              nameInfix = "Gallery",
              backend = ext.galleryRenderBackend.map { it.name },
              kspManifest = galleryManifestFile,
              layoutlibWorkDir = layout.buildDirectory.dir("navgraph-gallery-render"),
              thumbsDir = galleryThumbsDir,
              previewIndex = galleryPreviewIndexFile,
              renderLayoutlib = renderGalleryLayoutlib,
            ),
          )
        }
      }
      val roboAnchors = if (roboSpecs.isNotEmpty()) {
        wireRobolectric(this, kspTask, roboSpecs, variant, kmp, ext.robolectricApplication.get())
      } else {
        emptyMap()
      }
      val renderRobolectric = roboAnchors["nav"]
      val renderGalleryRobolectric = roboAnchors["gallery"]

      // (b) Merge the KSP manifest (+ thumbnails, when rendered) into the consumed manifest.
      val merge = tasks.register("mergeNavGraph", MergeNavGraphTask::class.java) {
        this.kspManifest.set(kspManifestFile)
        if (doRender || doRobolectric) previewIndex.set(previewIndexFile)
        outputManifest.set(navgraphDir.map { it.file("nav-graph.json") })
        dependsOn(kspTask)
        if (renderLayoutlib != null) dependsOn(renderLayoutlib)
        if (renderRobolectric != null) dependsOn(renderRobolectric)
      }

      // (b-gallery) Merge the gallery manifest (+ its thumbnails) the same way, into build/navgallery/.
      val galleryMerge = if (galleryEnabled) {
        tasks.register("mergeNavGallery", MergeNavGraphTask::class.java) {
          this.kspManifest.set(galleryManifestFile)
          if (doGalleryRender || doGalleryRobolectric) previewIndex.set(galleryPreviewIndexFile)
          outputManifest.set(galleryDir.map { it.file("preview-gallery.json") })
          dependsOn(kspTask)
          if (renderGalleryLayoutlib != null) dependsOn(renderGalleryLayoutlib)
          if (renderGalleryRobolectric != null) dependsOn(renderGalleryRobolectric)
        }
      } else {
        null
      }

      // (b2) Publish this module's merged nav-graph (nav-graph.json + thumbs/) as a consumable artifact so an
      // umbrella module that depends on it — with navgraph { aggregate = true } — can pull and merge it. The whole
      // navgraph dir is the artifact, built by the merge (which transitively runs the renders that fill thumbs/).
      configurations.create(NAVGRAPH_GRAPH_CONFIGURATION).apply {
        isCanBeConsumed = true
        isCanBeResolved = false
        attributes.attribute(NAVGRAPH_GRAPH_ATTRIBUTE, NAVGRAPH_GRAPH_VALUE)
        outgoing.artifact(navgraphDir) { builtBy(merge) }
      }

      // (b3) Cross-module aggregation, ON by default (most apps are multi-module). A module
      // merges every dependency module's nav-graph + thumbnails with its own into one graph:
      // navgraph's KSP runs per module, so a cross-module @NavEdge target is a no-preview stub in
      // the declaring module's graph until aggregation re-unites it with the real node from
      // the owning module. An umbrella (:app, on every feature) thus gets the whole app's
      // graph; a single-module app just gets its own. Plain-Android only; skipped gracefully
      // (own graph) when the variant has no runtime classpath.
      val runtimeClasspath = if (ext.aggregate.get() && !kmp && variant != null) {
        configurations.findByName("${variant}RuntimeClasspath")
      } else {
        null
      }
      val aggregatedDir = layout.buildDirectory.dir("navgraph-aggregated")
      val aggregateTask = if (runtimeClasspath != null) {
        // Re-select each dependency's navgraph graph artifact off the already-resolved runtime classpath (so Android
        // variant attributes stay correct), leniently skipping deps that produce no nav graph (JaCoCo-style).
        val depGraphDirs = runtimeClasspath.incoming.artifactView {
          withVariantReselection()
          lenient(true)
          componentFilter { it is ProjectComponentIdentifier }
          attributes.attribute(NAVGRAPH_GRAPH_ATTRIBUTE, NAVGRAPH_GRAPH_VALUE)
        }.files
        tasks.register("aggregateNavGraph", AggregateNavGraphTask::class.java) {
          group = "navgraph"
          description =
            "Merges this module's own + its dependency modules' nav-graphs into one graph."
          dependencyGraphDirs.from(depGraphDirs)
          ownManifest.set(navgraphDir.map { it.file("nav-graph.json") })
          ownThumbs.from(navgraphDir.map { it.dir("thumbs") })
          outputDir.set(aggregatedDir)
          manifestFileName.set("nav-graph.json")
          dependsOn(merge)
        }
      } else {
        null
      }

      // Exports + umbrella read the combined graph when aggregating, this module's own otherwise; the PNG/HTML are
      // always written to build/navgraph/ (no overlap with the aggregate task's build/navgraph-aggregated/ output).
      val graphSourceDir = if (aggregateTask != null) aggregatedDir else navgraphDir
      val graphProducer: TaskProvider<*> = aggregateTask ?: merge

      // (c) Umbrella entry point.
      tasks.register("generateNavGraph") {
        group = "navgraph"
        description = "Extracts the navgraph graph (+ thumbnails " +
          "on Android) and writes build/navgraph/nav-graph.json."
        dependsOn(graphProducer)
      }

      // (d) On-demand HTML export. -Pnavgraph.export.device=WxH frames thumbnails; -Pnavgraph.export.out=<path> redirects.
      tasks.register("exportNavGraphHtml", ExportNavGraphHtmlTask::class.java) {
        group = "navgraph"
        description = "Renders build/navgraph/nav-graph.html " +
          "— a self-contained, interactive flow graph."
        manifest.set(graphSourceDir.map { it.file("nav-graph.json") })
        this.thumbsDir.set(graphSourceDir.map { it.dir("thumbs") })
        deviceSpec.set(providers.gradleProperty("navgraph.export.device").orElse(""))
        outputHtml.set(
          layout.file(providers.gradleProperty("navgraph.export.out").map { File(it) })
            .orElse(navgraphDir.map { it.file("nav-graph.html") }),
        )
        dependsOn(graphProducer)
      }

      // (d2) On-demand PNG export. -Pnavgraph.export.scale=N for hi-DPI; same device/out props as the HTML export.
      tasks.register("exportNavGraphImage", ExportNavGraphImageTask::class.java) {
        group = "navgraph"
        description = "Renders build/navgraph/nav-graph.png — a static image of the flow graph."
        manifest.set(graphSourceDir.map { it.file("nav-graph.json") })
        this.thumbsDir.set(graphSourceDir.map { it.dir("thumbs") })
        deviceSpec.set(providers.gradleProperty("navgraph.export.device").orElse(""))
        scale.set(providers.gradleProperty("navgraph.export.scale").map { it.toInt() })
        outputImage.set(
          layout.file(providers.gradleProperty("navgraph.export.out").map { File(it) })
            .orElse(navgraphDir.map { it.file("nav-graph.png") }),
        )
        dependsOn(graphProducer)
      }

      // (gallery) Publish + aggregate + export the preview gallery, mirroring the nav-graph pipeline but on
      // build/navgallery/ and a SEPARATE consumable attribute VALUE (nav-gallery), so a dependency's gallery is
      // re-selected independently of its nav-graph. On-demand only — never wired into generateNavGraph / check.
      if (galleryEnabled && galleryMerge != null) {
        configurations.create(NAVGRAPH_GALLERY_CONFIGURATION).apply {
          isCanBeConsumed = true
          isCanBeResolved = false
          attributes.attribute(NAVGRAPH_GRAPH_ATTRIBUTE, NAVGRAPH_GALLERY_VALUE)
          outgoing.artifact(galleryDir) { builtBy(galleryMerge) }
        }

        val galleryRuntimeClasspath = if (ext.galleryAggregate.get() && !kmp && variant != null) {
          configurations.findByName("${variant}RuntimeClasspath")
        } else {
          null
        }
        val galleryAggregatedDir = layout.buildDirectory.dir("navgallery-aggregated")
        val galleryAggregate = if (galleryRuntimeClasspath != null) {
          val depGalleryDirs = galleryRuntimeClasspath.incoming.artifactView {
            withVariantReselection()
            lenient(true)
            componentFilter { it is ProjectComponentIdentifier }
            attributes.attribute(NAVGRAPH_GRAPH_ATTRIBUTE, NAVGRAPH_GALLERY_VALUE)
          }.files
          tasks.register("aggregateNavGallery", AggregateNavGraphTask::class.java) {
            group = "navgraph"
            description =
              "Merges this module's own + its dependency modules' preview galleries into one."
            dependencyGraphDirs.from(depGalleryDirs)
            ownManifest.set(galleryDir.map { it.file("preview-gallery.json") })
            ownThumbs.from(galleryDir.map { it.dir("thumbs") })
            outputDir.set(galleryAggregatedDir)
            manifestFileName.set("preview-gallery.json")
            dependsOn(galleryMerge)
          }
        } else {
          null
        }

        val gallerySourceDir = if (galleryAggregate != null) galleryAggregatedDir else galleryDir
        val galleryProducer: TaskProvider<*> = galleryAggregate ?: galleryMerge

        tasks.register("generatePreviewGallery") {
          group = "navgraph"
          description =
            "Renders every @Preview thumbnail and writes build/navgallery/preview-gallery.json."
          dependsOn(galleryProducer)
        }

        tasks.register("exportPreviewGalleryHtml", ExportPreviewGalleryHtmlTask::class.java) {
          group = "navgraph"
          description =
            "Renders build/navgallery/preview-gallery.html — a self-contained gallery " +
            "of every @Preview, grouped by module and package."
          manifest.set(gallerySourceDir.map { it.file("preview-gallery.json") })
          this.thumbsDir.set(gallerySourceDir.map { it.dir("thumbs") })
          outputHtml.set(
            layout.file(providers.gradleProperty("navgraph.gallery.out").map { File(it) })
              .orElse(galleryDir.map { it.file("preview-gallery.html") }),
          )
          dependsOn(galleryProducer)
        }

        // (gallery PNG) On-demand static image. -Pnavgraph.export.scale=N for hi-DPI; -Pnavgraph.gallery.out=<path>
        // redirects. The image parity of exportPreviewGalleryHtml (a grid grouped by module then package).
        tasks.register("exportPreviewGalleryImage", ExportPreviewGalleryImageTask::class.java) {
          group = "navgraph"
          description =
            "Renders build/navgallery/preview-gallery.png — a static grid image of every @Preview."
          manifest.set(gallerySourceDir.map { it.file("preview-gallery.json") })
          this.thumbsDir.set(gallerySourceDir.map { it.dir("thumbs") })
          scale.set(providers.gradleProperty("navgraph.export.scale").map { it.toInt() })
          outputImage.set(
            layout.file(providers.gradleProperty("navgraph.gallery.out").map { File(it) })
              .orElse(galleryDir.map { it.file("preview-gallery.png") }),
          )
          dependsOn(galleryProducer)
        }
      }

      // (e) Navigation baseline (.nav) — reads the KSP manifest directly (structure only, render-free).
      tasks.register("navDump", NavDumpTask::class.java) {
        group = "navgraph"
        description = "Writes the committed nav baseline (.nav) from the current graph."
        manifest.set(kspManifestFile)
        baseline.set(ext.baselineFile)
        dependsOn(kspTask)
      }
      val navCheck = tasks.register("navCheck", NavCheckTask::class.java) {
        group = "navgraph"
        description = "Fails if the navigation graph drifted from the committed .nav baseline."
        manifest.set(kspManifestFile)
        baseline.set(ext.baselineFile)
        failOnNavChange.set(ext.failOnNavChange)
        allowMissingBaseline.set(ext.allowMissingBaseline)
        dependsOn(kspTask)
      }
      // Gate `check` on the baseline (Android app/library + KMP all apply the `base` plugin → `check` exists).
      plugins.withId("base") { tasks.named("check") { dependsOn(navCheck) } }
    }
  }

  /**
   * Wires the Robolectric render for one or more pipelines (the nav graph and/or the preview gallery): generate
   * the one-line `NavGraphRobolectricRenderTest : NavPreviewRenderTestBase()` ONCE into the unit-test Kotlin
   * compilation, register each pipeline's `prepare…RobolectricRenderList` TSV task, and drive the consumer's ONE
   * `test<Variant>UnitTest` — filtered (at taskGraph-ready, only when a render is requested) to run JUST the
   * generated test, which renders every pipeline's Layoutlib failures as INDEXED jobs in a single JVM (so a
   * normal test run is untouched and the two pipelines never run the AGP test twice). Returns each spec's render
   * anchor keyed by [RoboSpec.key] so the merges can depend on them.
   */
  private fun wireRobolectric(
    project: Project,
    kspTask: String,
    specs: List<RoboSpec>,
    variant: String?,
    kmp: Boolean,
    robolectricApplication: String,
  ): Map<String, TaskProvider<*>> {
    with(project) {
      // The variant whose unit-test compilation hosts the render: the resolved Android variant for plain-Android;
      // `debug` for KMP+Android (its androidMain previews compile into the debugUnitTest classpath).
      val testVariant = if (kmp) "debug" else requireNotNull(variant)
      val cap = testVariant.replaceFirstChar { it.uppercase() }
      val unitTestTask = "test${cap}UnitTest"
      // The Kotlin source set hosting the generated render test. KMP+Android (androidTarget) → `androidUnitTest`.
      // Plain-Android varies by AGP: newer AGP names it per-variant (`<variant>UnitTest`); older AGP exposes a
      // single `test`. Try per-variant, then `test`.
      val testSourceSets = if (kmp) {
        listOf("androidUnitTest")
      } else {
        listOf("${testVariant}UnitTest", "test")
      }

      // Some Android DSLs (e.g. the new `androidLibrary {}` KMP plugin) expose no `test<Variant>UnitTest` task, so
      // the Robolectric render has nowhere to run. Skip gracefully — a missing task would otherwise throw
      // UnknownTaskException and abort configuration. Layoutlib still renders in AUTO.
      if (unitTestTask !in tasks.names) {
        logger.warn(
          "navgraph: Robolectric render unavailable for ':$name' — no '$unitTestTask' " +
            "unit-test task in this Android DSL. Layoutlib-only previews still render.",
        )
        return emptyMap()
      }

      val genDir = layout.buildDirectory.dir("generated/navgraph/robolectric").get().asFile
      writeRobolectricTest(genDir, robolectricApplication)
      addKotlinSrcDir(this, testSourceSets, genDir)

      // The render runs INSIDE the consumer's own `test<Variant>UnitTest` (an AGP `AndroidUnitTest`): only AGP
      // fully values that task's classpath / merged-resources `.ap_` / R closure when it runs the task itself —
      // reading those from a separate task throws `MissingValueException`, and reconstructing them desynchronizes
      // the R-id ↔ `.ap_` ↔ class versions. So each `renderNavGraph…Robolectric` is a thin anchor on the AGP test;
      // the filter + indexed navgraph sysprops are applied at taskGraph-ready, ONLY when a render is requested.
      val agpTest = tasks.named(unitTestTask, Test::class.java)

      data class Wired(
        val spec: RoboSpec,
        val renderListFile: Provider<RegularFile>,
        val anchor: TaskProvider<*>,
      )
      val wired = specs.map { spec ->
        val renderListFile = spec.layoutlibWorkDir.map { it.file("robolectric-render-list.tsv") }
        val prepare = tasks.register(
          "prepareNavGraph${spec.nameInfix}RobolectricRenderList",
          RobolectricRenderListTask::class.java,
        ) {
          kspManifest.set(spec.kspManifest)
          backend.set(spec.backend)
          layoutlibWorkDir.set(spec.layoutlibWorkDir)
          renderList.set(renderListFile)
          previewIndex.set(spec.previewIndex)
          dependsOn(kspTask)
          spec.renderLayoutlib?.let { dependsOn(it) }
        }
        agpTest.configure { mustRunAfter(prepare) }
        val anchor = tasks.register("renderNavGraph${spec.nameInfix}Robolectric") {
          group = "navgraph"
          description =
            "Renders @Preview thumbnails via the consumer's Robolectric unit-test task."
          dependsOn(prepare, agpTest)
          spec.renderLayoutlib?.let { dependsOn(it) }
        }
        Wired(spec, renderListFile, anchor)
      }

      // ONE shared reaction configures the single AGP test with the indexed jobs of whichever pipelines are
      // actually in this task graph; a plain `./gradlew test<V>UnitTest` matches none and is untouched. Adds NO
      // new taskGraph hooks beyond this one (config-cache parity with the original single-pipeline wiring).
      gradle.taskGraph.whenReady {
        val active = wired.filter { hasTask(it.anchor.get()) }
        if (active.isEmpty()) return@whenReady
        agpTest.get().apply {
          filter {
            includeTestsMatching("*NavGraphRobolectricRenderTest")
            // The generated render test can be legitimately absent (a module layout whose unit-test Kotlin source
            // set isn't named `test`/`androidUnitTest`), so "no matching test" must not fail the build here.
            isFailOnNoMatchingTests = false
          }
          systemProperty("navgraph.jobCount", active.size.toString())
          active.forEachIndexed { i, w ->
            systemProperty("navgraph.renderList.$i", w.renderListFile.get().asFile.absolutePath)
            systemProperty("navgraph.thumbsDir.$i", w.spec.thumbsDir.get().asFile.absolutePath)
            systemProperty("navgraph.previewIndex.$i", w.spec.previewIndex.get().asFile.absolutePath)
          }
          // Run iff ANY active pipeline has a non-empty render list (a Layoutlib failure to fill somewhere).
          onlyIf {
            active.any { w ->
              w.renderListFile.get().asFile.let { it.isFile && it.readText().isNotBlank() }
            }
          }
          // Force the render to run when requested (the prior thumbnails aren't a tracked output of the test).
          outputs.upToDateWhen { false }
        }
      }

      return wired.associate { it.spec.key to it.anchor }
    }
  }

  /** One Robolectric render pipeline (the nav graph or the preview gallery): its manifest, its Layoutlib scratch
   *  dir (whose `results.json` AUTO reads to find failures), and its output thumbs/index. [nameInfix]
   *  differentiates task names (`""` → `renderNavGraphRobolectric`, `"Gallery"` → `renderNavGraphGalleryRobolectric`). */
  private data class RoboSpec(
    val key: String,
    val nameInfix: String,
    val backend: Provider<String>,
    val kspManifest: Provider<RegularFile>,
    val layoutlibWorkDir: Provider<Directory>,
    val thumbsDir: Provider<Directory>,
    val previewIndex: Provider<RegularFile>,
    val renderLayoutlib: TaskProvider<LayoutlibRenderTask>?,
  )

  /** The Layoutlib renderer classpath + the prepare task, created once and shared by both render pipelines. */
  private class LayoutlibSetup(
    val prepare: TaskProvider<PrepareLayoutlibTask>,
    val renderer: Configuration,
  )

  /** Create the SHARED Layoutlib renderer/runtime/resources configs + the prepare task ONCE (idempotent
   *  maybeCreate), validating the plain-Android variant has a runtime classpath. Both render pipelines reuse it. */
  private fun prepareLayoutlib(project: Project, variant: String?, kmp: Boolean): LayoutlibSetup {
    with(project) {
      // Plain Android: fail fast + clearly if the resolved variant has no runtime classpath — e.g. the bare
      // "debug" fallback (or a hand-set navgraph { variant }) on a flavored module whose only variants are
      // demoDebug/fullDebug. (KMP renders off the androidMain compilation, not a named variant.)
      if (!kmp) {
        val v = requireNotNull(variant)
        configurations.findByName("${v}RuntimeClasspath")
          ?: error(
            "navgraph: no '${v}RuntimeClasspath' configuration — set navgraph { " +
              "variant } to a real variant of this module (e.g. \"demoDebug\").",
          )
      }
      // The renderer's own classpath: the standalone preview renderer + the Layoutlib framework classes.
      val renderer = configurations.maybeCreate("navgraphLayoutlibRenderer").apply {
        isCanBeConsumed = false
        isCanBeResolved = true
      }
      dependencies.add(
        renderer.name,
        "com.android.tools.compose:compose-preview-renderer:$LAYOUTLIB_RENDERER_VERSION",
      )
      dependencies.add(renderer.name, "com.android.tools.layoutlib:layoutlib:$LAYOUTLIB_VERSION")
      // The Layoutlib data dir is assembled from the OS-native runtime jar + the framework resources jar.
      val runtimeCfg = configurations.maybeCreate("navgraphLayoutlibRuntime").apply {
        isCanBeConsumed = false
        isCanBeResolved = true
      }
      dependencies.add(
        runtimeCfg.name,
        "com.android.tools.layoutlib:layoutlib-runtime:" +
          "$LAYOUTLIB_VERSION:${layoutlibOsClassifier()}",
      )
      val resourcesCfg = configurations.maybeCreate("navgraphLayoutlibResources").apply {
        isCanBeConsumed = false
        isCanBeResolved = true
      }
      dependencies.add(
        resourcesCfg.name,
        "com.android.tools.layoutlib:layoutlib-resources:$LAYOUTLIB_VERSION",
      )
      val prepare = tasks.register("prepareNavGraphLayoutlib", PrepareLayoutlibTask::class.java) {
        runtimeJar.from(runtimeCfg)
        resourcesJar.from(resourcesCfg)
        layoutlibDir.set(layout.buildDirectory.dir("navgraph-layoutlib"))
      }
      return LayoutlibSetup(prepare, renderer)
    }
  }

  /** Register + configure a [LayoutlibRenderTask] instance: the shared renderer/prepare from [setup], pointed at
   *  this pipeline's [manifestFile] / scratch [scratchDir] / [thumbsOut] / [indexOut], plus the variant's
   *  app/project/R classpath + linked resources (identical for both pipelines — same module/variant). KMP feeds
   *  the consuming app's resources via [wireKmpConsumerResources]. */
  private fun registerLayoutlibRender(
    project: Project,
    name: String,
    variant: String?,
    kmp: Boolean,
    kspTaskName: String,
    setup: LayoutlibSetup,
    manifestFile: Provider<RegularFile>,
    scratchDir: Provider<Directory>,
    thumbsOut: Provider<Directory>,
    indexOut: Provider<RegularFile>,
  ): TaskProvider<LayoutlibRenderTask> {
    with(project) {
      val artifactType = Attribute.of("artifactType", String::class.java)
      val renderTask = tasks.register(name, LayoutlibRenderTask::class.java) {
        kspManifest.set(manifestFile)
        rendererClasspath.from(setup.renderer)
        layoutlibDir.set(setup.prepare.flatMap { it.layoutlibDir })
        layoutlibVersion.set(LAYOUTLIB_VERSION)
        namespace.set((if (kmp) kmpAndroidNamespace() else androidNamespace()) ?: "")
        apiLevel.set(LAYOUTLIB_API)
        workDir.set(scratchDir)
        this.thumbsDir.set(thumbsOut)
        previewIndex.set(indexOut)
        dependsOn(kspTaskName, setup.prepare)

        if (kmp) {
          // KMP + Android (com.android.kotlin.multiplatform.library): render off the androidMain compilation.
          // Classes live in classes/kotlin/android/main; Compose-Multiplatform resources are merged under
          // intermediates/assets/androidMain/mergeAndroidMainAssets (which holds composeResources/ on the
          // classpath so Res.* loads at render). The app classpath is the androidMain runtime as
          // android-classes-jars. The R closure + linked .ap_ come from the consuming app (wired below, once
          // all projects evaluate) — a KMP library has no AAPT2-linked R/.ap_ of its own.
          //
          // `androidRuntimeClasspath` exists only on the NEW `com.android.kotlin.multiplatform.library`
          // (`androidLibrary {}`) DSL. A module on the OLD `com.android.library` + `kotlin { androidTarget() }`
          // setup names its runtime per-variant (`debugRuntimeClasspath`, …) and has no `androidRuntimeClasspath`,
          // so `getByName` there would throw here at task creation. Look it up tolerantly: feed the app classpath
          // when present, else warn (matching the missing-consumer message) and skip — the graph still generates;
          // thumbnails are skipped rather than failing the whole build.
          val androidRuntime = configurations.findByName("androidRuntimeClasspath")
            ?: configurations.findByName("debugRuntimeClasspath")
          if (androidRuntime != null) {
            appClasspath.from(
              androidRuntime.incoming
                .artifactView { attributes.attribute(artifactType, "android-classes-jar") }.files,
            )
          } else {
            logger.warn(
              "navgraph: thumbnails for KMP module '$path' need an Android runtime classpath " +
                "('androidRuntimeClasspath' or 'debugRuntimeClasspath'), but none was " +
                "found. The graph still generates; thumbnails are skipped.",
            )
          }
          // This module's own compiled android classes: new `androidLibrary {}` writes
          // classes/kotlin/android/main; legacy `androidTarget()` writes tmp/kotlin-classes/debug.
          projectClasspath.from(layout.buildDirectory.dir("classes/kotlin/android/main"))
          projectClasspath.from(layout.buildDirectory.dir("tmp/kotlin-classes/debug"))
          projectClasspath.from(
            layout.buildDirectory.dir("intermediates/assets/androidMain/mergeAndroidMainAssets"),
          )
          dependsOn(
            tasks.matching {
              it.name == "compileAndroidMain" || it.name == "compileDebugKotlinAndroid"
            },
          )
          dependsOn(tasks.matching { it.name == "mergeAndroidMainAssets" })
          dependsOn(tasks.matching { it.name == "generateAndroidMainRFile" })
        } else {
          val v = requireNotNull(variant)
          val cap = v.replaceFirstChar { it.uppercase() }
          // The module's runtime dependencies, viewed as android-classes-jars (AGP's own unit-test view).
          appClasspath.from(
            configurations.getByName("${v}RuntimeClasspath").incoming
              .artifactView { attributes.attribute(artifactType, "android-classes-jar") }.files,
          )
          // This module's own compiled classes — the location depends on the Kotlin integration: the
          // `kotlin-android` plugin writes build/tmp/kotlin-classes/<variant>; AGP's built-in Kotlin (AGP 9,
          // android.builtInKotlin) writes intermediates/built_in_kotlinc/<variant>/compile<V>Kotlin/classes.
          // Include both (+ javac for Java sources) as SPECIFIC dirs (not a broad build/intermediates tree → no
          // overlapping-output validation); absent ones contribute nothing. compile<V>Kotlin is a task dep.
          projectClasspath.from(layout.buildDirectory.dir("tmp/kotlin-classes/$v"))
          projectClasspath.from(
            layout.buildDirectory.dir(
              "intermediates/built_in_kotlinc/$v/" +
                "compile${cap}Kotlin/classes",
            ),
          )
          projectClasspath.from(
            layout.buildDirectory.dir(
              "intermediates/javac/$v/" +
                "compile${cap}JavaWithJavac/classes",
            ),
          )
          rClassPath.from(
            layout.buildDirectory.dir("intermediates").map { d ->
              // The FULL R closure — this module's R AND every dependency's R, incl. androidx (e.g.
              // androidx.customview.poolingcontainer.R, which a ComposeView-backed preview loads at render). For an
              // app the AAPT2-linked R is under compile_and_runtime_r_class_jar/<variant> (the app links
              // everything); for a library the main R is module-only, so the closure lives under <variant>UnitTest.
              // CRUCIAL: take ONLY the linked `process<…>Resources` R — whose IDs match the unit-test `.ap_` we
              // feed — NOT the sibling `generate<…>StubRFile` R, a stub with PHANTOM ids. With non-transitive R a
              // cross-module `R.string.x` is a non-final field resolved at render time; if the stub (listed first)
              // wins the classloader, the id points at nothing in the `.ap_` → Resources$NotFoundException → a
              // blank/failed render (this is why feature modules that reference another module's R went blank).
              fileTree(d.asFile).matching {
                include(
                  "**/compile_and_runtime_r_class_jar/$v/process*Resources/R.jar",
                  "**/compile_and_runtime_r_class_jar/${v}UnitTest/process*Resources/R.jar",
                )
              }
            },
          )
          // The linked resources (.ap_) AGP produces for unit tests — gives Layoutlib the app's @string/themes/etc.
          resourceApk.from(
            layout.buildDirectory.dir("intermediates/apk_for_local_test/${v}UnitTest")
              .map { d -> fileTree(d.asFile).matching { include("**/apk-for-local-test.ap_") } },
          )
          // Materialize this variant's classes (transitively R) + the unit-test linked resources before rendering.
          dependsOn("compile${cap}Kotlin")
          dependsOn(tasks.matching { it.name == "compile${cap}JavaWithJavac" })
          dependsOn(tasks.matching { it.name == "package${cap}UnitTestForUnitTest" })
        }
      }
      if (kmp) wireKmpConsumerResources(this, renderTask)
      return renderTask
    }
  }

  /** Reflectively flip `android { testOptions { unitTests { isIncludeAndroidResources = true } } }` (no AGP
   *  compile dependency) so Robolectric reads the module's merged manifest/resources/assets → CMP `Res.*`
   *  resolve. Covers plain-Android + legacy KMP `com.android.library`; a no-op if no `android` extension. */
  private fun setIncludeAndroidResources(project: Project) {
    val android = project.extensions.findByName("android") ?: return
    runCatching {
      val testOptions = android.javaClass.getMethod("getTestOptions").invoke(android)
      val unitTests = testOptions.javaClass.getMethod("getUnitTests").invoke(testOptions)
      unitTests.javaClass
        .getMethod("setIncludeAndroidResources", Boolean::class.javaPrimitiveType)
        .invoke(unitTests, true)
    }.onFailure {
      project.logger.warn(
        "navgraph: could not enable testOptions.unitTests.isIncludeAndroidResources reflectively " +
          "(${it.message}); Robolectric previews using merged resources may render blank.",
      )
    }
  }

  /** Add [dir] as a Kotlin source root of the first existing source set among [sourceSetNames] via the
   *  `kotlin { sourceSets }` DSL, read reflectively (no KGP compile dependency) — the generated render test then
   *  compiles into that compilation's output, which the mirrored `Test` task scans. Candidates are tried in order
   *  because the unit-test source-set name differs by AGP version (`<variant>UnitTest` vs a shared `test`). */
  @Suppress("UNCHECKED_CAST")
  private fun addKotlinSrcDir(project: Project, sourceSetNames: List<String>, dir: File) {
    val kotlin = project.extensions.findByName("kotlin") ?: return
    runCatching {
      val sourceSets = kotlin.javaClass.getMethod("getSourceSets")
        .invoke(kotlin) as NamedDomainObjectCollection<Any>
      val sourceSet = sourceSetNames.firstNotNullOfOrNull { sourceSets.findByName(it) }
        ?: error("none of $sourceSetNames found (available: ${sourceSets.names})")
      val kotlinDirs = sourceSet.javaClass.getMethod("getKotlin")
        .invoke(sourceSet) as SourceDirectorySet
      kotlinDirs.srcDir(dir)
    }.onFailure {
      project.logger.warn(
        "navgraph: could not add the generated Robolectric render test to a unit-test " +
          "source set (${it.message}); the Robolectric render will not run.",
      )
    }
  }

  /** Write the one-line `NavGraphRobolectricRenderTest` subclass (idempotent — only when missing/changed, so it
   *  never needlessly invalidates the unit-test compilation). JUnit discovers it; `@RunWith`/`@Config` are
   *  inherited from [com.github.skydoves.navgraph.testing.NavPreviewRenderTestBase]. A non-blank
   *  [robolectricApplication] (`navgraph { robolectricApplication }`) is emitted as `@Config(application = …)` on
   *  the subclass — Robolectric overlays class-hierarchy configs per field, so sdk/qualifiers stay inherited. */
  private fun writeRobolectricTest(genDir: File, robolectricApplication: String) {
    val pkgDir = File(genDir, "com/skydoves/navgraph/generated").apply { mkdirs() }
    val file = File(pkgDir, "NavGraphRobolectricRenderTest.kt")
    val configImport = if (robolectricApplication.isNotBlank()) {
      "\nimport org.robolectric.annotation.Config"
    } else {
      ""
    }
    val configAnnotation = if (robolectricApplication.isNotBlank()) {
      "@Config(application = $robolectricApplication::class)\n"
    } else {
      ""
    }
    val content =
      """
      |package com.github.skydoves.navgraph.generated
      |
      |import com.github.skydoves.navgraph.testing.NavPreviewRenderTestBase$configImport
      |
      |${configAnnotation}internal class NavGraphRobolectricRenderTest : NavPreviewRenderTestBase()
      |
      """.trimMargin()
    if (!file.isFile || file.readText() != content) file.writeText(content)
  }

  /** Once all projects are evaluated, find the `com.android.application` that consumes this KMP [module] and feed
   *  its AAPT2-linked R closure + `.ap_` into the render — a KMP library produces neither itself, but the app that
   *  depends on it links the full resource closure (its own R + this module's + every dependency's). */
  private fun wireKmpConsumerResources(
    module: Project,
    renderTask: TaskProvider<LayoutlibRenderTask>,
  ) {
    module.gradle.projectsEvaluated {
      val consumer = findConsumingAndroidApp(module)
      if (consumer == null) {
        module.logger.warn(
          "navgraph: thumbnails for KMP module '${module.path}' need a consuming " +
            "com.android.application's linked resources, but none was found " +
            "in this build. The graph still generates; thumbnails are skipped.",
        )
        return@projectsEvaluated
      }
      val consumerBuild = consumer.layout.buildDirectory.get().asFile
      // The consuming app's debug variant — `debug`, or `<flavor>Debug` (e.g. `devDebug`) when flavored.
      val cv = consumerDebugVariant(consumer)
      val cvCap = cv.replaceFirstChar { it.uppercase() }
      renderTask.configure {
        // Glob the consumer's debug resources by absolute path (plain file IO at execution, not cross-project
        // model access) so this stays configuration-cache safe; the dependsOn ensures they're linked first.
        // An app links its full R closure (its own R + every dependency's, incl. androidx such as
        // androidx.customview.poolingcontainer.R that ComposeViewAdapter loads) under
        // `compile_and_runtime_not_namespaced_r_class_jar`; a library uses `compile_and_runtime_r_class_jar`.
        // Match both so the consuming app's R.jar is on the render classpath.
        rClassPath.from(
          module.fileTree(File(consumerBuild, "intermediates"))
            .matching { include("compile_and_runtime*r_class_jar/$cv/process*Resources/R.jar") },
        )
        resourceApk.from(
          module.fileTree(File(consumerBuild, "intermediates/linked_resources_binary_format"))
            .matching { include("**/$cv/process*Resources/*.ap_") },
        )
        // The consuming app's FULLY-merged Compose-Multiplatform resources (its own + this module's + every
        // dependency's composeResources). A KMP library's own mergeAndroidMainAssets carries ONLY its resources, so
        // a preview of a screen that reads a sibling module's `Res.*` (e.g. :app:ui-components strings) would
        // MissingResourceException at render. The app merges the whole closure under assets/<variant>/merge<V>Assets.
        projectClasspath.from(File(consumerBuild, "intermediates/assets/$cv/merge${cvCap}Assets"))
        dependsOn(consumer.tasks.matching { it.name == "process${cvCap}Resources" })
        dependsOn(consumer.tasks.matching { it.name == "merge${cvCap}Assets" })
      }
      module.logger.lifecycle(
        "navgraph: KMP module '${module.path}' renders thumbnails via " +
          "'${consumer.path}' ($cv) resources.",
      )
    }
  }

  /** The first `com.android.application` project that declares a dependency on [module]. Checks the always-created
   *  `implementation` configuration (variant runtime classpaths like `devDebugRuntimeClasspath` are registered
   *  lazily, so iterating their names at `projectsEvaluated` is unreliable). */
  private fun findConsumingAndroidApp(module: Project): Project? =
    module.rootProject.allprojects.firstOrNull { candidate ->
      candidate.path != module.path &&
        candidate.plugins.hasPlugin("com.android.application") &&
        candidate.configurations.findByName("implementation")
          ?.allDependencies?.any {
            it is ProjectDependency && projectDependencyPath(it) == module.path
          } == true
    }

  /** The consuming app's debug variant — `debug`, or `<flavor>Debug` (e.g. `devDebug`) when flavored. Reads the
   *  first product flavor off the `android` extension reflectively (no AGP compile dependency); iterating the
   *  lazily-registered variant configs is unreliable here. */
  private fun consumerDebugVariant(consumer: Project): String {
    val android = consumer.extensions.findByName("android") ?: return "debug"
    val flavor = runCatching {
      (android.javaClass.getMethod("getProductFlavors").invoke(android) as? Iterable<*>)
        ?.firstOrNull()
        ?.let { it.javaClass.getMethod("getName").invoke(it) as? String }
    }.getOrNull()
    return if (flavor.isNullOrBlank()) "debug" else "${flavor}Debug"
  }

  /** [ProjectDependency] target path: `getPath()` (Gradle 8.11+), falling back to the deprecated
   *  `dependencyProject.path` reflectively so the plugin runs on either API level. */
  private fun projectDependencyPath(dep: ProjectDependency): String? =
    runCatching { dep.javaClass.getMethod("getPath").invoke(dep) as? String }.getOrNull()
      ?: runCatching {
        val dp = dep.javaClass.getMethod("getDependencyProject").invoke(dep)
        dp.javaClass.getMethod("getPath").invoke(dp) as? String
      }.getOrNull()

  private companion object {
    const val KMP_PLUGIN = "org.jetbrains.kotlin.multiplatform"

    val ANDROID_PLUGINS = listOf(
      "com.android.application",
      "com.android.library",
      "com.android.kotlin.multiplatform.library",
    )

    // KMP without Android: the common-metadata KSP pass, structure only, no render.
    const val KMP_KSP_TASK = "kspCommonMainKotlinMetadata"
    const val KMP_MANIFEST = "generated/ksp/metadata/commonMain/resources/nav-graph.json"

    // KMP with Android (com.android.kotlin.multiplatform.library / `androidLibrary {}`): KSP over the androidMain
    // compilation (commonMain + androidMain) so it sees @NavPreview; the manifest lands under androidMain.
    const val KMP_ANDROID_KSP_TASK = "kspAndroidMain"
    const val KMP_ANDROID_MANIFEST = "generated/ksp/android/androidMain/resources/nav-graph.json"

    // Layoutlib backend: the pinned version tuple (renderer ↔ Layoutlib must stay an atomic pair) + the render
    // API level. Maven Layoutlib 16.2.1 ships Android 16 / API 36 (build.prop: ro.build.version.sdk=36).
    const val LAYOUTLIB_VERSION = "16.2.1"
    const val LAYOUTLIB_RENDERER_VERSION = "0.0.1-alpha15"
    const val LAYOUTLIB_API = "36"
  }
}
