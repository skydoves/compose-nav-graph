package org.jetbrains.kotlinconf.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.kotlinconf.MarkdownScreenWithTitle
import org.jetbrains.kotlinconf.generated.resources.Res
import org.jetbrains.kotlinconf.generated.resources.app_terms
import org.jetbrains.kotlinconf.generated.resources.app_terms_header
import androidx.compose.ui.tooling.preview.Preview
import com.github.skydoves.navgraph.annotations.NavDestination
import com.github.skydoves.navgraph.annotations.NavEdge
import com.github.skydoves.navgraph.annotations.NavPreview
import org.jetbrains.kotlinconf.ui.theme.KotlinConfTheme
import org.jetbrains.kotlinconf.utils.ErrorLoadingState

@NavDestination(route = org.jetbrains.kotlinconf.navigation.AppTermsOfUseScreen::class)
@NavEdge(to = org.jetbrains.kotlinconf.navigation.AppPrivacyNoticeScreen::class, label = "Privacy notice")
@Composable
fun AppTermsOfUse(
    onBack: () -> Unit,
    onAppPrivacyNotice: () -> Unit,
) {
    val viewModel = assistedMetroViewModel<DocumentsViewModel, DocumentsViewModel.Factory> {
        create("documents/app-terms.md")
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    MarkdownScreenWithTitle(
        title = stringResource(Res.string.app_terms),
        header = stringResource(Res.string.app_terms_header),
        documentState = state,
        onBack = onBack,
        onReload = { viewModel.refresh() },
        onCustomUriClick = { uri ->
            if (uri == "app-privacy-notice.md") {
                onAppPrivacyNotice()
            }
        },
    )
}

@NavPreview(route = org.jetbrains.kotlinconf.navigation.AppTermsOfUseScreen::class, primary = true)
@Preview
@Composable
private fun AppTermsOfUseScreenNavPreview() {
    KotlinConfTheme {
        MarkdownScreenWithTitle(
            title = stringResource(Res.string.app_terms),
            header = stringResource(Res.string.app_terms_header),
            documentState = ErrorLoadingState.Content(
                """
                # Terms of Use

                By downloading and using the KotlinConf app you agree to these terms of use.

                ## License

                The app is provided to you free of charge for your personal, non-commercial
                use during the conference.

                ## Acceptable use

                You agree not to misuse the app, attempt to disrupt its services, or use it
                in any way that violates applicable laws.

                ## Disclaimer

                The app is provided "as is" without warranties of any kind. Schedule and
                session information may change at any time.
                """.trimIndent()
            ),
            onBack = {},
            onReload = {},
        )
    }
}
