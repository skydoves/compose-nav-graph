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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.skydoves.navgraph.annotations.NavDestination
import com.github.skydoves.navgraph.annotations.NavEdge
import com.github.skydoves.navgraph.annotations.NavPreview

/**
 * A richly decorated profile destination used for polished demo thumbnails.
 *
 * The annotations are preserved exactly so the static graph keeps wiring the same node:
 *  - @NavDestination(route) → this composable is the Profile node's click target.
 *  - @NavEdge(to)           → the outgoing Article / Settings transitions.
 *  - @NavPreview(route)     → links the preview below to the Profile node's thumbnail.
 */
@NavEdge(to = Article::class, label = "Test Label")
@NavEdge(to = Settings::class)
@NavEdge(to = ProfileDetailActivity::class, label = "View Detail")
@NavDestination(route = Profile::class)
@Composable
fun ProfileScreen(
  userId: String,
  onOpenArticle: (String) -> Unit = {},
  onOpenSettings: () -> Unit = {},
  onOpenProfileDetail: () -> Unit = {},
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.surface)
      .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    ProfileHeader(userId = userId, onOpenProfileDetail = onOpenProfileDetail)

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      ProfileStatsRow()

      ProfileInfoCard(
        icon = Icons.Filled.Info,
        title = "About",
        body = "Android & Compose tinkerer. Building delightful navigation tooling " +
          "and shipping small, sharp libraries.",
      )

      ProfileSectionCard(userId = userId)

      ProfileActions(
        onOpenArticle = { onOpenArticle("intro-to-navgraph") },
        onOpenSettings = onOpenSettings,
      )

      Spacer(modifier = Modifier.height(8.dp))
    }
  }
}

@Composable
private fun ProfileHeader(userId: String, onOpenProfileDetail: () -> Unit = {}) {
  val headerBrush = Brush.verticalGradient(
    colors = listOf(
      MaterialTheme.colorScheme.primary,
      MaterialTheme.colorScheme.tertiary,
    ),
  )
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(
        brush = headerBrush,
        shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
      )
      .padding(horizontal = 24.dp, vertical = 28.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      ProfileAvatar(userId = userId, onClick = onOpenProfileDetail)

      Text(
        text = "@$userId",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimary,
      )
      Text(
        text = "Jaewoong Eum",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onPrimary,
      )
      Text(
        text = "Android GDE · Compose enthusiast · Open-source maker",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
      )
    }
  }
}

@Composable
private fun ProfileAvatar(userId: String, onClick: () -> Unit = {}) {
  val initial = userId.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
  Surface(
    modifier = Modifier
      .size(96.dp)
      .clickable { onClick() },
    shape = CircleShape,
    color = MaterialTheme.colorScheme.onPrimary,
    border = BorderStroke(
      width = 3.dp,
      color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
    ),
    shadowElevation = 6.dp,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(
        text = initial,
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

@Composable
private fun ProfileStatsRow() {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 18.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      ProfileStat(value = "128", label = "Posts", modifier = Modifier.weight(1f))
      ProfileStatDivider()
      ProfileStat(value = "12.4k", label = "Followers", modifier = Modifier.weight(1f))
      ProfileStatDivider()
      ProfileStat(value = "326", label = "Following", modifier = Modifier.weight(1f))
    }
  }
}

@Composable
private fun ProfileStat(value: String, label: String, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      text = value,
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun ProfileStatDivider() {
  Box(
    modifier = Modifier
      .width(1.dp)
      .height(32.dp)
      .background(MaterialTheme.colorScheme.outlineVariant),
  )
}

@Composable
private fun ProfileInfoCard(icon: ImageVector, title: String, body: String) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface,
    ),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(20.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Surface(
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = icon,
            contentDescription = title,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
          )
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = body,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun ProfileSectionCard(userId: String) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.secondaryContainer,
    ),
  ) {
    Column(modifier = Modifier.padding(20.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Filled.Notifications,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
          text = "Recent activity",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
      }
      Spacer(modifier = Modifier.height(12.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f))
      Spacer(modifier = Modifier.height(12.dp))
      ProfileActivityLine(text = "@$userId published \"Intro to navgraph\"")
      Spacer(modifier = Modifier.height(8.dp))
      ProfileActivityLine(text = "Starred 4 repositories this week")
      Spacer(modifier = Modifier.height(8.dp))
      ProfileActivityLine(text = "Updated profile settings")
    }
  }
}

@Composable
private fun ProfileActivityLine(text: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(6.dp)
        .background(
          color = MaterialTheme.colorScheme.onSecondaryContainer,
          shape = CircleShape,
        ),
    )
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
  }
}

@Composable
private fun ProfileActions(onOpenArticle: () -> Unit, onOpenSettings: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Button(
      onClick = onOpenArticle,
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
    ) {
      Icon(
        imageVector = Icons.Filled.Edit,
        contentDescription = null,
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text("Open Article")
    }
    OutlinedButton(
      onClick = onOpenSettings,
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
    ) {
      Text("Open Settings")
    }
  }
}

@NavPreview(route = Profile::class, primary = true)
@Preview(showBackground = true)
@Composable
internal fun ProfileScreenPreview() {
  NavSampleTheme { ProfileScreen(userId = "skydoves") }
}
