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
package com.github.skydoves.navgraph.sample.cart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.github.skydoves.navgraph.annotations.NavDestination
import com.github.skydoves.navgraph.annotations.NavEdge
import com.github.skydoves.navgraph.annotations.NavPreview
import com.github.skydoves.navgraph.sample.NavSampleTheme
import com.github.skydoves.navgraph.sample.orders.Orders
import kotlinx.serialization.Serializable

/**
 * The **cart** feature (package `...sample.cart`). `Cart -> Checkout` is an internal edge; `Checkout -> Orders`
 * crosses into the `orders` feature and is dropped when you scope the graph to `cart`. See [CatalogScreen]'s
 * sibling package for the full "Feature:" selector story (#19).
 */
@Serializable
sealed interface CartKey : NavKey

@Serializable
data object Cart : CartKey

@Serializable
data object Checkout : CartKey

@NavEdge(to = Checkout::class, label = "checkout")
@NavDestination(route = Cart::class)
@Composable
fun CartScreen(onCheckout: () -> Unit = {}) {
  val lines = listOf(
    "Aurora Headphones" to "$129",
    "Nimbus Keyboard" to "$89",
  )
  CartScaffold(title = "Cart") {
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        lines.forEach { (name, price) -> CartLine(name = name, price = price) }
        HorizontalDivider()
        CartLine(name = "Total", price = "$218", emphasize = true)
      }
    }
    Button(
      onClick = onCheckout,
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
    ) {
      Text(text = "Checkout", fontWeight = FontWeight.SemiBold)
    }
  }
}

@NavEdge(to = Orders::class, label = "place order")
@NavDestination(route = Checkout::class)
@Composable
fun CheckoutScreen(onPlaceOrder: () -> Unit = {}) {
  CartScaffold(title = "Checkout") {
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = "Shipping",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = "skydoves\n123 Compose Street\nSeoul",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        CartLine(name = "Total due", price = "$218", emphasize = true)
      }
    }
    Button(
      onClick = onPlaceOrder,
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
    ) {
      Text(text = "Place order", fontWeight = FontWeight.SemiBold)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CartScaffold(title: String, content: @Composable () -> Unit) {
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
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      content()
    }
  }
}

@Composable
private fun CartLine(name: String, price: String, emphasize: Boolean = false) {
  val weight = if (emphasize) FontWeight.Bold else FontWeight.Normal
  val priceColor =
    if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = name,
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = weight,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = price,
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = weight,
      color = priceColor,
    )
  }
}

@NavPreview(route = Cart::class, primary = true)
@Preview(showBackground = true)
@Composable
internal fun CartScreenPreview() {
  NavSampleTheme { CartScreen() }
}

@NavPreview(route = Checkout::class, primary = true)
@Preview(showBackground = true)
@Composable
internal fun CheckoutScreenPreview() {
  NavSampleTheme { CheckoutScreen() }
}
