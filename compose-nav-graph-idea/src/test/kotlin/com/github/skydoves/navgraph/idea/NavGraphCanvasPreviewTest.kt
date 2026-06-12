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
package com.github.skydoves.navgraph.idea

import com.github.skydoves.navgraph.idea.model.NavGraphDto
import com.google.gson.Gson
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Headless render of the real [NavGraphCanvas] against the `:sample` manifest → `build/canvas-preview.png`.
 * Lets us eyeball the flow map without a running IDE. Skips if the sample hasn't been generated.
 */
class NavGraphCanvasPreviewTest {

  @Test
  fun rendersSampleGraph() {
    val base = File("../sample/build/navgraph")
    val manifest = File(base, "nav-graph.json")
    assumeTrue("run :sample:generateNavGraph first", manifest.isFile)

    val graph = Gson().fromJson(manifest.readText(), NavGraphDto::class.java)
    val thumbs = graph.nodes.mapNotNull { node ->
      val rel =
        node.previews.firstOrNull { it.primary }?.thumbnail
          ?: node.previews.firstOrNull()?.thumbnail
          ?: return@mapNotNull null
      val png = File(base, rel)
      if (png.isFile) runCatching { ImageIO.read(png) }.getOrNull()?.let { node.id to it } else null
    }.toMap()

    val w = 1240
    val h = 780
    // No Project in the headless test → the canvas uses a default NavGraphSettings bean (all defaults),
    // so this render reflects the un-customized look and is the visual-regression gate.
    val canvas = NavGraphCanvas(null, onNodeActivated = { })
    canvas.setSize(w, h)
    canvas.setGraph(graph, thumbs)

    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    canvas.paint(g)
    g.dispose()

    val out = File("build/canvas-preview.png").apply { parentFile.mkdirs() }
    ImageIO.write(image, "png", out)
    println(
      "navgraph canvas preview → ${out.absolutePath} (${graph.nodes.size} nodes, ${thumbs.size} thumbnails)",
    )
  }
}
