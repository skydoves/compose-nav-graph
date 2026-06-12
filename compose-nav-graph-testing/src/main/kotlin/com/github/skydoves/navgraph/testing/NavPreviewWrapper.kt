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
package com.github.skydoves.navgraph.testing

import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import org.jetbrains.compose.resources.PreviewContextConfigurationEffect

@Composable
public fun NavPreviewWrapper(locale: String? = null, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalInspectionMode provides true) {
    // PreviewContextConfigurationEffect wires Compose-Multiplatform's resource context so `Res.*` resolve in a
    // preview. It lives in `components-resources`, a compileOnly dependency: present for a CMP consumer, ABSENT for
    // a plain-Android (non-CMP) consumer — where invoking it throws NoClassDefFoundError and blanks the entire
    // render. Gate the call on the class actually being on the runtime classpath so plain-Android renders normally.
    if (CMP_RESOURCES_ON_CLASSPATH) {
      PreviewContextConfigurationEffect()
    }
    if (locale.isNullOrBlank()) {
      content()
    } else {
      LocalizedPreview(locale, content)
    }
  }
}

/**
 * Applies a `@Preview(locale = …)` qualifier the same way Android Studio's preview does: a
 * configuration-context override scoped to the composition — NOT a system-wide qualifier change, which
 * (under Robolectric) recreates the host Activity and blanks the captured frame. String and drawable
 * resources below this point resolve against the localized configuration.
 */
@Composable
private fun LocalizedPreview(locale: String, content: @Composable () -> Unit) {
  val baseContext = LocalContext.current
  val baseConfiguration = LocalConfiguration.current
  val configuration = remember(locale, baseConfiguration) {
    // setLocales also derives the configuration's layout direction from the locale.
    Configuration(baseConfiguration).apply {
      setLocales(LocaleList.forLanguageTags(localeTag(locale)))
    }
  }
  val localizedContext = remember(configuration) {
    baseContext.createConfigurationContext(configuration)
  }
  val layoutDirection = if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
    LayoutDirection.Rtl
  } else {
    LayoutDirection.Ltr
  }
  CompositionLocalProvider(
    LocalContext provides localizedContext,
    LocalConfiguration provides configuration,
    LocalLayoutDirection provides layoutDirection,
  ) {
    content()
  }
}

/** `@Preview(locale)` accepts resource-qualifier syntax — map it to a BCP-47 tag:
 *  `"ko"` → `"ko"`, `"fr-rFR"` → `"fr-FR"`, `"b+es+419"` → `"es-419"`. */
private fun localeTag(qualifier: String): String = when {
  qualifier.startsWith("b+") -> qualifier.removePrefix("b+").replace('+', '-')
  else -> qualifier.replace("-r", "-")
}

/** Whether Compose-Multiplatform's `components-resources` is on the runtime classpath (it is a compileOnly
 *  dependency of compose-nav-graph-testing). Resolved once; a plain-Android consumer that never uses CMP resources won't have
 *  it, and must not invoke [PreviewContextConfigurationEffect] — doing so throws NoClassDefFoundError. */
private val CMP_RESOURCES_ON_CLASSPATH: Boolean = runCatching {
  Class.forName(
    "org.jetbrains.compose.resources.AndroidContextProviderKt",
    false,
    NavPreviewRenderer::class.java.classLoader,
  )
}.isSuccess
