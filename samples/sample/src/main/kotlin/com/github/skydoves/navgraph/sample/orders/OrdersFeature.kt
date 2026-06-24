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
package com.github.skydoves.navgraph.sample.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.github.skydoves.navgraph.annotations.NavDestination
import com.github.skydoves.navgraph.annotations.NavEdge
import com.github.skydoves.navgraph.annotations.NavPreview
import com.github.skydoves.navgraph.sample.NavSampleTheme
import kotlinx.serialization.Serializable

/**
 * The **orders** feature (package `...sample.orders`). `Orders -> OrderDetail` is an internal edge, so it
 * survives the `orders` filter; `OrderDetail` is a leaf. The incoming `Checkout -> Orders` edge (declared over
 * in the `cart` feature) is dropped when you scope to `orders`, since its source is outside this package (#19).
 */
@Serializable
sealed interface OrdersKey : NavKey

@Serializable
data object Orders : OrdersKey

@Serializable
data class OrderDetail(val orderId: String) : OrdersKey

@NavEdge(to = OrderDetail::class, label = "open")
@NavDestination(route = Orders::class)
@Composable
fun OrdersScreen(onOpenOrder: (String) -> Unit = {}) {
  val orders = listOf(
    Triple("#1043", "Delivered", MaterialTheme.colorScheme.primary),
    Triple("#1042", "Shipped", MaterialTheme.colorScheme.tertiary),
    Triple("#1041", "Processing", MaterialTheme.colorScheme.secondary),
  )
  OrdersScaffold(title = "Orders") {
    orders.forEachIndexed { index, (id, status, accent) ->
      OrderRow(
        id = id,
        status = status,
        accent = accent,
        onClick = { onOpenOrder(id) }.takeIf { index == 0 },
      )
    }
  }
}

@NavDestination(route = OrderDetail::class)
@Composable
fun OrderDetailScreen() {
  OrdersScaffold(title = "Order #1043") {
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = "Delivered",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = "2 items · $218",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = "Arrived Jun 12, left at the front door.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrdersScaffold(title: String, content: @Composable () -> Unit) {
  Scaffold(
    topBar = {
      TopAppBar(title = { Text(text = title, fontWeight = FontWeight.Bold) })
    },
    containerColor = MaterialTheme.colorScheme.surfaceVariant,
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      content()
    }
  }
}

@Composable
private fun OrderRow(
  id: String,
  status: String,
  accent: androidx.compose.ui.graphics.Color,
  onClick: (() -> Unit)?,
) {
  val body: @Composable () -> Unit = {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier = Modifier
          .size(10.dp)
          .clip(CircleShape)
          .background(accent),
      )
      Text(
        text = id,
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = status,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  val shape = RoundedCornerShape(16.dp)
  val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
  if (onClick != null) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = shape, colors = colors) {
      body()
    }
  } else {
    Card(modifier = Modifier.fillMaxWidth(), shape = shape, colors = colors) { body() }
  }
}

@NavPreview(route = Orders::class, primary = true)
@Preview(showBackground = true)
@Composable
internal fun OrdersScreenPreview() {
  NavSampleTheme { OrdersScreen() }
}

@NavPreview(route = OrderDetail::class, primary = true)
@Preview(showBackground = true)
@Composable
internal fun OrderDetailScreenPreview() {
  NavSampleTheme { OrderDetailScreen() }
}
