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

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.github.skydoves.navgraph.annotations.NavPreview

/**
 * A second Activity, reached by tapping the avatar on [ProfileScreen].
 *
 * It is annotated with `@NavDestination(route = ProfileDetailActivity::class)` so navgraph can treat an
 * **Activity** as a graph node — the static graph wires the `Profile → ProfileDetailActivity` edge
 * (declared via `@NavEdge` on `ProfileScreen`) and uses this screen's `@NavPreview` thumbnail.
 *
 * Pure static content: no ViewModel, no DI — just a polished Material3 detail layout.
 */
class ProfileDetailActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      NavSampleTheme { ProfileDetailScreen() }
    }
  }
}

@NavDestination(route = ProfileDetailActivity::class)
@Composable
fun ProfileDetailScreen() {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.surface)
      .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    ProfileDetailHeader()

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      ProfileDetailStatsRow()

      ProfileDetailInfoCard(
        icon = Icons.Filled.Person,
        title = "Bio",
        body = "Android GDE crafting Compose libraries and developer tooling. " +
          "Passionate about navigation, architecture, and shipping polished open-source.",
      )

      ProfileDetailContactCard()

      Spacer(modifier = Modifier.height(8.dp))
    }
  }
}

@Composable
private fun ProfileDetailHeader() {
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
      .padding(horizontal = 24.dp, vertical = 32.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      ProfileDetailAvatar()

      Text(
        text = "Jaewoong Eum",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimary,
      )
      Text(
        text = "@skydoves",
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
private fun ProfileDetailAvatar() {
  Surface(
    modifier = Modifier.size(120.dp),
    shape = CircleShape,
    color = MaterialTheme.colorScheme.onPrimary,
    border = BorderStroke(
      width = 4.dp,
      color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
    ),
    shadowElevation = 8.dp,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(
        text = "S",
        style = MaterialTheme.typography.displayMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

@Composable
private fun ProfileDetailStatsRow() {
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
      ProfileDetailStat(value = "128", label = "Posts", modifier = Modifier.weight(1f))
      ProfileDetailStatDivider()
      ProfileDetailStat(value = "12.4k", label = "Followers", modifier = Modifier.weight(1f))
      ProfileDetailStatDivider()
      ProfileDetailStat(value = "326", label = "Following", modifier = Modifier.weight(1f))
    }
  }
}

@Composable
private fun ProfileDetailStat(value: String, label: String, modifier: Modifier = Modifier) {
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
private fun ProfileDetailStatDivider() {
  Box(
    modifier = Modifier
      .width(1.dp)
      .height(32.dp)
      .background(MaterialTheme.colorScheme.outlineVariant),
  )
}

@Composable
private fun ProfileDetailInfoCard(icon: ImageVector, title: String, body: String) {
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
private fun ProfileDetailContactCard() {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.secondaryContainer,
    ),
  ) {
    Column(modifier = Modifier.padding(20.dp)) {
      Text(
        text = "Details",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )
      Spacer(modifier = Modifier.height(12.dp))
      HorizontalDivider(
        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f),
      )
      Spacer(modifier = Modifier.height(12.dp))
      ProfileDetailContactLine(icon = Icons.Filled.Email, text = "@skydoves")
      Spacer(modifier = Modifier.height(10.dp))
      ProfileDetailContactLine(icon = Icons.Filled.LocationOn, text = "Seoul, South Korea")
      Spacer(modifier = Modifier.height(10.dp))
      ProfileDetailContactLine(icon = Icons.Filled.DateRange, text = "Joined March 2016")
    }
  }
}

@Composable
private fun ProfileDetailContactLine(icon: ImageVector, text: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
  }
}

@NavPreview(route = ProfileDetailActivity::class, primary = true)
@Preview(showBackground = true)
@Composable
private fun ProfileDetailPreview() {
  NavSampleTheme { ProfileDetailScreen() }
}
