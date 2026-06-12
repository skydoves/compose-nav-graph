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

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Proves the F4 "Add transition" insertion core ([KotlinNavWriter.writeEdge]) writes correct,
 * idiomatic source: `@NavEdge(to = X::class)` on the right element, the annotation import added
 * once, and a cross-package target imported while a same-package target is not. Uses the
 * bundled Kotlin plugin's PSI on an in-memory fixture.
 */
class KotlinNavWriterTest : BasePlatformTestCase() {

  fun testAddEdgeOnScreenComposableSamePackageTarget() {
    val file = myFixture.configureByText(
      "FeedScreen.kt",
      """
      package com.example.app

      import androidx.compose.runtime.Composable
      import com.github.skydoves.navgraph.annotations.NavDestination

      @NavDestination(route = Feed::class)
      @Composable
      fun FeedScreen() {
      }
      """.trimIndent(),
    ) as KtFile
    val fn = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)
      .first { it.name == "FeedScreen" }

    KotlinNavWriter.writeEdge(project, fn, "com.example.app.Settings", "Settings")

    val text = file.text
    assertTrue("@NavEdge inserted:\n$text", text.contains("@NavEdge(to = Settings::class)"))
    assertTrue(
      "NavEdge imported:\n$text",
      text.contains("import com.github.skydoves.navgraph.annotations.NavEdge"),
    )
    assertFalse(
      "same-package target not imported:\n$text",
      text.contains("import com.example.app.Settings"),
    )
    // existing @NavDestination is preserved
    assertTrue(
      "@NavDestination preserved:\n$text",
      text.contains("@NavDestination(route = Feed::class)"),
    )
  }

  fun testAddEdgeOnOrphanNavKeyCrossPackageTarget() {
    val file = myFixture.configureByText(
      "Keys.kt",
      """
      package com.example.app

      import androidx.navigation3.runtime.NavKey
      import kotlinx.serialization.Serializable

      @Serializable
      data object Orphan : NavKey
      """.trimIndent(),
    ) as KtFile
    val obj = PsiTreeUtil.findChildrenOfType(file, KtClassOrObject::class.java)
      .first { it.name == "Orphan" }

    KotlinNavWriter.writeEdge(project, obj, "com.example.other.Home", "Home")

    val text = file.text
    assertTrue(
      "@NavEdge inserted on the NavKey:\n$text",
      text.contains("@NavEdge(to = Home::class)"),
    )
    assertTrue(
      "NavEdge imported:\n$text",
      text.contains("import com.github.skydoves.navgraph.annotations.NavEdge"),
    )
    assertTrue(
      "cross-package target imported:\n$text",
      text.contains("import com.example.other.Home"),
    )
  }

  fun testWireUpAppendsDestinationStubToKeyFile() {
    val file = myFixture.configureByText(
      "Keys.kt",
      """
      package com.example.app

      import androidx.navigation3.runtime.NavKey
      import kotlinx.serialization.Serializable

      @Serializable
      data object Orphan : NavKey
      """.trimIndent(),
    ) as KtFile
    val obj = PsiTreeUtil.findChildrenOfType(file, KtClassOrObject::class.java)
      .first { it.name == "Orphan" }

    KotlinNavWriter.writeDestinationStub(project, obj, "Orphan")

    val text = file.text
    assertTrue(
      "@NavDestination stub:\n$text",
      text.contains("@NavDestination(route = Orphan::class)"),
    )
    assertTrue("screen fn:\n$text", text.contains("fun OrphanScreen()"))
    assertTrue(
      "NavDestination imported:\n$text",
      text.contains("import com.github.skydoves.navgraph.annotations.NavDestination"),
    )
    assertTrue(
      "Composable imported:\n$text",
      text.contains("import androidx.compose.runtime.Composable"),
    )
    assertTrue("original key preserved:\n$text", text.contains("data object Orphan : NavKey"))
  }

  fun testCreateDestinationAddsKeyAndStubMirroringSupertype() {
    val file = myFixture.configureByText(
      "NavKeys.kt",
      """
      package com.example.app

      import androidx.navigation3.runtime.NavKey
      import kotlinx.serialization.Serializable

      @Serializable
      sealed interface AppKey : NavKey

      @Serializable
      data object Home : AppKey
      """.trimIndent(),
    ) as KtFile
    val home = PsiTreeUtil.findChildrenOfType(file, KtClassOrObject::class.java)
      .first { it.name == "Home" }

    KotlinNavWriter.writeNewDestination(project, home, "Dashboard")

    val text = file.text
    assertTrue(
      "new key mirrors sibling supertype:\n$text",
      text.contains("data object Dashboard : AppKey"),
    )
    assertTrue(
      "@NavDestination stub:\n$text",
      text.contains("@NavDestination(route = Dashboard::class)"),
    )
    assertTrue("screen fn:\n$text", text.contains("fun DashboardScreen()"))
    assertTrue(
      "NavDestination imported:\n$text",
      text.contains("import com.github.skydoves.navgraph.annotations.NavDestination"),
    )
    assertTrue(
      "Composable imported:\n$text",
      text.contains("import androidx.compose.runtime.Composable"),
    )
  }

  fun testAddEdgeIsIdempotentForSamePair() {
    val file = myFixture.configureByText(
      "FeedScreen.kt",
      """
      package com.example.app

      import androidx.compose.runtime.Composable
      import com.github.skydoves.navgraph.annotations.NavDestination

      @NavDestination(route = Feed::class)
      @Composable
      fun FeedScreen() {
      }
      """.trimIndent(),
    ) as KtFile
    val fn = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)
      .first { it.name == "FeedScreen" }

    KotlinNavWriter.writeEdge(project, fn, "com.example.app.Settings", "Settings")
    // re-drag → must no-op
    KotlinNavWriter.writeEdge(project, fn, "com.example.app.Settings", "Settings")

    assertEquals(
      "exactly one @NavEdge after a re-drag:\n${file.text}",
      1,
      Regex("@NavEdge").findAll(file.text).count(),
    )
  }

  fun testAddEdgeUsesFqnWhenSimpleNameCollides() {
    val file = myFixture.configureByText(
      "FeedScreen.kt",
      """
      package com.example.app

      import androidx.compose.runtime.Composable
      import com.other.Settings
      import com.github.skydoves.navgraph.annotations.NavDestination

      @NavDestination(route = Feed::class)
      @Composable
      fun FeedScreen() {
      }
      """.trimIndent(),
    ) as KtFile
    val fn = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)
      .first { it.name == "FeedScreen" }

    KotlinNavWriter.writeEdge(project, fn, "com.example.app.Settings", "Settings")

    val text = file.text
    assertTrue(
      "qualifies the target on a name collision:\n$text",
      text.contains("@NavEdge(to = com.example.app.Settings::class)"),
    )
    assertFalse(
      "did not add a clashing import:\n$text",
      text.contains("import com.example.app.Settings"),
    )
  }

  fun testCreateDestinationFallsBackToNavKeyForGenericSupertype() {
    val file = myFixture.configureByText(
      "Keys.kt",
      """
      package com.example.app

      import androidx.navigation3.runtime.NavKey
      import kotlinx.serialization.Serializable

      @Serializable
      data class Home(val x: Int) : BaseKey<Home>
      """.trimIndent(),
    ) as KtFile
    val home = PsiTreeUtil.findChildrenOfType(file, KtClassOrObject::class.java)
      .first { it.name == "Home" }

    KotlinNavWriter.writeNewDestination(project, home, "Dashboard")

    val text = file.text
    assertTrue(
      "generic supertype → fall back to NavKey:\n$text",
      text.contains("data object Dashboard : NavKey"),
    )
    assertTrue(
      "NavKey imported:\n$text",
      text.contains("import androidx.navigation3.runtime.NavKey"),
    )
  }

  fun testWireUpIsIdempotent() {
    val file = myFixture.configureByText(
      "Keys.kt",
      """
      package com.example.app

      import androidx.navigation3.runtime.NavKey
      import kotlinx.serialization.Serializable

      @Serializable
      data object Orphan : NavKey
      """.trimIndent(),
    ) as KtFile
    val obj = PsiTreeUtil.findChildrenOfType(file, KtClassOrObject::class.java)
      .first { it.name == "Orphan" }

    KotlinNavWriter.writeDestinationStub(project, obj, "Orphan")
    KotlinNavWriter.writeDestinationStub(project, obj, "Orphan") // re-click → must no-op

    assertEquals(
      "exactly one stub:\n${file.text}",
      1,
      Regex("@NavDestination").findAll(file.text).count(),
    )
  }
}
