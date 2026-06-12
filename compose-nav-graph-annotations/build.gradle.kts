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
@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.vanniktech.publish)
}

// The annotations + the @Serializable nav-graph.json model live in commonMain, so any Compose-Multiplatform
// target can apply them.
kotlin {
  explicitApi()

  jvm {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
  }
  androidTarget {
    publishLibraryVariants("release")
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
  }
  iosArm64()
  iosSimulatorArm64()
  iosX64()
  js(IR) {
    browser()
    nodejs()
  }
  wasmJs {
    browser()
    nodejs()
  }

  sourceSets {
    commonMain.dependencies {
      api(libs.kotlinx.serialization.json)
    }
  }
}

android {
  namespace = "com.github.skydoves.navgraph.annotations"
  compileSdk = 36
  defaultConfig {
    minSdk = 21
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}
