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
package com.github.skydoves.navgraph.sample.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.github.skydoves.navgraph.annotations.NavDestination
import com.github.skydoves.navgraph.annotations.NavEdge
import com.github.skydoves.navgraph.annotations.NavPreview
import com.github.skydoves.navgraph.sample.NavSampleTheme
import com.github.skydoves.navgraph.sample.cart.Cart
import kotlinx.serialization.Serializable

/**
 * The **catalog** feature, kept in its own package `...sample.catalog`. Together with the sibling `cart` and
 * `orders` packages it demonstrates the tool window's package-based "Feature:" selector (#19): a single-module
 * graph organized by feature package can be viewed/exported one feature at a time. `Catalog -> Product` is an
 * internal edge (kept under the `catalog` filter); `Catalog -> Cart` crosses into the `cart` feature and is
 * dropped when you scope to `catalog`.
 */
@Serializable
sealed interface CatalogKey : NavKey

@Serializable
data object Catalog : CatalogKey

@Serializable
data class Product(val productId: String) : CatalogKey

@NavEdge(to = Product::class, label = "open")
@NavEdge(to = Cart::class, label = "cart")
@NavDestination(route = Catalog::class)
@Composable
fun CatalogScreen(onOpenProduct: (String) -> Unit = {}, onOpenCart: () -> Unit = {}) {
  val items = listOf(
    Triple("Aurora Headphones", "$129", Color(0xFF6D5BFF) to Color(0xFF22D3EE)),
    Triple("Nimbus Keyboard", "$89", Color(0xFFFF6B6B) to Color(0xFFFF9F45)),
    Triple("Lumen Desk Lamp", "$54", Color(0xFF12B886) to Color(0xFF0EA5E9)),
  )
  CatalogScaffold(title = "Catalog") {
    items.forEachIndexed { index, (name, price, gradient) ->
      ProductRowCard(
        name = name,
        price = price,
        start = gradient.first,
        end = gradient.second,
        onClick = { onOpenProduct(name) }.takeIf { index == 0 },
      )
    }
  }
}

@NavEdge(to = Cart::class, label = "add")
@NavDestination(route = Product::class)
@Composable
fun ProductScreen(onAddToCart: () -> Unit = {}) {
  CatalogScaffold(title = "Product") {
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
      Column {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(Brush.linearGradient(listOf(Color(0xFF6D5BFF), Color(0xFF22D3EE)))),
        )
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = "Aurora Headphones",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "$129",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = "Wireless over-ear headphones with adaptive noise cancellation and 30h battery.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

/** Shared chrome for the catalog screens: a top bar over a padded, scroll-free column of content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogScaffold(title: String, content: @Composable () -> Unit) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(text = title, fontWeight = FontWeight.Bold)
        },
      )
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
private fun ProductRowCard(
  name: String,
  price: String,
  start: Color,
  end: Color,
  onClick: (() -> Unit)?,
) {
  val body: @Composable () -> Unit = {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier = Modifier
          .size(48.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(Brush.linearGradient(listOf(start, end))),
      )
      Text(
        text = name,
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = price,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
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

@NavPreview(route = Catalog::class, primary = true)
@Preview(showBackground = true)
@Composable
internal fun CatalogScreenPreview() {
  NavSampleTheme { CatalogScreen() }
}

@NavPreview(route = Product::class, primary = true)
@Preview(showBackground = true)
@Composable
internal fun ProductScreenPreview() {
  NavSampleTheme { ProductScreen() }
}
