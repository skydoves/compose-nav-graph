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
package com.github.skydoves.navgraph.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      NavSampleTheme {
        // The developer owns the back stack as plain Compose state (navgraph's model).
        val backStack = rememberNavBackStack(Home)

        NavDisplay(
          backStack = backStack,
          // `entry` is a member of EntryProviderScope in navgraph 1.1.2 — it resolves via this entryProvider {}
          // receiver and must NOT be imported (importing it gives "Unresolved reference 'entry'").
          entryProvider = entryProvider {
            entry<Home> {
              HomeScreen(
                onOpenFeed = { backStack.add(Feed) },
                onOpenSettings = { backStack.add(Settings) },
              )
            }
            entry<Feed> {
              FeedScreen(onOpenProfile = { userId -> backStack.add(Profile(userId)) })
            }
            entry<Profile> { key ->
              val context = LocalContext.current
              ProfileScreen(
                userId = key.userId,
                onOpenArticle = { id -> backStack.add(Article(articleId = id)) },
                onOpenSettings = { backStack.add(Settings) },
                onOpenProfileDetail = {
                  context.startActivity(Intent(context, ProfileDetailActivity::class.java))
                },
              )
            }
            entry<Article> { key -> ArticleScreen(articleId = key.articleId) }
            entry<Settings> { SettingsScreen() }
          },
        )
      }
    }
  }
}
