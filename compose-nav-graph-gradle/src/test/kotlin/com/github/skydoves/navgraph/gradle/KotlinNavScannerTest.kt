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
package com.github.skydoves.navgraph.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the lexical scanner behind `EdgeConfidence.INFERRED`. The two shapes it has to get right are taken verbatim
 * from the shipped samples: `samples/sample`'s `backStack.add(Feed)` and the KotlinConf app's
 * `navigator.add(SessionScreen(it))` — different receivers, same transition.
 */
class KotlinNavScannerTest {

  private fun edges(
    source: String,
    destinations: List<DestinationHint> = emptyList(),
  ): List<Pair<String, String>> =
    KotlinNavScanner.scanKotlinFile(source, destinations = destinations)
      .edges.map { it.fromRef to it.toRef }

  @Test
  fun findsBackStackAddInsideEntryBlocks() {
    // The shape of samples/sample/.../MainActivity.kt.
    val source = """
      package com.app

      class MainActivity {
        fun onCreate() {
          val backStack = rememberNavBackStack(Home)
          NavDisplay(
            backStack = backStack,
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
              entry<Settings> { SettingsScreen() }
            },
          )
        }
      }
    """.trimIndent()

    assertEquals(
      listOf("Home" to "Feed", "Home" to "Settings", "Feed" to "Profile"),
      edges(source),
    )
  }

  @Test
  fun rememberNavBackStackIsNotATransition() {
    // `rememberNavBackStack(Home)` declares the start destination; it sits outside every entry block AND is not a
    // nav call, so it must not become a self-edge or an edge from whatever scope encloses it.
    val source = """
      package com.app
      fun App() {
        val backStack = rememberNavBackStack(Home)
        entryProvider { entry<Home> { HomeScreen() } }
      }
    """.trimIndent()
    assertEquals(emptyList<Pair<String, String>>(), edges(source))
  }

  @Test
  fun matchesByMethodNameNotReceiverName() {
    // The KotlinConf app routes everything through its own `Navigator` wrapper, so a receiver-name check
    // (`backStack.`) would find nothing here.
    val source = """
      package org.jetbrains.kotlinconf.navigation
      private fun EntryProviderScope<AppRoute>.screens(navigator: Navigator) {
        entry<ScheduleScreen>(metadata = noAnimationMetadata) {
          ScheduleScreen(
            onSession = { navigator.add(SessionScreen(it)) },
            onPrivacyNoticeNeeded = { navigator.add(AppPrivacyNoticePrompt) },
          )
        }
        entry<StartPrivacyNoticeScreen> {
          AppPrivacyNoticePrompt(
            onRejectNotice = { navigator.set(ScheduleScreen) },
            onAppTermsOfUse = { navigator.add(AppTermsOfUseScreen) },
          )
        }
      }
    """.trimIndent()

    assertEquals(
      listOf(
        "ScheduleScreen" to "SessionScreen",
        "ScheduleScreen" to "AppPrivacyNoticePrompt",
        "StartPrivacyNoticeScreen" to "ScheduleScreen",
        "StartPrivacyNoticeScreen" to "AppTermsOfUseScreen",
      ),
      edges(source),
    )
  }

  @Test
  fun ignoresCommentedOutNavigation() {
    val source = """
      package com.app
      fun App() {
        entryProvider {
          entry<Home> {
            // backStack.add(LineComment)
            /* backStack.add(BlockComment) */
            /* nested /* backStack.add(Nested) */ still a comment */
            backStack.add(Real)
          }
        }
      }
    """.trimIndent()
    assertEquals(listOf("Home" to "Real"), edges(source))
  }

  @Test
  fun ignoresNavigationInsideStringLiterals() {
    val source = """
      package com.app
      fun App() {
        entryProvider {
          entry<Home> {
            log("backStack.add(Fake)")
            log(${"\"\"\""}also backStack.add(RawFake) and a stray { brace${"\"\"\""})
            backStack.add(Real)
          }
        }
      }
    """.trimIndent()
    assertEquals(listOf("Home" to "Real"), edges(source))
  }

  @Test
  fun bracesInsideStringsAndCharsDoNotBreakScopes() {
    // An unbalanced `{` in a string or a `'}'` char literal would otherwise swallow or truncate the entry block.
    val source = """
      package com.app
      fun App() {
        entryProvider {
          entry<Home> {
            val open = "{"
            val close = '}'
            val json = "{\"k\": \"v\"}"
            backStack.add(Feed)
          }
          entry<Feed> { backStack.add(Profile) }
        }
      }
    """.trimIndent()
    assertEquals(listOf("Home" to "Feed", "Feed" to "Profile"), edges(source))
  }

  @Test
  fun stringTemplatesKeepBracesBalanced() {
    val source = """
      package com.app
      fun App() {
        entryProvider {
          entry<Home> {
            val label = "count: ${"\${"}if (n > 0) "{many}" else "{none}"}"
            backStack.add(Feed)
          }
        }
      }
    """.trimIndent()
    assertEquals(listOf("Home" to "Feed"), edges(source))
  }

  @Test
  fun readsNavigationInsideAStringTemplate() {
    // A template's contents really are code, so a call there is a genuine call site.
    val source = """
      package com.app
      fun App() {
        entryProvider {
          entry<Home> { log("x ${"\${"}backStack.add(Feed)} y") }
        }
      }
    """.trimIndent()
    assertEquals(listOf("Home" to "Feed"), edges(source))
  }

  @Test
  fun innermostScopeWins() {
    val source = """
      package com.app
      fun App() {
        entry<Outer> {
          entry<Inner> { backStack.add(Target) }
        }
      }
    """.trimIndent()
    assertEquals(listOf("Inner" to "Target"), edges(source))
  }

  @Test
  fun readsQualifiedAndNamedArguments() {
    val source = """
      package com.app
      fun App() {
        entry<Home> {
          backStack.add(com.app.other.Detail(id))
          navController.navigate(route = Settings)
        }
      }
    """.trimIndent()
    assertEquals(
      listOf("Home" to "com.app.other.Detail", "Home" to "Settings"),
      edges(source),
    )
  }

  @Test
  fun supportsNavigation2TypedComposableBlocks() {
    val source = """
      package com.app
      fun Graph() {
        NavHost(navController, startDestination = Home) {
          composable<Home> { HomeScreen(onOpen = { navController.navigate(Detail(it)) }) }
        }
      }
    """.trimIndent()
    assertEquals(listOf("Home" to "Detail"), edges(source))
  }

  @Test
  fun usesNavDestinationBodiesAsScopes() {
    // Apps that pass the back stack into the screen have no `entry<T>` call site to read.
    val source = """
      package com.app

      @NavDestination(route = Home::class)
      @Composable
      fun HomeScreen(backStack: NavBackStack) {
        Button(onClick = { backStack.add(Feed) })
      }

      @NavDestination(route = Feed::class)
      @Composable
      fun FeedScreen(backStack: NavBackStack) = Column {
        Button(onClick = { backStack.add(Profile) })
      }
    """.trimIndent()

    val destinations = listOf(
      DestinationHint("com.app.Home", "HomeScreen", 5),
      DestinationHint("com.app.Feed", "FeedScreen", 11),
    )
    assertEquals(
      listOf("com.app.Home" to "Feed", "com.app.Feed" to "Profile"),
      edges(source, destinations),
    )
  }

  @Test
  fun defaultParameterLambdasAreNotMistakenForTheBody() {
    val source = """
      package com.app

      @NavDestination(route = Home::class)
      @Composable
      fun HomeScreen(onNope: () -> Unit = { backStack.add(FromDefault) }) {
        Button(onClick = { backStack.add(Real) })
      }
    """.trimIndent()
    assertEquals(
      listOf("com.app.Home" to "Real"),
      edges(source, listOf(DestinationHint("com.app.Home", "HomeScreen", 5))),
    )
  }

  @Test
  fun readsPackageAndImports() {
    val source = """
      package com.app.feature

      import com.app.routes.Detail
      import com.app.routes.Profile as ProfileRoute
      import com.app.routes.*
      // import com.app.routes.Commented
    """.trimIndent()

    val scan = KotlinNavScanner.scanKotlinFile(source)
    assertEquals("com.app.feature", scan.packageName)
    assertEquals("com.app.routes.Detail", scan.imports["Detail"])
    assertEquals("com.app.routes.Profile", scan.imports["ProfileRoute"])
    assertNull(scan.imports["Commented"])
    // A star import contributes no simple name; the caller falls back to a unique-suffix match.
    assertEquals(2, scan.imports.size)
  }

  @Test
  fun reportsTheCallSiteLine() {
    val source = "package com.app\nfun App() {\n  entry<Home> {\n    backStack.add(Feed)\n  }\n}\n"
    assertEquals(4, KotlinNavScanner.scanKotlinFile(source).edges.single().line)
  }

  @Test
  fun toleratesAScopeWithNoBlock() {
    val source = "package com.app\nfun App() {\n  val t = entry<Home>\n}\n"
    assertEquals(emptyList<Pair<String, String>>(), edges(source))
  }

  @Test
  fun anApostropheInABacktickIdentifierDoesNotSwallowTheScope() {
    // A lone `'` used to open a phantom char literal that blanked the rest of the line — including its `{` — so
    // `entry<Home>` closed early and everything after the `if` was silently lost.
    val source = """
      package com.app
      fun App() {
        entry<Home> {
          if (`isn't ready`) {
            backStack.add(Feed)
          }
          backStack.add(Settings)
        }
        entry<Profile> { backStack.add(Article) }
      }
    """.trimIndent()
    assertEquals(
      listOf("Home" to "Feed", "Home" to "Settings", "Profile" to "Article"),
      edges(source),
    )
  }

  @Test
  fun anExpressionBodiedDestinationDoesNotSwallowTheNextFunction() {
    // `= Text(...)` has no block of its own; the search used to run on into the following declaration and
    // attribute its body to this route — losing the real edge and publishing a wrong one.
    val source = """
      package com.app

      @NavDestination(route = Splash::class)
      @Composable
      fun SplashScreen(state: State) = Text(state.title)

      @NavDestination(route = Detail::class)
      @Composable
      fun DetailScreen(backStack: NavBackStack) {
        Button(onClick = { backStack.add(Related) })
      }
    """.trimIndent()
    val destinations = listOf(
      DestinationHint("com.app.Splash", "SplashScreen", 5),
      DestinationHint("com.app.Detail", "DetailScreen", 9),
    )
    assertEquals(listOf("com.app.Detail" to "Related"), edges(source, destinations))
  }

  @Test
  fun aBodylessDestinationDoesNotSwallowTheNextFunction() {
    // `expect fun` on a KMP commonMain screen has neither a block nor an `= expr`, so the body search used to run
    // on until it found the NEXT declaration's block and attributed those calls to this route — publishing a
    // transition the screen never makes. An annotation between the two is not what saves it: nothing may.
    val source = """
      package com.app

      @NavDestination(route = Home::class) @Composable
      expect fun HomeScreen(backStack: NavBackStack)

      @Composable
      fun SomethingElse(backStack: NavBackStack) { backStack.add(Feed) }
    """.trimIndent()
    val destinations = listOf(DestinationHint("com.app.Home", "HomeScreen", 4))
    assertEquals(emptyList<Pair<String, String>>(), edges(source, destinations))
  }

  @Test
  fun anExternalDestinationDoesNotSwallowTheNextFunction() {
    val source = """
      package com.app

      @NavDestination(route = Home::class) @Composable
      external fun HomeScreen(backStack: NavBackStack)

      fun somethingElse(backStack: NavBackStack) { backStack.add(Feed) }
    """.trimIndent()
    val destinations = listOf(DestinationHint("com.app.Home", "HomeScreen", 4))
    assertEquals(emptyList<Pair<String, String>>(), edges(source, destinations))
  }

  @Test
  fun aBlockBodiedDestinationIsUnaffectedByTheDeclarationGuard() {
    // The guard must not cost an ordinary screen its edges, including one with an annotated return type.
    val source = """
      package com.app

      @NavDestination(route = Home::class)
      @Composable
      fun HomeScreen(backStack: NavBackStack) {
        Button(onClick = { backStack.add(Feed) })
      }
    """.trimIndent()
    val destinations = listOf(DestinationHint("com.app.Home", "HomeScreen", 5))
    assertEquals(listOf("com.app.Home" to "Feed"), edges(source, destinations))
  }

  @Test
  fun anExpressionBodiedDestinationStillReadsItsOwnLambdas() {
    val source = """
      package com.app

      @NavDestination(route = Home::class)
      @Composable
      fun HomeScreen(backStack: NavBackStack) = Column { Button(onClick = { backStack.add(Feed) }) }

      @NavDestination(route = Feed::class)
      @Composable
      fun FeedScreen(backStack: NavBackStack) = Content(onOpen = { backStack.add(Profile) })
    """.trimIndent()
    val destinations = listOf(
      DestinationHint("com.app.Home", "HomeScreen", 5),
      DestinationHint("com.app.Feed", "FeedScreen", 9),
    )
    assertEquals(
      listOf("com.app.Home" to "Feed", "com.app.Feed" to "Profile"),
      edges(source, destinations),
    )
  }

  @Test
  fun aGenericDestinationIsStillFound() {
    val source = """
      package com.app

      @NavDestination(route = Home::class)
      @Composable
      fun <T : Comparable<T>> HomeScreen(backStack: NavBackStack) {
        Button(onClick = { backStack.add(Feed) })
      }
    """.trimIndent()
    assertEquals(
      listOf("com.app.Home" to "Feed"),
      edges(source, listOf(DestinationHint("com.app.Home", "HomeScreen", 5))),
    )
  }

  @Test
  fun anEntryWithNoLambdaClaimsNoLaterBlock() {
    // The block has to follow the `entry<T>` immediately; a `Box { }` on the next line is not this route's scope.
    val source = """
      package com.app
      fun App() {
        entry<Home>
        Box { backStack.add(Feed) }
      }
    """.trimIndent()
    assertEquals(emptyList<Pair<String, String>>(), edges(source))
  }

  @Test
  fun readsADottedReferenceSplitAcrossLines() {
    val source = """
      package com.app
      fun App() {
        entry<Home> {
          backStack.add(
            AppRoute
              .Detail
          )
        }
      }
    """.trimIndent()
    assertEquals(listOf("Home" to "AppRoute.Detail"), edges(source))
  }

  @Test
  fun aByteOrderMarkDoesNotHideThePackage() {
    val source = "\uFEFFpackage com.app\n\nimport com.other.Detail\n"
    val scan = KotlinNavScanner.scanKotlinFile(source)
    assertEquals("com.app", scan.packageName)
    assertEquals("com.other.Detail", scan.imports["Detail"])
  }

  @Test
  fun maskingPreservesOffsetsAndNewlines() {
    val source = "val a = \"hi\" // note\nval b = 'x'\n"
    val masked = KotlinNavScanner.maskLiterals(source)
    assertEquals(source.length, masked.length)
    assertEquals(source.count { it == '\n' }, masked.count { it == '\n' })
    assertTrue(masked.startsWith("val a = "))
    assertTrue("hi" !in masked)
    assertTrue("note" !in masked)
  }
}
