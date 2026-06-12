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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.skydoves.navgraph.annotations.NavDestination
import com.github.skydoves.navgraph.annotations.NavPreview

@NavDestination(route = Article::class)
@Composable
fun ArticleScreen(articleId: String) {
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    if (articleId.isBlank()) {
      ArticleEmptyState()
    } else {
      ArticleReader(articleId = articleId)
    }
  }
}

@Composable
private fun ArticleReader(articleId: String) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState()),
  ) {
    ArticleHero(articleId = articleId)

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp, vertical = 20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      ArticleByline(articleId = articleId)

      Text(
        text = "Navigation 3 reimagines how Compose apps move between screens. " +
          "Instead of a string-based graph stitched together at runtime, the back " +
          "stack becomes an ordinary list of type-safe keys that you own and mutate " +
          "like any other state.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )

      Text(
        text = "Because the destinations are plain serializable objects, the compiler " +
          "checks every argument for you. Passing the wrong type — or forgetting one " +
          "entirely — stops being a crash you discover on a device and becomes a red " +
          "squiggle you fix while you type.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      ArticlePullQuote(
        quote = "The best navigation framework is the one you never have to think " +
          "about — it simply moves out of your way.",
        attribution = "— The navgraph design notes",
      )

      Text(
        text = "Pair that with statically generated previews and you get a living map " +
          "of your app: each node rendered, each edge labelled, every route reachable " +
          "from the moment you open the editor. Article \"$articleId\" is one such node " +
          "in that graph.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )

      Spacer(modifier = Modifier.height(8.dp))

      ArticleActionsRow()

      Spacer(modifier = Modifier.height(8.dp))
    }
  }
}

@Composable
private fun ArticleHero(articleId: String) {
  val gradient = Brush.linearGradient(
    colors = listOf(
      MaterialTheme.colorScheme.primary,
      MaterialTheme.colorScheme.tertiary,
    ),
  )
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(220.dp)
      .background(gradient),
    contentAlignment = Alignment.BottomStart,
  ) {
    Column(
      modifier = Modifier.padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Surface(
        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f),
        shape = RoundedCornerShape(50),
      ) {
        Text(
          text = "FEATURED",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
      }
      Text(
        text = articleTitleFor(articleId),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimary,
      )
    }
  }
}

@Composable
private fun ArticleByline(articleId: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(RoundedCornerShape(50))
        .background(MaterialTheme.colorScheme.secondaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Filled.Person,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(26.dp),
      )
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = "Jaewoong Eum",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.Schedule,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(14.dp),
        )
        Text(
          text = "${articleReadMinutes(articleId)} min read",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Icon(
      imageVector = Icons.Filled.Star,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(20.dp),
    )
  }
}

@Composable
private fun ArticlePullQuote(quote: String, attribution: String) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.primaryContainer,
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Row(modifier = Modifier.padding(20.dp)) {
      Box(
        modifier = Modifier
          .width(4.dp)
          .height(64.dp)
          .clip(RoundedCornerShape(2.dp))
          .background(MaterialTheme.colorScheme.primary),
      )
      Spacer(modifier = Modifier.width(16.dp))
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = quote,
          style = MaterialTheme.typography.titleMedium.copy(
            lineHeightStyle = LineHeightStyle(
              alignment = LineHeightStyle.Alignment.Center,
              trim = LineHeightStyle.Trim.None,
            ),
          ),
          fontStyle = FontStyle.Italic,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
          text = attribution,
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
      }
    }
  }
}

@Composable
private fun ArticleActionsRow() {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      ArticleAction(icon = Icons.Filled.Favorite, label = "1.2k")
      ArticleAction(icon = Icons.Filled.Star, label = "Save")
      ArticleAction(icon = Icons.Filled.Share, label = "Share")
    }
  }
}

@Composable
private fun ArticleAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = label,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(20.dp),
    )
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun ArticleEmptyState() {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Box(
      modifier = Modifier
        .size(96.dp)
        .clip(RoundedCornerShape(50))
        .background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Filled.Info,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(44.dp),
      )
    }
    Spacer(modifier = Modifier.height(20.dp))
    Text(
      text = "No article selected",
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = "Pick a story from the feed and it will open right here, " +
        "ready to read.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** Turns a slug like "intro-to-navgraph" into a human title "Intro To NavGraph". */
private fun articleTitleFor(articleId: String): String = articleId
  .split('-', '_', ' ')
  .filter { it.isNotBlank() }
  .joinToString(" ") { word ->
    word.replaceFirstChar { it.uppercaseChar() }
  }

/** A deterministic, slug-derived "read time" so screenshots stay stable. */
private fun articleReadMinutes(articleId: String): Int = (articleId.length % 9) + 4

// Two previews for one route → exercises `primary` selection (primary + empty).

@NavPreview(route = Article::class, primary = true)
@Preview(showBackground = true)
@Composable
internal fun ArticleScreenPreview() {
  NavSampleTheme { ArticleScreen(articleId = "intro-to-navgraph") }
}

@NavPreview(route = Article::class)
@Preview(showBackground = true)
@Composable
internal fun ArticleScreenEmptyPreview() {
  NavSampleTheme { ArticleScreen(articleId = "") }
}
