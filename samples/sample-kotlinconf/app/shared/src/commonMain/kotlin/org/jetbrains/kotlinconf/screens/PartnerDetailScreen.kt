package org.jetbrains.kotlinconf.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.kotlinconf.PartnerId
import org.jetbrains.kotlinconf.ScreenWithTitle
import org.jetbrains.kotlinconf.generated.resources.Res
import org.jetbrains.kotlinconf.generated.resources.partner_detail_title
import org.jetbrains.kotlinconf.generated.resources.partners_error
import org.jetbrains.kotlinconf.ui.components.NetworkImage
import org.jetbrains.kotlinconf.ui.components.Text
import org.jetbrains.kotlinconf.ui.theme.KotlinConfTheme
import org.jetbrains.kotlinconf.utils.ErrorLoadingContent
import org.jetbrains.kotlinconf.ScreenWithTitle
import androidx.compose.ui.tooling.preview.Preview
import com.github.skydoves.navgraph.annotations.NavDestination
import com.github.skydoves.navgraph.annotations.NavPreview

@NavDestination(route = org.jetbrains.kotlinconf.navigation.PartnerDetailScreen::class)
@Composable
fun PartnerDetailScreen(
    partnerId: PartnerId,
    onBack: () -> Unit,
    viewModel: PartnerDetailViewModel =
        assistedMetroViewModel<PartnerDetailViewModel, PartnerDetailViewModel.Factory> {
            create(partnerId)
        },
) {
    val partnerState = viewModel.partner.collectAsStateWithLifecycle().value
    val isDark = KotlinConfTheme.colors.isDark

    ScreenWithTitle(
        title = stringResource(Res.string.partner_detail_title),
        onBack = onBack,
    ) {
        ErrorLoadingContent(
            state = partnerState,
            errorMessage = stringResource(Res.string.partners_error),
            modifier = Modifier.fillMaxSize(),
        ) { partner ->
            val logoUrl = if (isDark) partner.logoUrlDark else partner.logoUrlLight
            NetworkImage(
                photoUrl = logoUrl,
                contentDescription = partner.name,
                modifier = Modifier.fillMaxWidth()
                    .height(180.dp)
                    .padding(horizontal = 32.dp, vertical = 16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = partner.name,
                style = KotlinConfTheme.typography.h1,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = partner.description,
                color = KotlinConfTheme.colors.longText,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@NavPreview(route = org.jetbrains.kotlinconf.navigation.PartnerDetailScreen::class, primary = true)
@Preview
@Composable
private fun PartnerDetailScreenNavPreview() {
    KotlinConfTheme {
        ScreenWithTitle(
            title = stringResource(Res.string.partner_detail_title),
            onBack = {},
        ) {
            NetworkImage(
                photoUrl = "",
                contentDescription = "JetBrains",
                modifier = Modifier.fillMaxWidth()
                    .height(180.dp)
                    .padding(horizontal = 32.dp, vertical = 16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "JetBrains",
                style = KotlinConfTheme.typography.h1,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "JetBrains is the creator of Kotlin and a range of intelligent " +
                    "developer tools used by millions of developers worldwide, including " +
                    "IntelliJ IDEA, Android Studio, and Fleet.",
                color = KotlinConfTheme.colors.longText,
            )
        }
    }
}
