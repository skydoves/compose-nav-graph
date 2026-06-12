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
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.ImportPath

/**
 * Writes navgraph boilerplate (Map→Code, F4) via Kotlin PSI. Kept in its own class — the only
 * writer that touches `org.jetbrains.kotlin.psi.*` — so callers never hard-link the optional
 * Kotlin plugin: invoke behind a `try { } catch (_: LinkageError) {}` exactly like
 * [KotlinClickTargetResolver]. The whole-project FQN scan runs off the EDT (it would freeze the
 * UI); the small edit + save runs back on the UI thread inside its own write command, wrapped so
 * a malformed-input/PSI failure degrades to a balloon instead of an IDE fatal-error dialog.
 * `onDone(true)` = the edit landed; `onDone(false)` = the target wasn't found or the write threw.
 */
internal object KotlinNavWriter {

  private val LOG = Logger.getInstance(KotlinNavWriter::class.java)

  private const val NAV_EDGE_FQN = "com.github.skydoves.navgraph.annotations.NavEdge"
  private const val NAV_DESTINATION_FQN = "com.github.skydoves.navgraph.annotations.NavDestination"
  private const val COMPOSABLE_FQN = "androidx.compose.runtime.Composable"
  private const val SERIALIZABLE_FQN = "kotlinx.serialization.Serializable"
  private const val NAV_KEY_FQN = "androidx.navigation3.runtime.NavKey"

  // M2: Add transition — @NavEdge on the source screen (or its NavKey, for an orphan source)

  fun addEdge(
    project: Project,
    sourceFqn: String,
    onClass: Boolean,
    targetFqn: String,
    targetSimpleName: String,
    onDone: (Boolean) -> Unit,
  ) {
    ReadAction.nonBlocking<KtModifierListOwner?> {
      if (onClass) findClassByFqn(project, sourceFqn) else findFunctionByFqn(project, sourceFqn)
    }
      .inSmartMode(project)
      .finishOnUiThread(ModalityState.defaultModalityState()) { owner ->
        onDone(
          owner != null && owner.isValid &&
            tryWrite { writeEdge(project, owner, targetFqn, targetSimpleName) },
        )
      }
      .submit(AppExecutorUtil.getAppExecutorService())
  }

  /**
   * Synchronous insertion core (EDT, own write command). Split out so it's unit-testable on a
   * fixture file.
   */
  internal fun writeEdge(
    project: Project,
    owner: KtModifierListOwner,
    targetFqn: String,
    targetSimpleName: String,
  ) {
    val ktFile = owner.containingKtFile
    WriteCommandAction.runWriteCommandAction(project) {
      // Idempotent: if this exact transition is already annotated on the source, do nothing
      // (no duplicate).
      val already = owner.annotationEntries.any {
        it.shortName?.asString() == "NavEdge" && it.text.contains("$targetSimpleName::class")
      }
      if (!already) {
        val factory = KtPsiFactory(project)
        // Use the simple name + import normally; fall back to the FQN if a different same-named
        // type is imported.
        val ref = ktFile.classReference(targetSimpleName, targetFqn)
        val added = owner.addAnnotationEntry(
          factory.createAnnotationEntry("@NavEdge(to = $ref::class)"),
        )
        ktFile.ensureImport(factory, NAV_EDGE_FQN)
        if (ref == targetSimpleName) ktFile.ensureImport(factory, targetFqn)
        CodeStyleManager.getInstance(project).reformat(added)
        ktFile.saveToDisk(project)
      }
    }
  }

  // M3: Wire this up — scaffold a @NavDestination screen for an orphan NavKey

  fun addDestinationForKey(
    project: Project,
    keyFqn: String,
    route: String,
    onDone: (Boolean) -> Unit,
  ) {
    ReadAction.nonBlocking<KtClassOrObject?> { findClassByFqn(project, keyFqn) }
      .inSmartMode(project)
      .finishOnUiThread(ModalityState.defaultModalityState()) { keyClass ->
        onDone(
          keyClass != null && keyClass.isValid &&
            tryWrite { writeDestinationStub(project, keyClass, route) },
        )
      }
      .submit(AppExecutorUtil.getAppExecutorService())
  }

  /**
   * Synchronous core (EDT): append a `<route>Screen` composable to the NavKey's file
   * (idempotent on route).
   */
  internal fun writeDestinationStub(project: Project, keyClass: KtClassOrObject, route: String) {
    val ktFile = keyClass.containingKtFile
    WriteCommandAction.runWriteCommandAction(project) {
      if (!ktFile.hasDestinationFor(route)) {
        val factory = KtPsiFactory(project)
        ktFile.add(factory.createNewLine())
        val added = ktFile.add(factory.createDeclaration<KtNamedFunction>(destinationStub(route)))
        ktFile.ensureImport(factory, NAV_DESTINATION_FQN)
        ktFile.ensureImport(factory, COMPOSABLE_FQN)
        // only the new function, to keep the diff minimal
        CodeStyleManager.getInstance(project).reformat(added)
        ktFile.saveToDisk(project)
      }
    }
  }

  // M4: Add destination — scaffold a new NavKey + its @NavDestination screen

  /**
   * Creates a new `@Serializable data object <name>` next to [referenceKeyFqn] (inheriting its
   * NavKey supertype, so a sealed hierarchy stays intact — and, since the new key lands in the
   * reference key's own file, it stays in the same module, which keeps a sealed parent legal)
   * plus the screen stub. [referenceKeyFqn] is a sibling key from the same graph that anchors
   * the file, package, and supertype.
   */
  fun createDestination(
    project: Project,
    name: String,
    referenceKeyFqn: String,
    onDone: (Boolean) -> Unit,
  ) {
    ReadAction.nonBlocking<KtClassOrObject?> { findClassByFqn(project, referenceKeyFqn) }
      .inSmartMode(project)
      .finishOnUiThread(ModalityState.defaultModalityState()) { refKey ->
        onDone(
          refKey != null && refKey.isValid &&
            tryWrite { writeNewDestination(project, refKey, name) },
        )
      }
      .submit(AppExecutorUtil.getAppExecutorService())
  }

  /**
   * Synchronous core (EDT): append a new NavKey (mirroring [referenceKey]'s supertype) + its
   * screen stub.
   */
  internal fun writeNewDestination(project: Project, referenceKey: KtClassOrObject, name: String) {
    val ktFile = referenceKey.containingKtFile
    // Mirror the sibling's supertype only when it's a single, simple (non-generic, non-qualified)
    // type — so the new key joins the same (sealed) hierarchy. Anything ambiguous
    // (multiple/generic/qualified/none) falls back to a plain `NavKey`, which always compiles
    // and is still a valid destination.
    val singleSuper = referenceKey.superTypeListEntries.singleOrNull()?.typeReference?.text
    val base = singleSuper?.takeUnless { it.contains('<') || it.contains('.') } ?: "NavKey"
    WriteCommandAction.runWriteCommandAction(project) {
      if (!ktFile.hasDestinationFor(name)) {
        val factory = KtPsiFactory(project)
        ktFile.add(factory.createNewLine())
        val keyEl = ktFile.add(
          factory.createDeclaration<KtClassOrObject>("@Serializable\ndata object $name : $base"),
        )
        ktFile.add(factory.createNewLine())
        val fnEl = ktFile.add(factory.createDeclaration<KtNamedFunction>(destinationStub(name)))
        ktFile.ensureImport(factory, SERIALIZABLE_FQN)
        ktFile.ensureImport(factory, NAV_DESTINATION_FQN)
        ktFile.ensureImport(factory, COMPOSABLE_FQN)
        if (base == "NavKey") ktFile.ensureImport(factory, NAV_KEY_FQN)
        CodeStyleManager.getInstance(project).reformat(keyEl)
        CodeStyleManager.getInstance(project).reformat(fnEl)
        ktFile.saveToDisk(project)
      }
    }
  }

  /**
   * The screen-composable stub, including the one wiring step KSP can't do (the `entry<>`
   * registration).
   */
  private fun destinationStub(route: String): String {
    val todo = "// TODO: render the $route screen, then register it in your NavDisplay:  " +
      "entry<$route> { ${route}Screen() }"
    return """
    @NavDestination(route = $route::class)
    @Composable
    fun ${route}Screen() {
      $todo
    }
    """.trimIndent()
  }

  /**
   * True if this file already declares a `@NavDestination(route = <route>::class)` — keeps
   * scaffolding idempotent.
   */
  private fun KtFile.hasDestinationFor(route: String): Boolean =
    PsiTreeUtil.findChildrenOfType(this, KtNamedFunction::class.java).any { fn ->
      fn.annotationEntries.any {
        it.shortName?.asString() == "NavDestination" && it.text.contains("$route::class")
      }
    }

  /**
   * The reference to write for a target type: its simple name normally, but the fully-qualified
   * name when a DIFFERENT type with the same simple name is already imported (unaliased) —
   * otherwise the short name would resolve to the wrong type. Avoids needing the
   * resolution-context-dependent ShortenReferences (K1/K2-fragile).
   */
  private fun KtFile.classReference(simpleName: String, fqn: String): String {
    val collision = importDirectives.any { d ->
      d.aliasName == null && d.importedFqName?.let {
        it.shortName().asString() == simpleName && it.asString() != fqn
      } == true
    }
    return if (collision) fqn else simpleName
  }

  /**
   * Adds `import <fqn>` unless it's already imported (unaliased) or lives in the file's own
   * package.
   */
  private fun KtFile.ensureImport(factory: KtPsiFactory, fqn: String) {
    val fq = FqName(fqn)
    if (fq.parent() == packageFqName) return // same package → no import needed
    if (importDirectives.any { it.aliasName == null && it.importedFqName == fq }) return
    val directive = factory.createImportDirective(ImportPath(fq, false))
    val list = importList
    if (list != null) {
      list.add(directive)
    } else {
      val anchor = packageDirective
      if (anchor != null) addAfter(directive, anchor) else addBefore(directive, firstChild)
    }
  }

  /**
   * Flush just this file's document to disk (Gradle/KSP regen reads disk), not every dirty
   * editor.
   */
  private fun KtFile.saveToDisk(project: Project) {
    PsiDocumentManager.getInstance(project).getDocument(this)?.let {
      FileDocumentManager.getInstance().saveDocument(it)
    }
  }

  /**
   * Runs an EDT PSI write, converting an input/PSI failure into `false` + a log (never an IDE
   * fatal dialog).
   */
  private inline fun tryWrite(block: () -> Unit): Boolean = try {
    block()
    true
  } catch (e: ProcessCanceledException) {
    throw e
  } catch (t: Throwable) {
    LOG.warn("navgraph Map→Code write failed", t)
    false
  }

  private fun findFunctionByFqn(project: Project, fqn: String): KtNamedFunction? {
    val scope = GlobalSearchScope.projectScope(project)
    for (virtualFile in FileTypeIndex.getFiles(KotlinFileType.INSTANCE, scope)) {
      val ktFile = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: continue
      val match = PsiTreeUtil.findChildrenOfType(ktFile, KtNamedFunction::class.java)
        .firstOrNull { it.fqName?.asString() == fqn }
      if (match != null) return match
    }
    return null
  }

  private fun findClassByFqn(project: Project, fqn: String): KtClassOrObject? {
    val scope = GlobalSearchScope.projectScope(project)
    for (virtualFile in FileTypeIndex.getFiles(KotlinFileType.INSTANCE, scope)) {
      val ktFile = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: continue
      val match = PsiTreeUtil.findChildrenOfType(ktFile, KtClassOrObject::class.java)
        .firstOrNull { it.fqName?.asString() == fqn }
      if (match != null) return match
    }
    return null
  }
}
