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
 * Marks the top-level composable that renders a navigation destination ([route]). Clicking the
 * route's node in the flow map jumps here.
 *
 * This is required because the route→composable wiring lives inside an `entry<Route> { Screen() }`
 * lambda — a **function body**, which KSP cannot read. KSP instead reads the fully-qualified name of
 * the function this annotation is attached to and records it as the node's click target.
 *
 * ```
 * @NavDestination(route = Profile::class)
 * @Composable
 * fun ProfileScreen(state: ProfileState) { /* … */ }
 * ```
 *
 * @property route the destination route class this composable renders. Any class works; it does
 *   not need to implement `NavKey`.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
public annotation class NavDestination(val route: KClass<*>)
