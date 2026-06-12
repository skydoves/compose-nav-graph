package org.jetbrains.kotlinconf.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.kotlinconf.MarkdownScreenWithTitle
import org.jetbrains.kotlinconf.generated.resources.Res
import org.jetbrains.kotlinconf.generated.resources.app_privacy_notice_header
import org.jetbrains.kotlinconf.generated.resources.app_privacy_notice_title
import androidx.compose.ui.tooling.preview.Preview
import com.github.skydoves.navgraph.annotations.NavDestination
import com.github.skydoves.navgraph.annotations.NavEdge
import com.github.skydoves.navgraph.annotations.NavPreview
import org.jetbrains.kotlinconf.ui.theme.KotlinConfTheme
import org.jetbrains.kotlinconf.utils.ErrorLoadingState

@NavDestination(route = org.jetbrains.kotlinconf.navigation.AppPrivacyNoticeScreen::class)
@NavEdge(to = org.jetbrains.kotlinconf.navigation.AppTermsOfUseScreen::class, label = "Terms of use")
@Composable
fun AppPrivacyNotice(
    onBack: () -> Unit,
    onAppTermsOfUse: () -> Unit,
) {
    val viewModel = assistedMetroViewModel<DocumentsViewModel, DocumentsViewModel.Factory> {
        create("documents/app-privacy-notice.md")
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    MarkdownScreenWithTitle(
        title = stringResource(Res.string.app_privacy_notice_title),
        header = stringResource(Res.string.app_privacy_notice_header),
        documentState = state,
        onBack = onBack,
        onReload = { viewModel.refresh() },
        onCustomUriClick = { uri ->
            if (uri == "app-terms.md") {
                onAppTermsOfUse()
            }
        },
    )
}

@NavPreview(route = org.jetbrains.kotlinconf.navigation.AppPrivacyNoticeScreen::class, primary = true)
@Preview
@Composable
private fun AppPrivacyNoticeScreenNavPreview() {
    KotlinConfTheme {
        MarkdownScreenWithTitle(
            title = stringResource(Res.string.app_privacy_notice_title),
            header = stringResource(Res.string.app_privacy_notice_header),
            documentState = ErrorLoadingState.Content(
                """
                # App Privacy Notice

                This notice describes how the KotlinConf app handles your data on your device
                and when communicating with our servers.

                ## Data on your device

                Bookmarks, theme preference, and notification settings are stored locally on
                your device and never leave it unless you explicitly enable syncing.

                ## Analytics

                We collect anonymous, aggregated usage statistics to understand which features
                are useful. This data cannot be used to identify you.

                ## Notifications

                If you opt in, we send you reminders for sessions you have bookmarked and
                updates when the schedule changes.
                """.trimIndent()
            ),
            onBack = {},
            onReload = {},
        )
    }
}
