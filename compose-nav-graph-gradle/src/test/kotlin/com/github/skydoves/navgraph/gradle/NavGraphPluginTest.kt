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

import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NavGraphPluginTest {

  private fun navgraphProject(): Project =
    ProjectBuilder.builder().withName("sample").build().also {
      it.pluginManager.apply("com.github.skydoves.navgraph")
    }

  /** A project past `afterEvaluate`, so the tasks the plugin registers there exist and `check` is wired. */
  private fun evaluatedProject(configure: (NavGraphExtension) -> Unit = {}): Project {
    val project = ProjectBuilder.builder().withName("sample").build()
    // `base` is what creates `check`; Android and KMP both bring it, and the plugin gates its wiring on it.
    project.pluginManager.apply("base")
    project.pluginManager.apply("com.github.skydoves.navgraph")
    val ext = project.extensions.getByType(NavGraphExtension::class.java)
    ext.renderThumbnails.set(false)
    ext.autoDependencies.set(false)
    configure(ext)
    (project as ProjectInternal).evaluate()
    return project
  }

  @Test
  fun registersNavGraphExtensionWithDefaults() {
    val ext = navgraphProject().extensions.getByType(NavGraphExtension::class.java)
    assertTrue(ext.failOnNavChange.get())
    assertFalse(ext.allowMissingBaseline.get())
    assertTrue(ext.renderThumbnails.get())
    assertEquals(RenderBackend.AUTO, ext.renderBackend.get())
    assertEquals("", ext.variant.get())
    assertTrue(ext.autoDependencies.get())
    // Inference is on out of the box, but stays out of the committed baseline so an upgrade can't break navCheck.
    assertTrue(ext.inferEdges.get())
    assertFalse(ext.baselineIncludesInferred.get())
    assertEquals(KotlinNavScanner.DEFAULT_NAV_CALLS, ext.inferNavCalls.get())
  }

  @Test
  fun addingANavCallKeepsTheDefaults() {
    // `inferNavCalls` is seeded with `set`, not `convention`: Gradle's `add` appends to the explicit value and
    // DISCARDS a convention, so on a convention-only property this would leave `{openScreen}` alone and quietly
    // switch every ordinary call site (`backStack.add`, `navigate`, …) off.
    val ext = navgraphProject().extensions.getByType(NavGraphExtension::class.java)
    ext.inferNavCalls.add("openScreen")
    assertTrue(ext.inferNavCalls.get().containsAll(KotlinNavScanner.DEFAULT_NAV_CALLS))
    assertTrue(ext.inferNavCalls.get().contains("openScreen"))
  }

  @Test
  fun settingNavCallsStillReplacesTheDefaults() {
    val ext = navgraphProject().extensions.getByType(NavGraphExtension::class.java)
    ext.inferNavCalls.set(setOf("openScreen"))
    assertEquals(setOf("openScreen"), ext.inferNavCalls.get())
  }

  @Test
  fun baselineFileDefaultsToModuleNavFile() {
    val project = navgraphProject()
    val ext = project.extensions.getByType(NavGraphExtension::class.java)
    val expected = project.layout.projectDirectory.file("nav/sample.nav").asFile
    assertEquals(expected, ext.baselineFile.get().asFile)
  }

  @Test
  fun registersBaselineTasksOnPlainNonAndroidEvaluation() {
    val project = ProjectBuilder.builder().withName("sample").build()
    project.pluginManager.apply("com.github.skydoves.navgraph")
    val ext = project.extensions.getByType(NavGraphExtension::class.java)
    ext.renderThumbnails.set(false)
    ext.autoDependencies.set(false)
    (project as ProjectInternal).evaluate()

    assertNotNull(project.tasks.findByName("generateNavGraph"))
    assertNotNull(project.tasks.findByName("mergeNavGraph"))
    assertNotNull(project.tasks.findByName("navDump"))
    assertNotNull(project.tasks.findByName("navCheck"))
    assertNotNull(project.tasks.findByName("exportNavGraphHtml"))
    assertNotNull(project.tasks.findByName("exportNavGraphImage"))
    assertNotNull(project.tasks.findByName("inferNavEdges"))
    assertEquals("navgraph", project.tasks.getByName("generateNavGraph").group)
  }

  @Test
  fun inferNavEdgesIsNotRegisteredWhenInferenceIsOff() {
    val project = ProjectBuilder.builder().withName("sample").build()
    project.pluginManager.apply("com.github.skydoves.navgraph")
    val ext = project.extensions.getByType(NavGraphExtension::class.java)
    ext.renderThumbnails.set(false)
    ext.autoDependencies.set(false)
    ext.inferEdges.set(false)
    (project as ProjectInternal).evaluate()

    assertNull(project.tasks.findByName("inferNavEdges"))
    assertNotNull(project.tasks.findByName("mergeNavGraph"))
  }

  @Test
  fun inferNavEdgesAddsCallSiteTransitionsAndKeepsAnnotatedOnes() {
    val project = ProjectBuilder.builder().build()
    val task = project.tasks.register("inferNavEdges", InferNavEdgesTask::class.java).get()
    task.kspManifest.set(writeManifest(project, MANIFEST))
    task.sources.from(
      project.layout.projectDirectory.file("src/main/kotlin/Nav.kt").asFile.apply {
        parentFile.mkdirs()
        writeText(
          """
          package x
          fun App() {
            entryProvider {
              entry<Home> { HomeScreen(onOpen = { backStack.add(Profile) }) }
              entry<Profile> { ProfileScreen(onBack = { backStack.add(Home) }) }
            }
          }
          """.trimIndent(),
        )
      },
    )
    val out = project.layout.buildDirectory.file("out/nav-graph-inferred.json").get().asFile
    task.outputManifest.set(out)
    task.infer()

    val edges = parseGraph(out.readText()).edges
    // Home → Profile is already a @NavEdge, so it stays ANNOTATED (with its label); only Profile → Home is added.
    assertEquals(
      listOf(Triple("x.Home", "x.Profile", false), Triple("x.Profile", "x.Home", true)),
      edges.map { Triple(it.from, it.to, it.inferred) },
    )
    assertEquals("go", edges.first().label)
  }

  @Test
  fun inferNavEdgesNeverInventsADestination() {
    val project = ProjectBuilder.builder().build()
    val task = project.tasks.register("inferNavEdges", InferNavEdgesTask::class.java).get()
    task.kspManifest.set(writeManifest(project, MANIFEST))
    task.sources.from(
      project.layout.projectDirectory.file("src/main/kotlin/Nav.kt").asFile.apply {
        parentFile.mkdirs()
        writeText(
          """
          package x
          fun App() {
            entry<Home> {
              backStack.add(NotARouteAnywhere)
              basket.add(Item("apple"))
            }
          }
          """.trimIndent(),
        )
      },
    )
    val out = project.layout.buildDirectory.file("out/nav-graph-inferred.json").get().asFile
    task.outputManifest.set(out)
    task.infer()

    val graph = parseGraph(out.readText())
    assertEquals(listOf("x.Home", "x.Profile"), graph.nodes.map { it.id })
    assertEquals(1, graph.edges.size)
  }

  @Test
  fun navLintIsRegisteredButStaysOutOfCheckByDefault() {
    // Out of `check` on purpose: navLint reads the AGGREGATED graph, so gating check on it would build every
    // dependency module's graph and its thumbnail render. navCheck reads the render-free manifest and may stay.
    val project = evaluatedProject()
    assertNotNull(project.tasks.findByName("navLint"))
    assertEquals("navgraph", project.tasks.getByName("navLint").group)
    val checkDeps = project.tasks.getByName("check").taskDependencies.getDependencies(null).map {
      it.name
    }
    assertFalse("navLint must not be wired into check by default", "navLint" in checkDeps)
    assertTrue("navCheck must stay wired into check", "navCheck" in checkDeps)
  }

  @Test
  fun lintOnCheckWiresNavLintIntoCheck() {
    val project = evaluatedProject { it.lintOnCheck.set(true) }
    val checkDeps = project.tasks.getByName("check").taskDependencies.getDependencies(null).map {
      it.name
    }
    assertTrue("navLint", "navLint" in checkDeps)
  }

  @Test
  fun lintEnabledFalseSkipsRegistration() {
    val project = evaluatedProject { it.lintEnabled.set(false) }
    assertNull(project.tasks.findByName("navLint"))
  }

  @Test
  fun addingAnIgnoredRouteKeepsTheEmptyDefault() {
    // Same SetProperty trap as inferNavCalls: `add` on a convention-only property discards it. Both lint sets are
    // seeded with `set`, so `add` extends rather than replaces — here the default is empty, so this pins that
    // `add` works at all rather than silently no-op'ing a later `set`.
    val ext = navgraphProject().extensions.getByType(NavGraphExtension::class.java)
    assertEquals(emptySet<String>(), ext.navLintIgnoredRoutes.get())
    ext.navLintIgnoredRoutes.add("com.app.Orphan")
    assertEquals(setOf("com.app.Orphan"), ext.navLintIgnoredRoutes.get())
  }

  @Test
  fun navLintWarnsInsteadOfFailingByDefault() {
    val project = ProjectBuilder.builder().build()
    val task = project.tasks.register("navLint", NavLintTask::class.java).get()
    task.manifest.set(writeManifest(project, MANIFEST_UNREACHABLE))
    task.failOnNavLint.set(false)
    task.lint()
  }

  @Test
  fun navLintFailsWithFindingsWhenFailOnNavLint() {
    val project = ProjectBuilder.builder().build()
    val task = project.tasks.register("navLint", NavLintTask::class.java).get()
    task.manifest.set(writeManifest(project, MANIFEST_UNREACHABLE))
    task.failOnNavLint.set(true)
    task.aggregated.set(true)

    val error = runCatching { task.lint() }.exceptionOrNull()
    assertNotNull("navLint must fail when failOnNavLint is on", error)
    val message = error!!.message!!
    assertTrue(message, "navigation lint finding(s)" in message)
    assertTrue(message, "[unreachable] Ghost" in message)
    assertTrue(message, "navLintIgnoredRoutes" in message)
  }

  @Test
  fun navLintNotesAPartialGraphWhenNotAggregated() {
    val project = ProjectBuilder.builder().build()
    val task = project.tasks.register("navLint", NavLintTask::class.java).get()
    task.manifest.set(writeManifest(project, MANIFEST_UNREACHABLE))
    task.failOnNavLint.set(true)
    task.aggregated.set(false)

    val message = runCatching { task.lint() }.exceptionOrNull()!!.message!!
    assertTrue(message, "own graph, not the whole app's" in message)
  }

  @Test
  fun aggregationFillsAStubFromTheModuleThatOwnsTheRoute() {
    // The umbrella's own manifest is merged first and, with no thumbnails anywhere (structure-only, or KMP), used
    // to win outright — keeping its bare stub and dropping the owner's click target, which then reads to navLint
    // as a route no @NavDestination binds.
    val project = ProjectBuilder.builder().build()
    val task = project.tasks.register("aggregateNavGraph", AggregateNavGraphTask::class.java).get()
    task.manifestFileName.set("nav-graph.json")
    task.ownManifest.set(writeManifest(project, STUB_MANIFEST))
    val depDir = project.layout.buildDirectory.dir("dep").get().asFile.apply { mkdirs() }
    File(depDir, "nav-graph.json").writeText(OWNER_MANIFEST)
    task.dependencyGraphDirs.from(depDir)
    val out = project.layout.buildDirectory.dir("aggregated").get().asFile
    task.outputDir.set(out)
    task.aggregate()

    val node = parseGraph(File(out, "nav-graph.json").readText()).nodes.single { it.id == "x.Feed" }
    assertEquals("x.FeedScreen", node.clickTargetFqn)
    assertEquals("Feed.kt", node.sourceFile)
    assertTrue("the start flag must survive the merge", node.start)
  }

  @Test
  fun navCheckPassesWhenBaselineMatches() {
    val project = ProjectBuilder.builder().build()
    val task = project.tasks.register("navCheck", NavCheckTask::class.java).get()
    task.manifest.set(writeManifest(project, MANIFEST))
    task.baseline.set(writeBaseline(project, BASELINE))
    task.failOnNavChange.set(true)
    task.allowMissingBaseline.set(false)
    task.check()
  }

  @Test
  fun navCheckFailsWithAddRemoveDiffOnDrift() {
    val project = ProjectBuilder.builder().build()
    val task = project.tasks.register("navCheck", NavCheckTask::class.java).get()
    val drifted = MANIFEST.replace("\"route\": \"Profile\"", "\"route\": \"Settings\"")
    task.manifest.set(writeManifest(project, drifted))
    task.baseline.set(writeBaseline(project, BASELINE))
    task.failOnNavChange.set(true)
    task.allowMissingBaseline.set(false)

    val error = runCatching { task.check() }.exceptionOrNull()
    assertNotNull("navCheck must fail on drift", error)
    val message = error!!.message!!
    assertTrue(message, "out of date" in message)
    assertTrue(message, "  - dest Profile" in message)
    assertTrue(message, "  - edge Home -> Profile  \"go\"" in message)
    assertTrue(message, "  + dest Settings" in message)
    assertTrue(message, "  + edge Home -> Settings  \"go\"" in message)
  }

  @Test
  fun navCheckWarnsInsteadOfFailingWhenFailOnNavChangeFalse() {
    val project = ProjectBuilder.builder().build()
    val task = project.tasks.register("navCheck", NavCheckTask::class.java).get()
    val drifted = MANIFEST.replace("\"route\": \"Profile\"", "\"route\": \"Settings\"")
    task.manifest.set(writeManifest(project, drifted))
    task.baseline.set(writeBaseline(project, BASELINE))
    task.failOnNavChange.set(false)
    task.allowMissingBaseline.set(false)
    task.check()
  }

  @Test
  fun navCheckFailsOnMissingBaselineByDefault() {
    val project = ProjectBuilder.builder().build()
    val task = project.tasks.register("navCheck", NavCheckTask::class.java).get()
    task.manifest.set(writeManifest(project, MANIFEST))
    task.baseline.set(project.layout.projectDirectory.file("nav/absent.nav"))
    task.failOnNavChange.set(true)
    task.allowMissingBaseline.set(false)

    val error = runCatching { task.check() }.exceptionOrNull()
    assertNotNull(error)
    assertTrue(error!!.message!!, "No nav baseline" in error.message!!)
  }

  @Test
  fun navCheckSkipsMissingBaselineWhenAllowed() {
    val project = ProjectBuilder.builder().build()
    val task = project.tasks.register("navCheck", NavCheckTask::class.java).get()
    task.manifest.set(writeManifest(project, MANIFEST))
    task.baseline.set(project.layout.projectDirectory.file("nav/absent.nav"))
    task.failOnNavChange.set(true)
    task.allowMissingBaseline.set(true)
    task.check()
  }

  @Test
  fun navDumpWritesRenderedBaselineFromManifest() {
    val project = ProjectBuilder.builder().build()
    val task = project.tasks.register("navDump", NavDumpTask::class.java).get()
    task.manifest.set(writeManifest(project, MANIFEST))
    val out = project.layout.buildDirectory.file("out/sample.nav").get().asFile
    task.baseline.set(out)
    task.dump()
    assertEquals(BASELINE.trim(), out.readText().trim())
  }

  private fun writeManifest(project: Project, json: String) =
    project.layout.buildDirectory.file("test/nav-graph.json").get().asFile.apply {
      parentFile.mkdirs()
      writeText(json)
    }

  private fun writeBaseline(project: Project, text: String) =
    project.layout.buildDirectory.file("test/sample.nav").get().asFile.apply {
      parentFile.mkdirs()
      writeText(text)
    }

  private companion object {
    // What an umbrella module that only REFERENCES x.Feed extracts: id and route, nothing else.
    val STUB_MANIFEST = """
      {
        "navVersion": "navgraph",
        "schemaVersion": 1,
        "nodes": [{"id": "x.Feed", "route": "Feed", "previews": []}],
        "edges": []
      }
    """.trimIndent()

    // What the module that actually declares x.Feed extracts.
    val OWNER_MANIFEST = """
      {
        "navVersion": "navgraph",
        "schemaVersion": 1,
        "nodes": [{
          "id": "x.Feed", "route": "Feed", "start": true,
          "clickTargetFqn": "x.FeedScreen", "sourceFile": "Feed.kt", "sourceLine": 12
        }],
        "edges": []
      }
    """.trimIndent()

    // Home is the start; Ghost is bound to a screen but nothing links to it.
    val MANIFEST_UNREACHABLE = """
      {
        "navVersion": "navgraph",
        "schemaVersion": 1,
        "nodes": [
          {"id": "com.app.Home", "route": "Home", "start": true, "clickTargetFqn": "com.app.HomeScreen"},
          {"id": "com.app.Ghost", "route": "Ghost", "clickTargetFqn": "com.app.GhostScreen"}
        ],
        "edges": []
      }
    """.trimIndent()

    val MANIFEST = """
      {
        "nodes": [
          { "id": "x.Home", "route": "Home", "start": true },
          { "id": "x.Profile", "route": "Profile" }
        ],
        "edges": [ { "from": "x.Home", "to": "x.Profile", "label": "go" } ]
      }
    """.trimIndent()

    val BASELINE = """
      # Navigation 3 baseline — schema 1
      # Generated by the navgraph 'navDump' task. Commit this file; 'navCheck' fails if it drifts. Do not edit by hand.

      dest Home  start
      dest Profile
      edge Home -> Profile  "go"
    """.trimIndent()
  }
}
