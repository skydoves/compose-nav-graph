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
import org.junit.Assert.assertTrue
import org.junit.Test

class NavGraphPluginTest {

  private fun navgraphProject(): Project =
    ProjectBuilder.builder().withName("sample").build().also {
      it.pluginManager.apply("com.github.skydoves.navgraph")
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
    assertEquals("navgraph", project.tasks.getByName("generateNavGraph").group)
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
