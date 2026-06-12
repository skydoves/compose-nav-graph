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
plugins {
  `kotlin-dsl`
  alias(libs.plugins.vanniktech.publish)
  alias(libs.plugins.spotless)
}

group = project.property("GROUP") as String
version = project.property("VERSION_NAME") as String

repositories {
  google()
  mavenCentral()
}

dependencies {
  implementation(libs.kotlinx.serialization.json)
  testImplementation(libs.junit4)
}

tasks.withType<Test>().configureEach { useJUnit() }

val generateNavGraphVersion = tasks.register("generateNavGraphVersion") {
  val outDir = layout.buildDirectory.dir("generated/navgraphversion")
  val versionValue = version.toString()
  // Without this input the task stays UP-TO-DATE across a VERSION_NAME bump, leaving a stale
  // resource — consumers would then auto-wire the previous release's artifacts.
  inputs.property("version", versionValue)
  outputs.dir(outDir)
  doLast {
    outDir.get().file("navgraph.version").asFile.apply {
      parentFile.mkdirs()
      writeText(versionValue)
    }
  }
}
sourceSets.named("main") { resources.srcDir(layout.buildDirectory.dir("generated/navgraphversion")) }
tasks.matching { it.name == "processResources" || it.name == "sourcesJar" }
  .configureEach { dependsOn(generateNavGraphVersion) }

gradlePlugin {
  plugins {
    create("navgraph") {
      id = "com.github.skydoves.navgraph"
      displayName = "Compose Navigation Graph"
      description = "Statically extracts your Compose navigation graph and renders device-free thumbnails."
      implementationClass = "com.github.skydoves.navgraph.gradle.NavGraphGradlePlugin"
    }
  }
}

spotless {
  kotlin {
    target("**/*.kt")
    targetExclude("**/build/**/*.kt")
    ktlint().editorConfigOverride(
      mapOf(
        "indent_size" to 2,
        "continuation_indent_size" to 2,
        "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
      ),
    )
    licenseHeaderFile(rootProject.file("../spotless/copyright.kt"))
  }
  format("kts") {
    target("**/*.kts")
    targetExclude("**/build/**/*.kts")
    licenseHeaderFile(rootProject.file("../spotless/copyright.kts"), "(^(?![\\/ ]\\*).*$)")
  }
}
