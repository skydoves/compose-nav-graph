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
import org.gradle.api.file.FileCollection
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
      ext.lintEnabled.convention(true)
      ext.failOnNavLint.convention(false)
      ext.lintOnCheck.convention(false)
      // `set`, not `convention` — see the inferNavCalls note below; an empty convention would be silently
      // discarded the moment a consumer wrote `navLintIgnoredRoutes.add(...)`, which is the natural spelling.
      ext.navLintDisabledRules.set(emptySet<String>())
      ext.navLintIgnoredRoutes.set(emptySet<String>())
      ext.inferEdges.convention(true)
      ext.baselineIncludesInferred.convention(false)
      // `set`, deliberately not `convention`: Gradle's `SetProperty.add` appends to the EXPLICIT value and
      // discards a convention, so `inferNavCalls.add("openScreen")` on a convention-only property would silently
      // leave `{openScreen}` and switch every ordinary call site off. Seeding an explicit value makes `add` mean
      // "also match this", which is what the DSL reads like, while a user's own `set(…)` still replaces it.
      ext.inferNavCalls.set(KotlinNavScanner.DEFAULT_NAV_CALLS)
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
          (
            dependencies.add(
              testCfg,
              "com.github.skydoves:compose-nav-graph-testing:$v",
            ) as? ModuleDependency
            )
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

      // Layoutlib renders `@NavPreview` through `androidx.compose.ui.tooling.ComposeViewAdapter`, in Compose
      // `ui-tooling`. For KMP+Android the render classpath is the androidMain runtime — `androidRuntimeClasspath`
      // (new `androidLibrary {}`) / `debugRuntimeClasspath` (legacy `androidTarget()`), see registerLayoutlibRender —
      // and ui-tooling isn't on it by default, so the renderer emits a "…ComposeViewAdapter" placeholder for every
      // preview (issue #10). (Plain Android already gets ui-tooling on its variant runtime via the conventional
      // debugImplementation, so only KMP needs this.) Add the consumer's OWN ui-tooling notation — the exact string
      // they'd write as `compose.uiTooling`, so version+flavor match their screens (no NoSuchMethodError), honoring
      // the no-pin rule above. CMP plugin → read it reflectively; else derive from a declared `androidx.compose.ui:ui`.
      val layoutlib = ext.renderBackend.get() != RenderBackend.ROBOLECTRIC
      if (kmp && android && layoutlib && ext.renderThumbnails.get()) {
        val runtimeCfg = listOf("androidRuntimeClasspath", "debugRuntimeClasspath")
          .firstOrNull { configurations.findByName(it) != null }
        if (runtimeCfg != null && !uiToolingAlreadyDeclared()) {
          val notation = composeUiToolingNotation()
            ?: androidxComposeUiVersion()?.let { "androidx.compose.ui:ui-tooling:$it" }
          if (notation != null) {
            dependencies.add(runtimeCfg, notation)
            logger.info(
              "navgraph: added '$notation' to '$runtimeCfg' so Layoutlib can render KMP previews.",
            )
          }
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
          project.dependencies.create(
            "com.github.skydoves:compose-nav-graph-ksp:${navgraphVersion()}",
          ),
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
          "navgraph: could not pass the module path to KSP (${it.message}); the preview " +
            "gallery will group previews without module identity (single-module mode).",
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

  /** The consumer's own Compose `ui-tooling` dependency notation (`org.jetbrains.compose.ui:ui-tooling:<their CMP
   *  version>`) — the exact string they'd write as `compose.uiTooling` — read off the `org.jetbrains.compose`
   *  plugin's `compose` extension reflectively (no Compose-Gradle-plugin compile dependency). Because it carries the
   *  consumer's own version + flavor, adding it to the render classpath can't skew the Compose API (NoSuchMethodError).
   *  Null when the JetBrains Compose plugin isn't applied (androidx-Compose consumers use [androidxComposeUiVersion]). */
  private fun Project.composeUiToolingNotation(): String? {
    if (!plugins.hasPlugin("org.jetbrains.compose")) return null
    val compose = extensions.findByName("compose") ?: return null
    fun Any.uiTooling(): String? =
      runCatching { javaClass.getMethod("getUiTooling").invoke(this) as? String }.getOrNull()
    // `compose.uiTooling` is sugar for either ComposeExtension.getUiTooling() or its getDependencies().getUiTooling().
    return (
      compose.uiTooling()
        ?: runCatching {
          compose.javaClass.getMethod("getDependencies").invoke(compose)
        }.getOrNull()
          ?.uiTooling()
      )?.takeIf(String::isNotBlank)
  }

  /** The version of an already-DECLARED `androidx.compose.ui:ui`, scanned from the common declarable configs (never
   *  the runtime classpath we add to, so nothing is resolved). The androidx-Compose fallback for
   *  [composeUiToolingNotation]. Null when absent or version-less (e.g. pinned only by a BOM). */
  private fun Project.androidxComposeUiVersion(): String? =
    sequenceOf("commonMainImplementation", "androidMainImplementation", "implementation")
      .mapNotNull { configurations.findByName(it) }
      .flatMap { it.dependencies.asSequence() }
      .firstOrNull { it.group == "androidx.compose.ui" && it.name == "ui" }
      ?.version?.takeIf(String::isNotBlank)

  /** Whether Compose `ui-tooling` (either flavor) is already DECLARED on the render/impl configs — checked against
   *  declared dependencies only (no resolution), so we never add a duplicate/conflicting coordinate when the consumer
   *  already wired it by hand. */
  private fun Project.uiToolingAlreadyDeclared(): Boolean = sequenceOf(
    "androidRuntimeClasspath",
    "debugRuntimeClasspath",
    "commonMainImplementation",
    "androidMainImplementation",
    "implementation",
  )
    .mapNotNull { configurations.findByName(it) }
    .flatMap { it.dependencies.asSequence() }
    .any {
      (it.group == "org.jetbrains.compose.ui" || it.group == "androidx.compose.ui") &&
        it.name == "ui-tooling"
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
          this, "renderNavGraphGalleryLayoutlib", variant, kmp, kspTask, layoutlib,
          galleryManifestFile,
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

      // Each dependency module's published nav-graph dir, re-selected off the already-resolved runtime classpath (so
      // Android variant attributes stay correct) and leniently skipping deps that produce none (JaCoCo-style).
      val depGraphDirs = if (ext.aggregate.get() && !kmp && variant != null) {
        configurations.findByName("${variant}RuntimeClasspath")?.incoming?.artifactView {
          withVariantReselection()
          lenient(true)
          componentFilter { it is ProjectComponentIdentifier }
          attributes.attribute(NAVGRAPH_GRAPH_ATTRIBUTE, NAVGRAPH_GRAPH_VALUE)
        }?.files
      } else {
        null
      }

      // (a2) Infer the transitions KSP cannot see (it reads declarations only, never `entry<T>{}`/`backStack.add`
      // bodies) by scanning this module's Kotlin sources, and enrich the manifest with them as INFERRED edges.
      //
      // Resolves against THIS module's routes only, deliberately. Reading the dependency modules' routes would mean
      // an artifact view over the runtime classpath, and such a view carries the build dependencies of everything
      // those modules publish — including the nav-graph dir built by `mergeNavGraph`. Since `navCheck` is wired
      // into `check`, that quietly pulled every dependency's thumbnail render, and with it that module's AGP
      // unit-test task, which the render's `whenReady` hook then filters down to the render test alone: a module's
      // real unit tests would stop running, and CI would go green with them broken. Inference is a convenience;
      // it does not get to break `check` for it.
      val infer = if (ext.inferEdges.get()) {
        tasks.register("inferNavEdges", InferNavEdgesTask::class.java) {
          group = "navgraph"
          description =
            "Infers transitions from navigation call sites that KSP cannot read."
          this.kspManifest.set(kspManifestFile)
          sources.from(kotlinSourceDirs(project))
          navCalls.set(ext.inferNavCalls)
          outputManifest.set(navgraphDir.map { it.file("nav-graph-inferred.json") })
          dependsOn(kspTask)
        }
      } else {
        null
      }
      // What every EDGE consumer reads: the KSP manifest enriched with inferred edges, or the raw one when inference
      // is off. The render tasks deliberately keep reading the raw manifest — they only need the preview list.
      val edgeManifest = if (infer != null) {
        navgraphDir.map { it.file("nav-graph-inferred.json") }
      } else {
        kspManifestFile
      }
      val edgeManifestProducer: Any = infer ?: kspTask

      // (b) Merge the manifest (+ thumbnails, when rendered) into the consumed manifest.
      val merge = tasks.register("mergeNavGraph", MergeNavGraphTask::class.java) {
        this.kspManifest.set(edgeManifest)
        if (doRender || doRobolectric) previewIndex.set(previewIndexFile)
        outputManifest.set(navgraphDir.map { it.file("nav-graph.json") })
        dependsOn(edgeManifestProducer)
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
      val aggregatedDir = layout.buildDirectory.dir("navgraph-aggregated")
      val aggregateTask = if (depGraphDirs != null) {
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
        packageFilter.set(providers.gradleProperty("navgraph.export.package").orElse(""))
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
        packageFilter.set(providers.gradleProperty("navgraph.export.package").orElse(""))
        scale.set(providers.gradleProperty("navgraph.export.scale").map { it.toInt() })
        outputImage.set(
          layout.file(providers.gradleProperty("navgraph.export.out").map { File(it) })
            .orElse(navgraphDir.map { it.file("nav-graph.png") }),
        )
        dependsOn(graphProducer)
      }

      // (d2a) navLint — the graph's health, where the `.nav` baseline reviews only its change. Reads the same
      // combined graph the exports do: a cross-module route shows as a bare stub in the declaring module's own
      // manifest, indistinguishable from a genuinely unbound route, so linting anything less would cry wolf on
      // every dependency's screen. That input is why it is NOT in `check` by default — see `lintOnCheck`.
      val navLint = if (ext.lintEnabled.get()) {
        tasks.register("navLint", NavLintTask::class.java) {
          group = "navgraph"
          description =
            "Reports navigation graph problems: unreachable screens, missing or duplicate start, unbound routes."
          manifest.set(graphSourceDir.map { it.file("nav-graph.json") })
          failOnNavLint.set(ext.failOnNavLint)
          disabledRules.set(ext.navLintDisabledRules)
          ignoredRoutes.set(ext.navLintIgnoredRoutes)
          aggregated.set(aggregateTask != null)
          dependsOn(graphProducer)
        }
      } else {
        null
      }
      if (navLint != null && ext.lintOnCheck.get()) {
        plugins.withId("base") { tasks.named("check") { dependsOn(navLint) } }
      }

      // (d2b) Mermaid export — the one artifact that needs no viewer: GitHub renders a ```mermaid block natively,
      // so the graph can live in the README and be regenerated on every build instead of going stale.
      tasks.register("exportNavGraphMermaid", ExportNavGraphMermaidTask::class.java) {
        group = "navgraph"
        description =
          "Writes build/navgraph/nav-graph.mmd — a Mermaid flowchart GitHub markdown renders natively."
        manifest.set(graphSourceDir.map { it.file("nav-graph.json") })
        packageFilter.set(providers.gradleProperty("navgraph.export.package").orElse(""))
        direction.set(providers.gradleProperty("navgraph.export.direction").orElse("LR"))
        markdown.set(
          providers.gradleProperty("navgraph.export.mermaid.markdown").map {
            it.toBoolean()
          },
        )
        outputFile.set(
          layout.file(providers.gradleProperty("navgraph.export.mermaid.out").map { File(it) })
            .orElse(navgraphDir.map { it.file("nav-graph.mmd") }),
        )
        dependsOn(graphProducer)
      }

      // (d3) Visual navDiff: the same two renderers, pointed at the committed `.nav` baseline. Because git already
      // holds that baseline, "before" costs nothing — and the picture is computed from the exact lines navCheck
      // compares, so a pull request can never show a clean diff while the build fails on drift.
      // `-Pnavgraph.diff.base=<path.nav>` compares against a different baseline (e.g. one restored from CI).
      // fileContents() reads it as an optional value, so a project that has not run navDump yet still exports.
      // Resolved to a RegularFile here rather than chained lazily: `providers.fileContents(layout.file(provider))`
      // serializes a provider that reaches for ProjectLayout, which is not available when the configuration cache
      // is loaded back, and both diff tasks then fail to even start.
      val diffBaselineFile = providers.gradleProperty("navgraph.diff.base").orNull
        ?.let { layout.projectDirectory.file(it) }
        ?: ext.baselineFile.get()
      val diffBaseline = providers.fileContents(diffBaselineFile).asText

      // Both read this module's OWN merged graph, never the aggregated one, and neither takes a package filter.
      // The `.nav` baseline records exactly what `navDump` dumps — this module's destinations — so diffing an
      // aggregated or filtered graph against it would paint every dependency module's screen "added" forever, on a
      // repo where nothing changed. Same manifest as navCheck ⇒ the picture and the build always agree.
      tasks.register("exportNavDiffHtml", ExportNavGraphHtmlTask::class.java) {
        group = "navgraph"
        description =
          "Renders build/navgraph/nav-diff.html — the flow graph coloured against the .nav baseline."
        manifest.set(navgraphDir.map { it.file("nav-graph.json") })
        // Only when something actually renders. `build/navgraph/thumbs` is created by the render task, so in
        // structure-only mode (`renderThumbnails = false`) pointing at it fails Gradle's input validation before
        // the task can start. The sibling exports read the aggregated dir, which is always created, and so never
        // hit this; `thumbsDir` is @Optional precisely so it can be left unset here.
        if (doRender || doRobolectric) this.thumbsDir.set(navgraphDir.map { it.dir("thumbs") })
        deviceSpec.set(providers.gradleProperty("navgraph.export.device").orElse(""))
        diffBaselineText.set(diffBaseline)
        diffIncludesInferred.set(ext.baselineIncludesInferred)
        outputHtml.set(
          layout.file(providers.gradleProperty("navgraph.diff.html.out").map { File(it) })
            .orElse(navgraphDir.map { it.file("nav-diff.html") }),
        )
        dependsOn(merge)
      }

      tasks.register("exportNavDiffImage", ExportNavGraphImageTask::class.java) {
        group = "navgraph"
        description =
          "Renders build/navgraph/nav-diff.png — a review image of what this change does to navigation."
        manifest.set(navgraphDir.map { it.file("nav-graph.json") })
        // See exportNavDiffHtml: unset in structure-only mode, or the task fails input validation.
        if (doRender || doRobolectric) this.thumbsDir.set(navgraphDir.map { it.dir("thumbs") })
        deviceSpec.set(providers.gradleProperty("navgraph.export.device").orElse(""))
        scale.set(providers.gradleProperty("navgraph.export.scale").map { it.toInt() })
        diffBaselineText.set(diffBaseline)
        diffIncludesInferred.set(ext.baselineIncludesInferred)
        outputImage.set(
          layout.file(providers.gradleProperty("navgraph.diff.image.out").map { File(it) })
            .orElse(navgraphDir.map { it.file("nav-diff.png") }),
        )
        dependsOn(merge)
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

      // (e) Navigation baseline (.nav) — reads the extracted manifest directly (structure only, render-free).
      tasks.register("navDump", NavDumpTask::class.java) {
        group = "navgraph"
        description = "Writes the committed nav baseline (.nav) from the current graph."
        manifest.set(edgeManifest)
        baseline.set(ext.baselineFile)
        includeInferred.set(ext.baselineIncludesInferred)
        dependsOn(edgeManifestProducer)
      }
      val navCheck = tasks.register("navCheck", NavCheckTask::class.java) {
        group = "navgraph"
        description = "Fails if the navigation graph drifted from the committed .nav baseline."
        manifest.set(edgeManifest)
        baseline.set(ext.baselineFile)
        includeInferred.set(ext.baselineIncludesInferred)
        failOnNavChange.set(ext.failOnNavChange)
        allowMissingBaseline.set(ext.allowMissingBaseline)
        dependsOn(edgeManifestProducer)
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
        // Materialized OUTSIDE the closures attached to the test task: the configuration cache serializes that
        // task's whole state — sysprops AND its onlyIf specs — so a spec capturing `active` would drag each
        // Wired's TaskProviders (realized DefaultTasks) into the entry and fail serialization. Plain Files don't.
        val renderListFiles = active.map { it.renderListFile.get().asFile }
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
            systemProperty(
              "navgraph.previewIndex.$i",
              w.spec.previewIndex.get().asFile.absolutePath,
            )
          }
          // Run iff ANY active pipeline has a non-empty render list (a Layoutlib failure to fill somewhere).
          onlyIf {
            renderListFiles.any { it.isFile && it.readText().isNotBlank() }
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
        kmpModule.set(kmp)
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
              // androidx.customview.poolingcontainer.R$id, which EVERY ComposeView-backed preview loads at render
              // via PoolingContainer.<clinit>; if it's absent the renderer aborts at ComposeViewAdapter and the
              // preview silently falls back to the portrait Robolectric path). The app's AAPT2-linked R jar dir
              // varies by AGP config/version — `compile_and_runtime_r_class_jar` OR
              // `compile_and_runtime_not_namespaced_r_class_jar` — so match BOTH via `compile_and_runtime*r_class_jar`
              // (the same wildcard wireKmpConsumerResources uses for the consuming app); a library's module-only R
              // closure lives under <variant>UnitTest. CRUCIAL: take ONLY the linked `process<…>Resources` R — whose
              // IDs match the unit-test `.ap_` we feed — NOT the sibling `generate<…>StubRFile` R, a stub with
              // PHANTOM ids. With non-transitive R a cross-module `R.string.x` is a non-final field resolved at
              // render time; if the stub (listed first) wins the classloader, the id points at nothing in the `.ap_`
              // → Resources$NotFoundException → a blank/failed render (why feature modules referencing another
              // module's R went blank).
              fileTree(d.asFile).matching {
                include(
                  "**/compile_and_runtime*r_class_jar/$v/process*Resources/R.jar",
                  "**/compile_and_runtime*r_class_jar/${v}UnitTest/process*Resources/R.jar",
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

  /**
   * This module's own Kotlin sources — `main` on Android, `commonMain`/`androidMain`/… on KMP — read reflectively
   * off `kotlin { sourceSets }` (no KGP compile dependency, mirroring [addKotlinSrcDir]). Test source sets and
   * anything under the build directory are excluded: edge inference should describe the app's navigation, not its
   * tests or its own generated code.
   *
   * Falls back to scanning the module's `src` directory when the reflective read fails, so a future KGP shape can
   * never silently turn inference off.
   */
  @Suppress("UNCHECKED_CAST")
  private fun kotlinSourceDirs(project: Project): FileCollection {
    // Trailing separator so a sibling directory like `<project>/buildsomething/` isn't mistaken for build output.
    val buildDir = project.layout.buildDirectory.get().asFile.absolutePath + File.separator
    val kotlin = project.extensions.findByName("kotlin")
    val roots = kotlin?.let { extension ->
      runCatching {
        val sourceSets = extension.javaClass.getMethod("getSourceSets")
          .invoke(extension) as NamedDomainObjectCollection<Any>
        sourceSets.names
          // `test`, `androidTest`, `commonTest`, `androidUnitTest`, and AGP's per-variant `testDebug` /
          // `androidTestDebug` — but NOT a feature set named `latestMain`, which contains a lowercase `test`
          // yet neither starts with it nor carries the capitalised form.
          .filterNot { name -> name.startsWith("test") || "Test" in name }
          .mapNotNull(sourceSets::findByName)
          .flatMap { set ->
            (set.javaClass.getMethod("getKotlin").invoke(set) as SourceDirectorySet).srcDirs
          }
      }.getOrElse {
        project.logger.info(
          "navgraph: could not read Kotlin source sets (${it.message}); scanning the src directory instead.",
        )
        null
      }
    }
    val declared = roots?.takeIf { it.isNotEmpty() }
    val dirs = declared ?: listOf(project.file("src"))
    return project.files(
      dirs.distinct()
        .filterNot { (it.absolutePath + File.separator).startsWith(buildDir) }
        .map { dir ->
          project.fileTree(dir) {
            include(KOTLIN_SOURCES)
            // Only the fallback root spans whole source sets, so it is the only one that can pick up tests.
            if (declared == null) exclude(TEST_SOURCE_DIRS)
          }
        },
    )
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

  /** The `com.android.application` whose linked resources feed the KMP render: [module] ITSELF when it carries the
   *  application plugin (a single-module Compose-Multiplatform app — KMP + `com.android.application` on one module —
   *  links its own R/.ap_, so it needs no external consumer), else the first application project that
   *  **transitively** depends on [module]. A feature module is often several hops below the app (`:app` →
   *  `:composeApp` → `:feature:x`); the app links its FULL transitive resource closure, so any app that can reach
   *  [module] through project dependencies can render it. Walks DECLARED project deps (the always-created declarable
   *  buckets; variant runtime classpaths are registered lazily, so iterating them at `projectsEvaluated` is
   *  unreliable). */
  private fun findConsumingAndroidApp(module: Project): Project? =
    module.takeIf { it.plugins.hasPlugin("com.android.application") }
      ?: module.rootProject.allprojects.firstOrNull { candidate ->
        candidate.path != module.path &&
          candidate.plugins.hasPlugin("com.android.application") &&
          appReachesModule(candidate, module.path)
      }

  /** Whether [app]'s project-dependency closure (transitively) reaches [targetPath]. BFS over [directProjectDeps],
   *  visited-guarded so dependency cycles can't loop. */
  private fun appReachesModule(app: Project, targetPath: String): Boolean {
    val visited = hashSetOf(app.path)
    val queue = ArrayDeque(listOf(app))
    while (queue.isNotEmpty()) {
      for (depPath in directProjectDeps(queue.removeFirst())) {
        if (depPath == targetPath) return true
        if (visited.add(depPath)) app.rootProject.findProject(depPath)?.let { queue.add(it) }
      }
    }
    return false
  }

  /** The DECLARED project-dependency paths of [project], across the dependency buckets that carry a module edge into
   *  an app's resource closure: plain `implementation`/`api` and the KMP `commonMain`/`androidMain` source sets.
   *  Declarable buckets only (declared project edges are enough for reachability; resolved/variant configs are
   *  lazy + unreliable here). */
  private fun directProjectDeps(project: Project): Set<String> {
    val out = LinkedHashSet<String>()
    for (name in DEP_EDGE_CONFIGS) {
      project.configurations.findByName(name)?.allDependencies?.forEach { dep ->
        if (dep is ProjectDependency) projectDependencyPath(dep)?.let { out.add(it) }
      }
    }
    return out
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

    /** Declarable dependency buckets walked to find an app's transitive project-dependency closure (a module edge
     *  into the app's resource closure flows through these): plain Android + the KMP commonMain/androidMain sets. */
    val DEP_EDGE_CONFIGS = listOf(
      "implementation",
      "api",
      "commonMainImplementation",
      "commonMainApi",
      "androidMainImplementation",
      "androidMainApi",
    )

    // Edge inference reads Kotlin sources only; the test excludes apply solely to the whole-`src` fallback root,
    // since the declared source-set roots are already filtered by name.
    const val KOTLIN_SOURCES = "**/*.kt"

    // `test*/` covers `test`, `testDebug`, `testDemoRelease`; `*Test*/` covers `androidTest`, `commonTest`,
    // `androidUnitTestDebug`. Neither matches `latestMain` (no leading `test`, no capitalised `Test`).
    val TEST_SOURCE_DIRS = listOf("test*/**", "*Test*/**")

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
