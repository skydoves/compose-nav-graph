package org.jetbrains.kotlinconf.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.kotlinconf.MarkdownScreenWithTitle
import org.jetbrains.kotlinconf.generated.resources.Res
import org.jetbrains.kotlinconf.generated.resources.privacy_notice_for_visitors
import org.jetbrains.kotlinconf.generated.resources.privacy_notice_for_visitors_title
import androidx.compose.ui.tooling.preview.Preview
import com.github.skydoves.navgraph.annotations.NavDestination
import com.github.skydoves.navgraph.annotations.NavPreview
import org.jetbrains.kotlinconf.ui.theme.KotlinConfTheme
import org.jetbrains.kotlinconf.utils.ErrorLoadingState

@NavDestination(route = org.jetbrains.kotlinconf.navigation.VisitorPrivacyNoticeScreen::class)
@Composable
fun VisitorPrivacyNotice(onBack: () -> Unit) {
    val viewModel = assistedMetroViewModel<DocumentsViewModel, DocumentsViewModel.Factory> {
        create("documents/visitors-privacy-notice.md")
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    MarkdownScreenWithTitle(
        title = stringResource(Res.string.privacy_notice_for_visitors),
        header = stringResource(Res.string.privacy_notice_for_visitors_title),
        documentState = state,
        onBack = onBack,
        onReload = { viewModel.refresh() },
    )
}

@NavPreview(route = org.jetbrains.kotlinconf.navigation.VisitorPrivacyNoticeScreen::class, primary = true)
@Preview
@Composable
private fun VisitorPrivacyNoticeScreenNavPreview() {
    KotlinConfTheme {
        MarkdownScreenWithTitle(
            title = stringResource(Res.string.privacy_notice_for_visitors),
            header = stringResource(Res.string.privacy_notice_for_visitors_title),
            documentState = ErrorLoadingState.Content(
                """
                # Privacy Notice for Visitors

                This privacy notice explains how JetBrains processes your personal data when
                you attend KotlinConf as a visitor.

                ## What we collect

                We collect your name, email address, and the sessions you choose to attend so
                that we can provide you with a personalized conference experience.

                ## How we use it

                Your data is used solely to operate the conference, send you reminders about
                your bookmarked sessions, and improve future events.

                ## Your rights

                You may request access to, correction of, or deletion of your personal data at
                any time by contacting the conference organizers.
                """.trimIndent()
            ),
            onBack = {},
            onReload = {},
        )
    }
}
