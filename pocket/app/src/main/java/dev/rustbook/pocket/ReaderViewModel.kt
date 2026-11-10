package dev.rustbook.pocket

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.rustbook.pocket.book.BookUris
import dev.rustbook.pocket.data.AppPreferences
import dev.rustbook.pocket.data.ReaderSettings
import dev.rustbook.pocket.runner.ExecutionMode
import dev.rustbook.pocket.runner.ExecutorRouter
import dev.rustbook.pocket.runner.PlaygroundRequest
import dev.rustbook.pocket.runner.RemoteExecutor
import dev.rustbook.pocket.runner.RustExecutor
import dev.rustbook.pocket.update.UpdateChecker
import dev.rustbook.pocket.update.UpdateState
import dev.rustbook.pocket.util.NetworkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class ReaderUiState(
    val ready: Boolean = false,
    val startPath: String = BookUris.INDEX,
    val startProgress: Float = 0f,
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AppPreferences(application)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    /** One compile at a time keeps the playground's rate limits happy. */
    private val executionMutex = Mutex()

    private val _settings = MutableStateFlow(ReaderSettings())
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val updateChecker = UpdateChecker(
        client = httpClient,
        repository = BuildConfig.UPDATE_REPO,
        currentVersion = BuildConfig.VERSION_NAME,
    )

    /**
     * The on-device executor is not wired up yet; when it lands it slots in
     * here and the router starts preferring it in `AUTO` mode.
     */
    val executor: RustExecutor = ExecutorRouter(
        remote = RemoteExecutor(httpClient) { _settings.value.playgroundUrl },
        local = null,
        mode = { _settings.value.executionMode },
        isOnline = { NetworkStatus.isOnline(getApplication()) },
    )

    init {
        viewModelScope.launch {
            preferences.settings.collect { _settings.value = it }
        }

        viewModelScope.launch {
            val position = preferences.readingPosition()
            _uiState.value = ReaderUiState(
                ready = true,
                startPath = position?.path ?: BookUris.INDEX,
                startProgress = position?.progress ?: 0f,
            )
        }
    }

    fun setExecutionMode(mode: ExecutionMode) {
        viewModelScope.launch { preferences.setExecutionMode(mode) }
    }

    fun setPlaygroundUrl(url: String) {
        viewModelScope.launch { preferences.setPlaygroundUrl(url) }
    }

    fun setTextZoom(percent: Int) {
        viewModelScope.launch { preferences.setTextZoom(percent) }
    }

    fun clearProgress() {
        viewModelScope.launch { preferences.clearProgress() }
    }

    fun recordProgress(relativePath: String, progress: Float) {
        viewModelScope.launch { preferences.recordProgress(relativePath, progress) }
    }

    suspend fun execute(request: PlaygroundRequest) = executionMutex.withLock {
        executor.execute(request)
    }

    fun checkForUpdates() {
        if (_updateState.value == UpdateState.Checking) return
        _updateState.value = UpdateState.Checking
        viewModelScope.launch {
            _updateState.value = updateChecker.check()
        }
    }
}
