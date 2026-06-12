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
@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package com.github.skydoves.navgraph.ksp

import com.github.skydoves.navgraph.model.NavGraph
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

private val NAV_KEY_STUB = SourceFile.kotlin(
  "NavKey.kt",
  """
  package androidx.navigation3.runtime
  interface NavKey
  """.trimIndent(),
)

/** Mirrors androidx's `@Preview` FQN + the params the processor reads — the real artifact isn't on the
 *  test classpath, and KSP matches annotations by qualified name only. */
internal val PREVIEW_STUB = SourceFile.kotlin(
  "Preview.kt",
  """
  package androidx.compose.ui.tooling.preview
  @Repeatable
  annotation class Preview(
    val name: String = "",
    val locale: String = "",
  )
  """.trimIndent(),
)

private val DECODER = Json { ignoreUnknownKeys = true }

internal fun compileNavGraph(
  vararg sources: SourceFile,
  options: Map<String, String> = emptyMap(),
): NavGraph {
  val compilation = KotlinCompilation().apply {
    this.sources = sources.toList() + NAV_KEY_STUB
    inheritClassPath = true
    messageOutputStream = System.out
    configureKsp {
      symbolProcessorProviders += NavGraphProcessorProvider()
      processorOptions.putAll(options)
    }
  }
  val result = compilation.compile()
  assertEquals(
    "compilation+ksp should succeed",
    KotlinCompilation.ExitCode.OK,
    result.exitCode,
  )
  val json = compilation.kspSourcesDir.resolve("resources").walkTopDown()
    .firstOrNull { it.name == "nav-graph.json" }
  assertNotNull("nav-graph.json should be emitted", json)
  return DECODER.decodeFromString(NavGraph.serializer(), json!!.readText())
}

internal fun NavGraph.node(route: String) = nodes.single { it.route == route }

internal fun NavGraph.nodeById(id: String) = nodes.single { it.id == id }
