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
 * Marks the graph's start destination, highlighted in the flow map. Attach to the start route
 * class declaration (no argument), or to a screen composable / any element with an explicit [route]. On a
 * FUNCTION target with no [route], the route is taken from the same function's `@NavDestination`.
 *
 * Assumes a **single** start destination per project.
 *
 * @property route the start destination; defaults to [Unit] = the route class this is attached to.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@MustBeDocumented
public annotation class NavGraphRoot(val route: KClass<*> = Unit::class)
