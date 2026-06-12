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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.skydoves.navgraph.annotations.NavDestination
import com.github.skydoves.navgraph.annotations.NavEdge
import com.github.skydoves.navgraph.annotations.NavPreview

/*
 * The app's start destination, styled as a small "home dashboard" so demo
 * screenshots look polished rather than empty. The navigation contract is
 * unchanged — the annotations below still drive static graph wiring:
 *  - @NavDestination(route) → the click target (this composable's FQN) for Home's node.
 *  - @NavEdge(to)           → an outgoing transition (from = Home by default).
 *  - @NavPreview(route)     → links the @Preview below to Home → the node's thumbnail.
 */

@NavEdge(to = Feed::class)
@NavEdge(to = Settings::class)
@NavDestination(route = Home::class)
@Composable
fun HomeScreen(onOpenFeed: () -> Unit = {}, onOpenSettings: () -> Unit = {}) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    HomeHeader()
    HomeHeroCard()
    HomeStatRow()
    HomeActions(
      onOpenFeed = onOpenFeed,
      onOpenSettings = onOpenSettings,
    )
  }
}

/** Greeting row with a colored circular avatar (placeholder, no image loading). */
@Composable
private fun HomeHeader() {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Box(
      modifier = Modifier
        .size(56.dp)
        .clip(CircleShape)
        .background(
          Brush.linearGradient(
            listOf(
              MaterialTheme.colorScheme.primary,
              MaterialTheme.colorScheme.tertiary,
            ),
          ),
        ),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = "S",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimary,
      )
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = "Welcome back",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = "skydoves",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
      )
    }
    Box(
      modifier = Modifier
        .size(12.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primary),
    )
  }
}

/** Hero / feature card with a gradient brush background and placeholder copy. */
@Composable
private fun HomeHeroCard() {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.linearGradient(
            listOf(
              MaterialTheme.colorScheme.primary,
              MaterialTheme.colorScheme.secondary,
            ),
          ),
        )
        .padding(24.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "Your daily digest",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onPrimary,
        )
        Text(
          text = "12 new stories curated for you across the feeds you follow.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
          shape = RoundedCornerShape(50),
          color = MaterialTheme.colorScheme.onPrimary,
        ) {
          Text(
            text = "Updated just now",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
          )
        }
      }
    }
  }
}

/** A row of compact stat / quick-action cards. */
@Composable
private fun HomeStatRow() {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    HomeStatCard(
      modifier = Modifier.weight(1f),
      value = "24",
      label = "Saved",
      accent = MaterialTheme.colorScheme.primaryContainer,
      onAccent = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    HomeStatCard(
      modifier = Modifier.weight(1f),
      value = "8",
      label = "Following",
      accent = MaterialTheme.colorScheme.secondaryContainer,
      onAccent = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    HomeStatCard(
      modifier = Modifier.weight(1f),
      value = "3",
      label = "Drafts",
      accent = MaterialTheme.colorScheme.tertiaryContainer,
      onAccent = MaterialTheme.colorScheme.onTertiaryContainer,
    )
  }
}

@Composable
private fun HomeStatCard(
  value: String,
  label: String,
  accent: androidx.compose.ui.graphics.Color,
  onAccent: androidx.compose.ui.graphics.Color,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier,
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = accent),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = value,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = onAccent,
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = onAccent,
      )
    }
  }
}

/** The two existing destinations, styled as prominent buttons. */
@Composable
private fun HomeActions(onOpenFeed: () -> Unit, onOpenSettings: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Button(
      onClick = onOpenFeed,
      modifier = Modifier
        .fillMaxWidth()
        .height(52.dp),
      shape = RoundedCornerShape(16.dp),
    ) {
      Text(
        text = "Open Feed",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
      )
    }
    OutlinedButton(
      onClick = onOpenSettings,
      modifier = Modifier
        .fillMaxWidth()
        .height(52.dp),
      shape = RoundedCornerShape(16.dp),
      colors = ButtonDefaults.outlinedButtonColors(
        contentColor = MaterialTheme.colorScheme.primary,
      ),
    ) {
      Text(
        text = "Open Settings",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

@NavPreview(route = Home::class, primary = true)
@Preview(showBackground = true)
@Composable
internal fun HomeScreenPreview() {
  NavSampleTheme { HomeScreen() }
}
