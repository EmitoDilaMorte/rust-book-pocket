package dev.rustbook.pocket.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rustbook.pocket.R
import dev.rustbook.pocket.ReaderUiState
import dev.rustbook.pocket.ReaderViewModel
import dev.rustbook.pocket.book.BookBundle
import dev.rustbook.pocket.book.BookWebView
import dev.rustbook.pocket.runner.ExecResult
import dev.rustbook.pocket.runner.PlaygroundRequest

@Composable
fun ReaderRoute(viewModel: ReaderViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var bookView by remember { mutableStateOf<BookWebView?>(null) }

    BackHandler {
        when {
            showSettings -> showSettings = false
            bookView?.canGoBack() == true -> bookView?.goBack()
            else -> (context as? Activity)?.finish()
        }
    }

    when {
        !uiState.ready -> LoadingScreen()

        !BookBundle.isAvailable(context) -> MissingBookScreen()

        else -> BookContent(
            uiState = uiState,
            textZoom = settings.textZoom,
            onExecute = viewModel::execute,
            onOpenSettings = { showSettings = true },
            onRecordProgress = viewModel::recordProgress,
            onWebViewChanged = { bookView = it },
        )
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            updateState = updateState,
            onDismiss = { showSettings = false },
            onExecutionModeChange = viewModel::setExecutionMode,
            onPlaygroundUrlChange = viewModel::setPlaygroundUrl,
            onTextZoomChange = viewModel::setTextZoom,
            onClearProgress = viewModel::clearProgress,
            onCheckForUpdates = viewModel::checkForUpdates,
        )
    }
}

@Composable
private fun BookContent(
    uiState: ReaderUiState,
    textZoom: Int,
    onExecute: suspend (PlaygroundRequest) -> ExecResult,
    onOpenSettings: () -> Unit,
    onRecordProgress: (String, Float) -> Unit,
    onWebViewChanged: (BookWebView?) -> Unit,
) {
    val context = LocalContext.current

    // Keep the WebView's callbacks pointing at the latest lambdas without
    // recreating the (expensive) view.
    val settingsClick = rememberUpdatedState(onOpenSettings)
    val executeRequest = rememberUpdatedState(onExecute)
    val progressSink = rememberUpdatedState(onRecordProgress)
    val viewSink = rememberUpdatedState(onWebViewChanged)

    var pageError by remember { mutableStateOf<String?>(null) }

    val book = remember(context) {
        BookWebView(
            context = context,
            execute = { request -> executeRequest.value(request) },
            onOpenSettings = { settingsClick.value() },
            onRecordProgress = { path, progress -> progressSink.value(path, progress) },
        )
    }

    LaunchedEffect(book) {
        book.load(uiState.startPath, uiState.startProgress)
        viewSink.value(book)
    }

    DisposableEffect(book) {
        onDispose {
            viewSink.value(null)
            book.destroyBook()
        }
    }

    SideEffect {
        book.onPageError = { description -> pageError = description }
    }

    LaunchedEffect(textZoom) {
        book.applyTextZoom(textZoom)
    }

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        AndroidView(
            factory = { book },
            modifier = Modifier.fillMaxSize(),
        )

        val error = pageError
        if (error != null) {
            PageErrorOverlay(
                description = error,
                onRetry = {
                    pageError = null
                    book.load(uiState.startPath)
                },
            )
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MissingBookScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.book_missing_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.book_missing_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        SelectionContainer {
            Text(
                text = "pocket/scripts/build-book.ps1",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun PageErrorOverlay(description: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.book_error_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.book_error_retry))
            }
        }
    }
}
