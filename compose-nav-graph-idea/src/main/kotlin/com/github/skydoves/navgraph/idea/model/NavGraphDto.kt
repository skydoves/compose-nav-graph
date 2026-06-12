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
package com.github.skydoves.navgraph.idea.model

/**
 * Plain DTOs mirroring `com.github.skydoves.navgraph.model.*` in `compose-nav-graph-annotations` (the schema source of truth),
 * deserialized from `build/navgraph/nav-graph.json` with the IDE's bundled **Gson** — so the plugin
 * doesn't bundle kotlinx-serialization (avoids conflicting with the IDE's own copy). Defaults make
 * parsing tolerant of older/newer manifests.
 */
internal data class NavGraphDto(
  val navVersion: String = "navgraph",
  val schemaVersion: Int = 1,
  val nodes: List<NavNodeDto> = emptyList(),
  val edges: List<NavEdgeDto> = emptyList(),
)

internal data class NavNodeDto(
  val id: String = "",
  val route: String = "",
  val module: String? = null,
  val clickTargetFqn: String? = null,
  val sourceFile: String? = null,
  val sourceLine: Int? = null,
  val args: List<NavArgDto> = emptyList(),
  val previews: List<NavPreviewDto> = emptyList(),
  val start: Boolean = false,
)

internal data class NavArgDto(
  val name: String = "",
  val type: String = "",
  val typeArguments: List<String> = emptyList(),
  val nullable: Boolean = false,
  val optional: Boolean = false,
  val enum: Boolean = false,
)

internal data class NavPreviewDto(
  val previewName: String = "",
  val previewFqn: String? = null,
  val thumbnail: String? = null,
  val primary: Boolean = false,
)

internal data class NavEdgeDto(
  val from: String = "",
  val to: String = "",
  val args: List<String> = emptyList(),
  val label: String? = null,
  val confidence: String = "ANNOTATED",
)
