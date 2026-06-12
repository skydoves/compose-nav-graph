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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.skydoves.navgraph.annotations.NavDestination
import com.github.skydoves.navgraph.annotations.NavEdge
import com.github.skydoves.navgraph.annotations.NavPreview

// An explicit `from` (not the attached screen's own route) + a label.
@NavEdge(from = Settings::class, to = Home::class, label = "home")
@NavDestination(route = Settings::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(text = "Settings", style = MaterialTheme.typography.titleLarge) },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface,
          titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
      )
    },
    containerColor = MaterialTheme.colorScheme.surfaceVariant,
  ) { innerPadding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      item { SettingsAccountHeader() }

      items(settingsSections) { section ->
        SettingsSectionCard(section)
      }

      item {
        Text(
          text = "navgraph sample • version 1.0.0",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 12.dp),
        )
      }
    }
  }
}

/** The account summary shown at the top of the settings list: avatar + name + email. */
@Composable
private fun SettingsAccountHeader() {
  Card(
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.primaryContainer,
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(20.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      SettingsAvatar()
      Spacer(modifier = Modifier.width(16.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Jaewoong Eum",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "@skydoves",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      SettingsEditBadge()
    }
  }
}

/** Circular avatar placeholder using a Material icon on a tinted surface (no image loading). */
@Composable
private fun SettingsAvatar() {
  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.size(56.dp),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = Icons.Filled.AccountCircle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(36.dp),
      )
    }
  }
}

/** A small "edit profile" affordance rendered purely as a shape + icon. */
@Composable
private fun SettingsEditBadge() {
  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
    modifier = Modifier.size(36.dp),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = Icons.Filled.Person,
        contentDescription = "Edit profile",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

/** One grouped section card ("General", "Notifications", "Privacy") with a header + rows. */
@Composable
private fun SettingsSectionCard(section: SettingsSection) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = section.title,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
    )
    Card(
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface,
      ),
      elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.padding(vertical = 4.dp)) {
        section.rows.forEachIndexed { index, row ->
          SettingsRow(row)
          if (index != section.rows.lastIndex) {
            SettingsDivider()
          }
        }
      }
    }
  }
}

/** A single settings row: leading icon chip + title + subtitle + trailing toggle or chevron. */
@Composable
private fun SettingsRow(row: SettingsRowData) {
  var checked by remember { mutableStateOf(row.initialChecked) }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SettingsLeadingChip(row.icon)
    Spacer(modifier = Modifier.width(16.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = row.title,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = row.subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Spacer(modifier = Modifier.width(12.dp))
    if (row.hasToggle) {
      Switch(checked = checked, onCheckedChange = { checked = it })
    } else {
      SettingsChevron()
    }
  }
}

/** Rounded leading icon container that tints the row's icon. */
@Composable
private fun SettingsLeadingChip(icon: ImageVector) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.secondaryContainer,
    modifier = Modifier.size(40.dp),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(22.dp),
      )
    }
  }
}

/** A trailing "navigate" chevron drawn as two rotated bars (no icon dependency). */
@Composable
private fun SettingsChevron() {
  val arm = MaterialTheme.colorScheme.onSurfaceVariant
  Box(
    modifier = Modifier.size(18.dp),
    contentAlignment = Alignment.Center,
  ) {
    // Upper arm of the ">" — a thin rounded bar rotated +45° and nudged up.
    Box(
      modifier = Modifier
        .offset(y = (-3).dp)
        .size(width = 2.dp, height = 9.dp)
        .rotate(45f)
        .background(arm, RoundedCornerShape(1.dp)),
    )
    // Lower arm — rotated -45° and nudged down to meet the upper arm at a point.
    Box(
      modifier = Modifier
        .offset(y = 3.dp)
        .size(width = 2.dp, height = 9.dp)
        .rotate(-45f)
        .background(arm, RoundedCornerShape(1.dp)),
    )
  }
}

/** A thin inset divider between rows. */
@Composable
private fun SettingsDivider() {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 72.dp, end = 16.dp)
      .height(1.dp)
      .background(MaterialTheme.colorScheme.outlineVariant),
  )
}

/** Static placeholder content model for the settings list. */
private data class SettingsRowData(
  val icon: ImageVector,
  val title: String,
  val subtitle: String,
  val hasToggle: Boolean,
  val initialChecked: Boolean = false,
)

private data class SettingsSection(val title: String, val rows: List<SettingsRowData>)

private val settingsSections: List<SettingsSection> = listOf(
  SettingsSection(
    title = "General",
    rows = listOf(
      SettingsRowData(
        icon = Icons.Filled.Settings,
        title = "Appearance",
        subtitle = "Theme, accent color, and layout",
        hasToggle = false,
      ),
      SettingsRowData(
        icon = Icons.Filled.Person,
        title = "Account",
        subtitle = "Profile, sign-in, and security",
        hasToggle = false,
      ),
      SettingsRowData(
        icon = Icons.Filled.Info,
        title = "Compact mode",
        subtitle = "Tighter spacing across screens",
        hasToggle = true,
        initialChecked = false,
      ),
    ),
  ),
  SettingsSection(
    title = "Notifications",
    rows = listOf(
      SettingsRowData(
        icon = Icons.Filled.Notifications,
        title = "Push notifications",
        subtitle = "Alerts on this device",
        hasToggle = true,
        initialChecked = true,
      ),
      SettingsRowData(
        icon = Icons.Filled.Email,
        title = "Email digest",
        subtitle = "A weekly summary in your inbox",
        hasToggle = true,
        initialChecked = false,
      ),
    ),
  ),
  SettingsSection(
    title = "Privacy",
    rows = listOf(
      SettingsRowData(
        icon = Icons.Filled.Lock,
        title = "App lock",
        subtitle = "Require unlock on launch",
        hasToggle = true,
        initialChecked = true,
      ),
      SettingsRowData(
        icon = Icons.Filled.Info,
        title = "Privacy policy",
        subtitle = "Read how your data is handled",
        hasToggle = false,
      ),
    ),
  ),
)

@NavPreview(route = Settings::class, primary = true)
@Preview(showBackground = true)
@Composable
internal fun SettingsScreenPreview() {
  NavSampleTheme { SettingsScreen() }
}

// Renders with Korean resources (values-ko/) on both backends; see @Preview(locale) support.
@NavPreview(route = Settings::class)
@Preview(showBackground = true, locale = "ko")
@Composable
internal fun SettingsScreenKoreanPreview() {
  NavSampleTheme {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
      Text(
        text = stringResource(R.string.settings_greeting),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(32.dp),
      )
    }
  }
}
