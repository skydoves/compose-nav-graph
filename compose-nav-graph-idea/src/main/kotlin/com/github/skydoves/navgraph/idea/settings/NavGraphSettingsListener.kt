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
package com.github.skydoves.navgraph.idea.settings

import com.intellij.util.messages.Topic

/**
 * Notifies subscribers (the open tool window) that [NavGraphSettings] changed, so the canvas can re-read the
 * theme and repaint live — no IDE restart, no tool-window reopen.
 *
 * Published from [NavGraphSettingsConfigurable.apply]; the tool window subscribes on the project message bus,
 * scoped to its content [com.intellij.openapi.Disposable] so the subscription dies with the tool window.
 */
internal fun interface NavGraphSettingsListener {

  fun settingsChanged()

  companion object {
    val TOPIC: Topic<NavGraphSettingsListener> =
      Topic.create("NavGraph Graph settings changed", NavGraphSettingsListener::class.java)
  }
}
