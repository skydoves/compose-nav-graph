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
 * Links a `@Preview` composable to the route it depicts, so the generated nav graph can use that
 * preview's rendered image as the node's thumbnail.
 *
 * There is no intrinsic connection between a `@Preview` function and an `entry<Route> { … }` — no
 * tool can infer it. This annotation is that explicit, refactor-safe link. The Gradle render
 * companion reads it at render time via JVM reflection on the discovered preview, so
 * it must be [AnnotationRetention.RUNTIME] (KSP reads it at compile time regardless). Reflection cannot
 * see `CLASS`/`BINARY`-retained annotations.
 *
 * ```
 * @NavPreview(route = Profile::class, primary = true)
 * @Preview
 * @Composable
 * fun ProfileScreenPreview() { /* … */ }
 * ```
 *
 * @property route the destination route class this preview visualizes.
 * @property primary when a route has several previews (light/dark, empty/loaded), marks the one to
 *   use as the canonical thumbnail. Exactly one preview per route should set this `true`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
public annotation class NavPreview(val route: KClass<*>, val primary: Boolean = false)
