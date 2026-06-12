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
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.vanniktech.publish)
}

android {
  namespace = "com.github.skydoves.navgraph.testing"
  compileSdk = 36

  defaultConfig {
    minSdk = 24
  }

  buildFeatures { compose = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  explicitApi()
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))

  api(libs.robolectric)
  api(libs.androidx.compose.ui.test.junit4)
  api(libs.androidx.compose.ui.test.manifest)
  api(libs.androidx.activity.compose)
  api(libs.junit4)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling)

  compileOnly(libs.compose.components.resources)
}
