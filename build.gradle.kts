plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.jetbrains.compose) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.spotless) apply false
  // Applied (not `apply false`): configures every subproject and wires apiCheck into `check`.
  alias(libs.plugins.kotlin.binary.compatibility)
}

// binary-compatibility-validator: guards the published libraries' public ABI (apiDump/apiCheck). The dogfood
// apps have no public API, so they're ignored.
apiValidation {
  ignoredProjects.addAll(listOf("sample"))
}

// `subprojects` (not `allprojects`) so the root project is skipped — that avoids linting the spotless/ header
// templates and double-processing. compose-nav-graph-gradle and compose-nav-graph-idea carry their own spotless config.
subprojects {
  apply(plugin = rootProject.libs.plugins.spotless.get().pluginId)
  configure<com.diffplug.gradle.spotless.SpotlessExtension> {
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
      licenseHeaderFile(rootProject.file("spotless/copyright.kt"))
    }
    format("kts") {
      target("**/*.kts")
      targetExclude("**/build/**/*.kts")
      licenseHeaderFile(rootProject.file("spotless/copyright.kts"), "(^(?![\\/ ]\\*).*$)")
    }
    format("xml") {
      target("**/*.xml")
      targetExclude("**/build/**/*.xml", "**/.idea/**/*.xml")
      licenseHeaderFile(rootProject.file("spotless/copyright.xml"), "(<[^!?])")
    }
  }
}
