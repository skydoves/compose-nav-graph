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
package com.github.skydoves.navgraph.idea

import com.github.skydoves.navgraph.idea.model.NavNodeDto
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * Jumps to a node's `@NavDestination` composable. Fast path: open `sourceFile` at `sourceLine` (a hint
 * captured at KSP time; a VFS refresh covers a freshly-generated file). Portable fallback (moved checkout):
 * resolve `clickTargetFqn` to the function via [KotlinClickTargetResolver] — which needs the optional Kotlin
 * plugin, so if it isn't loaded the resolver class can't link and we degrade silently.
 */
internal object NavGraphNavigator {

  fun navigate(project: Project, node: NavNodeDto) {
    ApplicationManager.getApplication().invokeLater {
      val lfs = LocalFileSystem.getInstance()
      val byPath = node.sourceFile
        ?.let { lfs.findFileByPath(it) ?: lfs.refreshAndFindFileByPath(it) }
        ?.takeIf { it.isValid }
      if (byPath != null) {
        OpenFileDescriptor(
          project,
          byPath,
          ((node.sourceLine ?: 1) - 1).coerceAtLeast(0),
          0,
        ).navigate(true)
        return@invokeLater
      }

      val fqn = node.clickTargetFqn ?: return@invokeLater
      try {
        KotlinClickTargetResolver.navigateToFunction(project, fqn)
      } catch (_: LinkageError) {
        // Kotlin plugin absent → the resolver class can't link; the sourceFile fast path is the primary route.
      }
    }
  }
}
