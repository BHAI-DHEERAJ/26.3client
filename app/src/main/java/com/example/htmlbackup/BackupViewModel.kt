package com.example.htmlbackup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BackupUiState {
    data object Idle : BackupUiState
    data class Preparing(val message: String, val percent: Int?) : BackupUiState
    data class Ready(val summary: ArchiveSummary) : BackupUiState
    data class Uploading(val percent: Int, val status: String) : BackupUiState
}

sealed interface BackupUiEvent {
    data class Result(val title: String, val message: String) : BackupUiEvent
    data class Toast(val message: String) : BackupUiEvent
}

/**
 * Owns the backup lifecycle. It survives configuration changes, keeps the
 * prepared archive out of the Activity, and never uploads without an explicit
 * [uploadArchive] call from the review dialog.
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val zipManager = ZipBackupManager(application)
    private val uploader = WebhookUploader()

    private val _state = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private val eventChannel = Channel<BackupUiEvent>(Channel.BUFFERED)
    val events: Flow<BackupUiEvent> = eventChannel.receiveAsFlow()

    private var preparationJob: Job? = null
    private var uploadJob: Job? = null
    private var preparedArchive: PreparedArchive? = null

    /** Persists across rotation so the intro does not reappear. */
    var introHandled: Boolean = false
        private set

    init {
        // Remove archives left behind by a previous process. This runs off the
        // main thread; preparation also clears the directory before writing.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { zipManager.clearTemporaryArchives() }
        }
    }

    fun markIntroHandled() {
        introHandled = true
    }

    fun prepareBackup(request: BackupRequest) {
        if (_state.value !is BackupUiState.Idle) {
            return
        }
        if (preparationJob?.isActive == true || uploadJob?.isActive == true) {
            return
        }

        _state.value = BackupUiState.Preparing(
            message = getString(R.string.progress_starting),
            percent = null
        )

        preparationJob = viewModelScope.launch {
            try {
                val archive = zipManager.prepareBackup(request) { progress ->
                    _state.value = BackupUiState.Preparing(
                        message = preparationMessage(progress),
                        percent = progress.percent
                    )
                }

                if (!archive.summary.hasAnyData) {
                    zipManager.deleteArchive(archive)
                    _state.value = BackupUiState.Idle
                    sendEvent(
                        BackupUiEvent.Result(
                            getString(R.string.no_data_title),
                            getString(R.string.no_data_message)
                        )
                    )
                    return@launch
                }

                preparedArchive = archive
                _state.value = BackupUiState.Ready(archive.summary)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    cleanupArchive()
                    _state.value = BackupUiState.Idle
                    sendEvent(
                        BackupUiEvent.Toast(
                            getString(R.string.toast_preparation_cancelled)
                        )
                    )
                }
                throw e
            } catch (e: BackupException) {
                cleanupArchive()
                _state.value = BackupUiState.Idle
                sendEvent(
                    BackupUiEvent.Result(
                        getString(R.string.preparation_failed_title),
                        e.message ?: getString(R.string.preparation_failed_message)
                    )
                )
            } catch (e: Exception) {
                cleanupArchive()
                _state.value = BackupUiState.Idle
                sendEvent(
                    BackupUiEvent.Result(
                        getString(R.string.preparation_failed_title),
                        getString(R.string.preparation_failed_message)
                    )
                )
            } finally {
                preparationJob = null
            }
        }
    }

    fun cancelPreparation() {
        if (_state.value is BackupUiState.Preparing) {
            preparationJob?.cancel()
        }
    }

    /** The user refused the transfer in the review dialog. */
    fun discardPreparedArchive() {
        if (_state.value !is BackupUiState.Ready) {
            return
        }

        cleanupArchive()
        _state.value = BackupUiState.Idle
        eventChannel.trySend(BackupUiEvent.Toast(getString(R.string.toast_archive_discarded)))
    }

    /**
     * Called only after the review dialog has been shown and the user tapped
     * Send. This is the sole path that performs network transmission.
     */
    fun uploadArchive() {
        if (_state.value !is BackupUiState.Ready) {
            return
        }
        if (uploadJob?.isActive == true) {
            return
        }

        val archive = preparedArchive
        if (archive == null) {
            _state.value = BackupUiState.Idle
            eventChannel.trySend(
                BackupUiEvent.Result(
                    getString(R.string.upload_failed_title),
                    getString(R.string.upload_error_archive_unavailable)
                )
            )
            return
        }

        if (!WebhookConfig.isConfigured) {
            val cleanupOk = cleanupArchive()
            _state.value = BackupUiState.Idle
            eventChannel.trySend(
                BackupUiEvent.Result(
                    getString(R.string.upload_failed_title),
                    getString(R.string.upload_error_invalid_config) + cleanupNote(cleanupOk)
                )
            )
            return
        }

        _state.value = BackupUiState.Uploading(
            percent = 0,
            status = getString(R.string.upload_status_sending)
        )

        uploadJob = viewModelScope.launch {
            try {
                val result = uploader.upload(
                    archive = archive.file,
                    summary = archive.summary,
                    onProgress = { percent ->
                        val status = if (percent >= 100) {
                            getString(R.string.upload_status_waiting)
                        } else {
                            getString(R.string.upload_status_sending)
                        }
                        _state.value = BackupUiState.Uploading(
                            percent = percent.coerceIn(0, 100),
                            status = status
                        )
                    },
                    onStatus = { status ->
                        val current = _state.value as? BackupUiState.Uploading
                        _state.value = BackupUiState.Uploading(
                            percent = current?.percent ?: 0,
                            status = uploadStatusMessage(status)
                        )
                    }
                )

                val cleanupOk = cleanupArchive()
                val title: String
                val baseMessage: String
                when (result) {
                    is UploadResult.Success -> {
                        title = getString(R.string.upload_success_title)
                        baseMessage = getString(R.string.upload_success_message)
                    }

                    is UploadResult.Failure -> {
                        title = getString(R.string.upload_failed_title)
                        baseMessage = uploadFailureMessage(result)
                    }
                }

                _state.value = BackupUiState.Idle
                sendEvent(
                    BackupUiEvent.Result(
                        title,
                        baseMessage + cleanupNote(cleanupOk)
                    )
                )
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    val cleanupOk = cleanupArchive()
                    _state.value = BackupUiState.Idle
                    sendEvent(
                        BackupUiEvent.Result(
                            getString(R.string.upload_cancelled_title),
                            getString(R.string.upload_cancelled_message) + cleanupNote(cleanupOk)
                        )
                    )
                }
                throw e
            } catch (e: Exception) {
                val cleanupOk = cleanupArchive()
                _state.value = BackupUiState.Idle
                sendEvent(
                    BackupUiEvent.Result(
                        getString(R.string.upload_failed_title),
                        getString(R.string.upload_error_unexpected) + cleanupNote(cleanupOk)
                    )
                )
            } finally {
                uploadJob = null
            }
        }
    }

    fun cancelUpload() {
        if (_state.value is BackupUiState.Uploading) {
            uploadJob?.cancel()
        }
    }

    override fun onCleared() {
        preparationJob?.cancel()
        uploadJob?.cancel()
        cleanupArchive()
        super.onCleared()
    }

    /**
     * Deletes the current archive. Returns true when no file remains (including
     * the case where there was nothing to delete).
     */
    private fun cleanupArchive(): Boolean {
        val archive = preparedArchive ?: return true
        preparedArchive = null
        archive.file.delete()
        return !archive.file.exists()
    }

    private fun preparationMessage(progress: PreparationProgress): String =
        when (progress.stage) {
            PreparationStage.STARTING -> getString(R.string.progress_starting)
            PreparationStage.QUERYING_PHOTOS -> getString(R.string.progress_finding_photos)
            PreparationStage.ADDING_PHOTOS -> getString(
                R.string.progress_adding_photos,
                progress.current,
                progress.total
            )

            PreparationStage.READING_CONTACTS -> getString(R.string.progress_reading_contacts)
            PreparationStage.ADDING_CONTACTS -> getString(R.string.progress_adding_contacts)
            PreparationStage.FINALIZING -> getString(R.string.progress_finalizing)
        }

    private fun uploadStatusMessage(status: UploadStatus): String = when (status) {
        UploadStatus.Sending -> getString(R.string.upload_status_sending)
        is UploadStatus.RateLimited -> getString(
            R.string.upload_status_rate_limited,
            status.retryAfterSeconds
        )
    }

    private fun uploadFailureMessage(failure: UploadResult.Failure): String =
        when (failure.kind) {
            UploadFailureKind.INVALID_CONFIGURATION ->
                getString(R.string.upload_error_invalid_config)

            UploadFailureKind.ARCHIVE_UNAVAILABLE ->
                getString(R.string.upload_error_archive_unavailable)

            UploadFailureKind.CONNECTIVITY -> getString(R.string.upload_error_connectivity)
            UploadFailureKind.TIMEOUT -> getString(R.string.upload_error_timeout)
            UploadFailureKind.TLS -> getString(R.string.upload_error_tls)
            UploadFailureKind.NETWORK -> getString(R.string.upload_error_network)

            UploadFailureKind.REDIRECT_REJECTED -> getString(
                R.string.upload_error_redirect,
                failure.httpStatusCode ?: 0
            )

            UploadFailureKind.UNAUTHORIZED,
            UploadFailureKind.NOT_FOUND -> getString(
                R.string.upload_error_webhook_rejected,
                failure.httpStatusCode ?: 0
            )

            UploadFailureKind.PAYLOAD_TOO_LARGE -> getString(
                R.string.upload_error_too_large,
                failure.httpStatusCode ?: 0
            )

            UploadFailureKind.RATE_LIMITED -> failure.retryAfterSeconds?.let { seconds ->
                getString(R.string.upload_error_rate_limited, seconds)
            } ?: getString(R.string.upload_error_rate_limited_unknown)

            UploadFailureKind.SERVER_ERROR -> getString(
                R.string.upload_error_server,
                failure.httpStatusCode ?: 0
            )

            UploadFailureKind.REJECTED -> getString(
                R.string.upload_error_rejected,
                failure.httpStatusCode ?: 0
            )

            UploadFailureKind.UNEXPECTED -> getString(R.string.upload_error_unexpected)
        }

    private fun cleanupNote(cleanupSucceeded: Boolean): String {
        val note = if (cleanupSucceeded) {
            R.string.upload_cleanup_note
        } else {
            R.string.upload_cleanup_failed_note
        }
        return "\n\n" + getString(note)
    }

    private fun getString(resId: Int): String =
        getApplication<Application>().getString(resId)

    private fun getString(resId: Int, vararg formatArgs: Any): String =
        getApplication<Application>().getString(resId, *formatArgs)

    private suspend fun sendEvent(event: BackupUiEvent) {
        eventChannel.send(event)
    }
}
