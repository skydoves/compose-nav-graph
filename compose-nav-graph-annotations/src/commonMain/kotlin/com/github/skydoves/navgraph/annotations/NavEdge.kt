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
package com.github.skydoves.navgraph.annotations

import kotlin.reflect.KClass

/**
 * Declares a navigation transition (edge) between two destinations.
 *
 * navgraph navigation is imperative `backStack.add(...)` inside arbitrary function bodies, so edges are
 * not statically decidable in general and KSP cannot see those call sites. Edges are therefore taken
 * from this explicit annotation (`confidence = ANNOTATED`).
 *
 * Apply on the **source** destination, either its route class declaration or its screen composable.
 * [from] defaults to [Unit], meaning "the route of the element I'm attached to is the source":
 *  - on a **route class** (CLASS) target, the source is that route directly;
 *  - on a **screen composable** (FUNCTION) target, the source is taken from the SAME function's
 *    `@NavDestination(route)`. If the function has no `@NavDestination`, the default `from` is
 *    undefined and `compose-nav-graph-ksp` will report an error — give an explicit [from] instead.
 *
 * Repeatable: a screen with several outgoing transitions carries several `@NavEdge` (the compiler
 * synthesizes a `@NavEdge.Container`; `compose-nav-graph-ksp` must read both forms).
 *
 * ```
 * @NavEdge(to = Profile::class)                 // from = this screen's route
 * @NavEdge(to = Settings::class, label = "menu")
 * @NavDestination(route = Feed::class)
 * @Composable
 * fun FeedScreen() { /* … */ }
 * ```
 *
 * @property to the destination this transition navigates to.
 * @property from the source destination; defaults to [Unit] = the annotated element's own route.
 * @property label an optional human-readable label for the edge (e.g. the triggering action).
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Repeatable
@MustBeDocumented
public annotation class NavEdge(
  val to: KClass<*>,
  val from: KClass<*> = Unit::class,
  val label: String = "",
)
