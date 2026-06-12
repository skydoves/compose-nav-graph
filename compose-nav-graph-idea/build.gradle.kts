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
  alias(libs.plugins.kotlin.jvm.idea)
  alias(libs.plugins.intellij.platform)
  alias(libs.plugins.spotless)
}

group = "com.github.skydoves"
version = "0.1.0"

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

dependencies {
  intellijPlatform {
    intellijIdeaCommunity(libs.versions.intellijIdea.get())
    bundledPlugin("org.jetbrains.kotlin")
    bundledPlugin("com.intellij.java")
    pluginVerifier()
    testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
  }
  testImplementation(libs.junit4)
}

intellijPlatform {
  buildSearchableOptions = false
  instrumentCode = true
  pluginConfiguration {
    ideaVersion {
      sinceBuild = "242"
      untilBuild = "261.*"
    }
  }
  pluginVerification {
    ides { recommended() }
    failureLevel = listOf(
      org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
      org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.NOT_DYNAMIC,
    )
  }
}

tasks {
  withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
  }
  withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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
  format("xml") {
    // Only our source XML — never the IDE's `.intellijPlatform/` ivy-descriptor cache.
    target("src/**/*.xml")
    licenseHeaderFile(rootProject.file("../spotless/copyright.xml"), "(<[^!?])")
  }
}
