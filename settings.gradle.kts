pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
    // The navgraph plugin (com.github.skydoves.navgraph) is supplied by the `includeBuild("compose-nav-graph-gradle")` composite for the
    // actual build, but the IDE resolves the build-script-model plugin marker from a repository — publish it to
    // mavenLocal (0.1.0) so IDE sync (`prepareKotlinBuildScriptModel`) can resolve it.
    mavenLocal()
  }
}

dependencyResolutionManagement {
  // PREFER_PROJECT: the KMP js/wasmJs targets register the nodejs.org repo at the project level, which
  // FAIL_ON_PROJECT_REPOS would reject.
  repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
  repositories {
    google()
    mavenCentral()
    // The navgraph plugin auto-wires its compose-nav-graph-annotations/compose-nav-graph-ksp/compose-nav-graph-testing dependencies as the published
    // com.github.skydoves:0.1.0 coordinates, which a consumer resolves from mavenLocal.
    mavenLocal()
  }
}

rootProject.name = "compose-nav-graph"

// Composite build so its `com.github.skydoves.navgraph` plugin can be applied to the samples here.
includeBuild("compose-nav-graph-gradle")

include(":compose-nav-graph-annotations")
include(":compose-nav-graph-ksp")
include(":compose-nav-graph-testing")
include(":sample")
project(":sample").projectDir = file("samples/sample")

// compose-nav-graph-idea (the IDE plugin) is a SEPARATE build: ./gradlew -p compose-nav-graph-idea buildPlugin
