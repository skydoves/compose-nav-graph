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

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Resolves a composable's fully-qualified name to its [KtNamedFunction] and navigates to it. Kept in its own
 * class (the only one that touches Kotlin PSI) so [NavGraphNavigator] never hard-links the Kotlin plugin — this
 * is loaded only when that optional dependency is present. Runs off the EDT (the whole-project scan would
 * otherwise freeze the UI) and navigates back on the UI thread.
 */
internal object KotlinClickTargetResolver {

  fun navigateToFunction(project: Project, fqn: String) {
    ReadAction.nonBlocking<KtNamedFunction?> { findFunctionByFqn(project, fqn) }
      .inSmartMode(project)
      .finishOnUiThread(ModalityState.defaultModalityState()) { it?.navigate(true) }
      .submit(AppExecutorUtil.getAppExecutorService())
  }

  private fun findFunctionByFqn(project: Project, fqn: String): KtNamedFunction? {
    val scope = GlobalSearchScope.projectScope(project)
    for (virtualFile in FileTypeIndex.getFiles(KotlinFileType.INSTANCE, scope)) {
      val ktFile = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: continue
      // findChildrenOfType (not just top-level declarations) so member/nested composables resolve too.
      val match = PsiTreeUtil.findChildrenOfType(ktFile, KtNamedFunction::class.java)
        .firstOrNull { it.fqName?.asString() == fqn }
      if (match != null) return match
    }
    return null
  }
}
