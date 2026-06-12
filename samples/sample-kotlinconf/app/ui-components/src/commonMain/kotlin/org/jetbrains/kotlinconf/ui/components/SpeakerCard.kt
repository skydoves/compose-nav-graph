package org.jetbrains.kotlinconf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.kotlinconf.ui.generated.resources.UiRes
import org.jetbrains.kotlinconf.ui.generated.resources.kodee_emotion_neutral
import org.jetbrains.kotlinconf.ui.theme.KotlinConfTheme
import org.jetbrains.kotlinconf.ui.theme.PreviewHelper
import org.jetbrains.kotlinconf.ui.utils.PreviewLightDark

@Composable
fun SpeakerCard(
    name: String,
    title: String,
    photoUrl: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    nameHighlights: List<IntRange> = emptyList(),
    titleHighlights: List<IntRange> = emptyList(),
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0A1F2E))
            .border(1.dp, Color(0xFF7CFFB2), RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 12.dp, end = 20.dp, top = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeakerAvatar(
            photoUrl = photoUrl,
            modifier = Modifier
                .size(64.dp)
                .border(2.dp, Color(0xFF00E5C7), RoundedCornerShape(14.dp)),
            shape = RoundedCornerShape(14.dp),
        )
        Column {
            Text(
                text = buildHighlightedString(name, nameHighlights),
                style = KotlinConfTheme.typography.h3,
                color = Color(0xFF7CFFB2),
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = buildHighlightedString(title, titleHighlights),
                style = KotlinConfTheme.typography.text2,
                color = Color(0xFFB8FFE0),
            )
        }
    }
}

@Composable
fun SpeakerAvatar(
    photoUrl: String,
    modifier: Modifier = Modifier,
    shape: Shape = KotlinConfTheme.shapes.roundedCornerMd,
) {
    AsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(photoUrl)
            .crossfade(true)
            .build(),
        contentDescription = null,
        modifier = modifier
            .clip(shape)
            .background(KotlinConfTheme.colors.tileBackground),
        contentScale = ContentScale.Crop,
        error = painterResource(UiRes.drawable.kodee_emotion_neutral),
    )
}

@PreviewLightDark
@Composable
private fun SpeakerCardPreview() = PreviewHelper {
    SpeakerCard(
        name = "John Doe",
        title = "Whatever Role Name at That Company",
        photoUrl = "https://example.com/not-an-image.jpg",
        onClick = {},
    )
}

@PreviewLightDark
@Composable
private fun SpeakerCardWithHighlightsPreview() = PreviewHelper {
    SpeakerCard(
        name = "John Doe",
        nameHighlights = listOf(0..3),  // Highlight "John"
        title = "Whatever Role Name at That Company",
        titleHighlights = listOf(9..12),  // Highlight "Role"
        photoUrl = "https://sessionize.com/image/2e2f-0o0o0-XGxKBoqZvxxQxosrZHQHTT.png?download=sebastian-aigner.png",
        onClick = {},
    )
}
