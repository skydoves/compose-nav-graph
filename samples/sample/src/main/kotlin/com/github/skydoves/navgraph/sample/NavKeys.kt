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
package com.github.skydoves.navgraph.sample

import androidx.navigation3.runtime.NavKey
import com.github.skydoves.navgraph.annotations.NavGraphRoot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * The app's destinations. Idiomatic navgraph: a `@Serializable sealed interface : NavKey` with
 * `data object`/`data class` implementers. Each implementer's serializable properties ARE its
 * navigation arguments — which is exactly what `compose-nav-graph-ksp` extracts.
 */
@Serializable
sealed interface AppKey : NavKey

/** Start destination. */
@NavGraphRoot
@Serializable
data object Home : AppKey

@Serializable
data object Feed : AppKey

/** A destination with a navigation argument. */
@Serializable
data class Profile(val userId: String) : AppKey

@Serializable
data object Settings : AppKey

/**
 * Exercises the hard arg-extraction cases:
 *  - `@SerialName` rename → arg name must be `"id"`, not `articleId`;
 *  - an **enum** arg with a default → `enum=true`, `optional=true`;
 *  - a **nullable** arg with a default → `nullable=true`, `optional=true`;
 *  - a `@Transient` property → MUST be **excluded** from the nav args.
 */
@Serializable
data class Article(
  @SerialName("id") val articleId: String,
  val section: Section = Section.NEWS,
  val query: String? = null,
  @Transient val analyticsTag: String = "",
) : AppKey {
  // a body backing-field property → kotlinx serializes it, so it IS a nav arg (optional)
  val bookmarked: Boolean = false
}

@Serializable
enum class Section { NEWS, SPORTS, TECH }

/** A declared destination with NO `@NavDestination` — tests the "node exists, click target unknown" path. */
@Serializable
data object Orphan : AppKey

/** A declared destination with NO `@NavDestination` — tests the "node exists, click target unknown" path. */
@Serializable
data object Orphan2 : AppKey
