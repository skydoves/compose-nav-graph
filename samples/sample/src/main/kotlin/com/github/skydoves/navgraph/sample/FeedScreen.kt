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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.skydoves.navgraph.annotations.NavDestination
import com.github.skydoves.navgraph.annotations.NavEdge
import com.github.skydoves.navgraph.annotations.NavPreview

@NavEdge(to = Profile::class)
@NavDestination(route = Feed::class)
@Composable
fun FeedScreen(onOpenProfile: (String) -> Unit = {}) {
  FeedContent(onOpenProfile = onOpenProfile)
}

/** Holds the immutable data for a single feed post card. */
private data class FeedPost(
  val author: String,
  val handle: String,
  val timestamp: String,
  val body: String,
  val avatarStart: Color,
  val avatarEnd: Color,
  val bannerStart: Color,
  val bannerEnd: Color,
  val likes: Int,
  val comments: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedContent(onOpenProfile: (String) -> Unit) {
  val posts = listOf(
    FeedPost(
      author = "skydoves",
      handle = "@skydoves",
      timestamp = "2h",
      body = "Shipping Navigation 3 today — static graphs, zero boilerplate. " +
        "Tap my card to open the profile demo.",
      avatarStart = Color(0xFF6D5BFF),
      avatarEnd = Color(0xFF9C46FF),
      bannerStart = Color(0xFF7F53FF),
      bannerEnd = Color(0xFF38BDF8),
      likes = 248,
      comments = 36,
    ),
    FeedPost(
      author = "Compose Weekly",
      handle = "@composeweekly",
      timestamp = "5h",
      body = "Material 3 cards + gradients make for surprisingly clean feed UI " +
        "with almost no custom drawing.",
      avatarStart = Color(0xFFFF6B6B),
      avatarEnd = Color(0xFFFF9F45),
      bannerStart = Color(0xFFFF8A65),
      bannerEnd = Color(0xFFFFC371),
      likes = 132,
      comments = 18,
    ),
    FeedPost(
      author = "Android Dev",
      handle = "@androiddev",
      timestamp = "1d",
      body = "Edge annotations describe the whole nav graph at compile time. " +
        "Previews become live thumbnails in the graph view.",
      avatarStart = Color(0xFF12B886),
      avatarEnd = Color(0xFF22D3EE),
      bannerStart = Color(0xFF34D399),
      bannerEnd = Color(0xFF0EA5E9),
      likes = 87,
      comments = 9,
    ),
  )

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = "Feed",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface,
          titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
      )
    },
    containerColor = MaterialTheme.colorScheme.surfaceVariant,
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .padding(horizontal = 16.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      posts.forEachIndexed { index, post ->
        // The first card (skydoves) is the navigation target.
        FeedPostCard(
          post = post,
          onClick = if (index == 0) {
            { onOpenProfile("skydoves") }
          } else {
            null
          },
        )
      }
    }
  }
}

@Composable
private fun FeedPostCard(post: FeedPost, onClick: (() -> Unit)?) {
  val cardModifier = Modifier.fillMaxWidth()
  val cardColors = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surface,
  )
  val cardElevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
  val cardShape = RoundedCornerShape(16.dp)

  val body: @Composable () -> Unit = {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      FeedAuthorRow(post = post)
      Text(
        text = post.body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      FeedBanner(start = post.bannerStart, end = post.bannerEnd)
      FeedActionsRow(likes = post.likes, comments = post.comments)
    }
  }

  if (onClick != null) {
    Card(
      onClick = onClick,
      modifier = cardModifier,
      shape = cardShape,
      colors = cardColors,
      elevation = cardElevation,
    ) { body() }
  } else {
    Card(
      modifier = cardModifier,
      shape = cardShape,
      colors = cardColors,
      elevation = cardElevation,
    ) { body() }
  }
}

@Composable
private fun FeedAuthorRow(post: FeedPost) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    FeedAvatar(start = post.avatarStart, end = post.avatarEnd, initial = post.author.first())
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = post.author,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = "${post.handle} · ${post.timestamp}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun FeedAvatar(start: Color, end: Color, initial: Char) {
  Box(
    modifier = Modifier
      .size(44.dp)
      .clip(CircleShape)
      .background(Brush.linearGradient(listOf(start, end))),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = initial.uppercaseChar().toString(),
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      color = Color.White,
    )
  }
}

@Composable
private fun FeedBanner(start: Color, end: Color) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(140.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(
        Brush.linearGradient(
          colors = listOf(start, end),
        ),
      ),
  )
}

@Composable
private fun FeedActionsRow(likes: Int, comments: Int) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    // Like — a core Material icon.
    FeedActionItem(label = likes.toString()) {
      Icon(
        imageVector = Icons.Filled.Favorite,
        contentDescription = "Likes",
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(20.dp),
      )
    }
    // Comment — a rounded "speech bubble" glyph (no chat icon in material-icons-core).
    FeedActionItem(label = comments.toString()) {
      Box(
        modifier = Modifier
          .size(18.dp)
          .clip(
            RoundedCornerShape(
              topStart = 6.dp,
              topEnd = 6.dp,
              bottomEnd = 6.dp,
              bottomStart = 2.dp,
            ),
          )
          .background(MaterialTheme.colorScheme.primary),
      )
    }
    Spacer(modifier = Modifier.weight(1f))
    Icon(
      imageVector = Icons.Filled.Share,
      contentDescription = "Share",
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(20.dp),
    )
  }
}

@Composable
private fun FeedActionItem(label: String, glyph: @Composable () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    glyph()
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@NavPreview(route = Feed::class, primary = true)
@Preview(showBackground = true)
@Composable
internal fun FeedScreenPreview() {
  NavSampleTheme { FeedScreen() }
}
