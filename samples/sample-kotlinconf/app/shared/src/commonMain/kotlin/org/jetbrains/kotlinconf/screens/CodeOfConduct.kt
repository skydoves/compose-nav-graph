package org.jetbrains.kotlinconf.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.kotlinconf.MarkdownScreenWithTitle
import org.jetbrains.kotlinconf.generated.resources.Res
import org.jetbrains.kotlinconf.generated.resources.code_of_conduct
import org.jetbrains.kotlinconf.generated.resources.kodee_code_of_conduct
import androidx.compose.ui.tooling.preview.Preview
import com.github.skydoves.navgraph.annotations.NavDestination
import com.github.skydoves.navgraph.annotations.NavPreview
import org.jetbrains.kotlinconf.ui.theme.KotlinConfTheme
import org.jetbrains.kotlinconf.utils.ErrorLoadingState

@NavDestination(route = org.jetbrains.kotlinconf.navigation.CodeOfConductScreen::class)
@Composable
fun CodeOfConduct(onBack: () -> Unit) {
    val viewModel = assistedMetroViewModel<DocumentsViewModel, DocumentsViewModel.Factory> {
        create("documents/code-of-conduct.md")
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    MarkdownScreenWithTitle(
        title = stringResource(Res.string.code_of_conduct),
        header = stringResource(Res.string.code_of_conduct),
        documentState = state,
        onBack = onBack,
        onReload = { viewModel.refresh() },
    ) {
        Image(
            painter = painterResource(Res.drawable.kodee_code_of_conduct),
            contentDescription = null,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp)
        )
    }
}

@NavPreview(route = org.jetbrains.kotlinconf.navigation.CodeOfConductScreen::class, primary = true)
@Preview
@Composable
private fun CodeOfConductScreenNavPreview() {
    KotlinConfTheme {
        MarkdownScreenWithTitle(
            title = stringResource(Res.string.code_of_conduct),
            header = stringResource(Res.string.code_of_conduct),
            documentState = ErrorLoadingState.Content(
                """
                # Code of Conduct

                All participants, speakers, sponsors, and volunteers at KotlinConf are
                required to agree with this Code of Conduct. We are dedicated to providing
                a harassment-free conference experience for everyone.

                ## Be respectful

                Treat everyone with respect. Harassment and exclusionary behavior are not
                acceptable. This includes, but is not limited to, offensive comments,
                deliberate intimidation, and unwelcome attention.

                ## Reporting

                If you are being harassed, notice that someone else is being harassed, or
                have any other concerns, please contact a member of the conference staff.
                """.trimIndent()
            ),
            onBack = {},
            onReload = {},
        )
    }
}
