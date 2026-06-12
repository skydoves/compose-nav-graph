package org.jetbrains.kotlinconf.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.kotlinconf.MarkdownScreenWithTitle
import org.jetbrains.kotlinconf.generated.resources.Res
import org.jetbrains.kotlinconf.generated.resources.general_terms
import org.jetbrains.kotlinconf.generated.resources.visitors_terms_title
import androidx.compose.ui.tooling.preview.Preview
import com.github.skydoves.navgraph.annotations.NavDestination
import com.github.skydoves.navgraph.annotations.NavEdge
import com.github.skydoves.navgraph.annotations.NavPreview
import org.jetbrains.kotlinconf.ui.theme.KotlinConfTheme
import org.jetbrains.kotlinconf.utils.ErrorLoadingState

@NavDestination(route = org.jetbrains.kotlinconf.navigation.TermsOfUseScreen::class)
@NavEdge(to = org.jetbrains.kotlinconf.navigation.CodeOfConductScreen::class, label = "Code of conduct")
@NavEdge(to = org.jetbrains.kotlinconf.navigation.VisitorPrivacyNoticeScreen::class, label = "Privacy notice")
@Composable
fun VisitorTermsOfUse(
    onBack: () -> Unit,
    onCodeOfConduct: () -> Unit,
    onVisitorPrivacyNotice: () -> Unit,
) {
    val viewModel = assistedMetroViewModel<DocumentsViewModel, DocumentsViewModel.Factory> {
        create("documents/visitors-terms.md")
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    MarkdownScreenWithTitle(
        title = stringResource(Res.string.general_terms),
        header = stringResource(Res.string.visitors_terms_title),
        documentState = state,
        onBack = onBack,
        onReload = { viewModel.refresh() },
        onCustomUriClick = { uri ->
            when (uri) {
                "code-of-conduct.md" -> onCodeOfConduct()
                "visitors-privacy-notice.md" -> onVisitorPrivacyNotice()
            }
        },
    )
}

@NavPreview(route = org.jetbrains.kotlinconf.navigation.TermsOfUseScreen::class, primary = true)
@Preview
@Composable
private fun TermsOfUseScreenNavPreview() {
    KotlinConfTheme {
        MarkdownScreenWithTitle(
            title = stringResource(Res.string.general_terms),
            header = stringResource(Res.string.visitors_terms_title),
            documentState = ErrorLoadingState.Content(
                """
                # General Terms and Conditions

                These general terms and conditions apply to all visitors of KotlinConf.

                ## Admission

                Your conference badge grants you access to all keynote and session areas.
                Please keep it visible at all times during the event.

                ## Conduct

                All visitors are expected to follow the Code of Conduct and the instructions
                of conference staff and venue security.

                ## Liability

                The organizers are not liable for any loss or damage to personal belongings
                brought to the venue.
                """.trimIndent()
            ),
            onBack = {},
            onReload = {},
        )
    }
}
