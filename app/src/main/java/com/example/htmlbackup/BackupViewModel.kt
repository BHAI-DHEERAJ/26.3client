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
    data class Ready(val plan: BackupPlan) : BackupUiState
    data class Uploading(val percent: Int, val message: String) : BackupUiState
}

sealed interface BackupUiEvent {
    data class Result(val title: String, val message: String) : BackupUiEvent
    data class Toast(val message: String) : BackupUiEvent
}

/**
 * Owns the backup lifecycle. It survives configuration changes, keeps the
 * discovered plan out of the Activity, and never uploads without an explicit
 * [uploadBackup] call from the review dialog.
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val preparer = BackupPreparer(application)
    private val sender = BackupSender(application)

    private val _state = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private val eventChannel = Channel<BackupUiEvent>(Channel.BUFFERED)
    val events: Flow<BackupUiEvent> = eventChannel.receiveAsFlow()

    private var preparationJob: Job? = null
    private var uploadJob: Job? = null
    private var plan: BackupPlan? = null

    /** Persists across rotation so the intro does not reappear. */
    var introHandled: Boolean = false
        private set

    init {
        // Remove compressed fallbacks left behind by a previous process.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { sender.clearTemporaryFiles() }
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
                val prepared = preparer.prepare(request) { progress ->
                    _state.value = BackupUiState.Preparing(
                        message = preparationMessage(progress),
                        percent = progress.percent
                    )
                }

                if (!prepared.hasAnyData) {
                    _state.value = BackupUiState.Idle
                    sendEvent(
                        BackupUiEvent.Result(
                            getString(R.string.no_data_title),
                            getString(R.string.no_data_message)
                        )
                    )
                    return@launch
                }

                plan = prepared
                _state.value = BackupUiState.Ready(prepared)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    plan = null
                    _state.value = BackupUiState.Idle
                    sendEvent(
                        BackupUiEvent.Toast(
                            getString(R.string.toast_preparation_cancelled)
                        )
                    )
                }
                throw e
            } catch (e: BackupException) {
                plan = null
                _state.value = BackupUiState.Idle
                sendEvent(
                    BackupUiEvent.Result(
                        getString(R.string.preparation_failed_title),
                        e.message ?: getString(R.string.preparation_failed_message)
                    )
                )
            } catch (e: Exception) {
                plan = null
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
    fun discardPlan() {
        if (_state.value !is BackupUiState.Ready) {
            return
        }

        plan = null
        _state.value = BackupUiState.Idle
        eventChannel.trySend(BackupUiEvent.Toast(getString(R.string.toast_plan_discarded)))
    }

    /**
     * Called only after the review dialog has been shown and the user tapped
     * Send. This is the sole path that performs network transmission.
     */
    fun uploadBackup() {
        if (_state.value !is BackupUiState.Ready) {
            return
        }
        if (uploadJob?.isActive == true) {
            return
        }

        val currentPlan = plan
        if (currentPlan == null) {
            _state.value = BackupUiState.Idle
            eventChannel.trySend(
                BackupUiEvent.Result(
                    getString(R.string.upload_failed_title),
                    getString(R.string.upload_error_plan_unavailable)
                )
            )
            return
        }

        if (!WebhookConfig.isConfigured) {
            val ok = cleanupTemporaryFiles()
            _state.value = BackupUiState.Idle
            eventChannel.trySend(
                BackupUiEvent.Result(
                    getString(R.string.upload_failed_title),
                    getString(R.string.upload_error_invalid_config) + cleanupNote(ok)
                )
            )
            return
        }

        _state.value = BackupUiState.Uploading(
            percent = 0,
            message = getString(R.string.upload_status_sending)
        )

        uploadJob = viewModelScope.launch {
            try {
                val report = sender.send(currentPlan) { progress ->
                    _state.value = BackupUiState.Uploading(
                        percent = progress.overallPercent.coerceIn(0, 100),
                        message = uploadProgressMessage(progress)
                    )
                }

                val cleanupOk = cleanupTemporaryFiles()
                plan = null
                _state.value = BackupUiState.Idle
                sendEvent(
                    BackupUiEvent.Result(
                        uploadResultTitle(report),
                        uploadResultMessage(report) + cleanupNote(cleanupOk)
                    )
                )
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    val cleanupOk = cleanupTemporaryFiles()
                    plan = null
                    _state.value = BackupUiState.Idle
                    sendEvent(
                        BackupUiEvent.Result(
                            getString(R.string.upload_cancelled_title),
                            getString(R.string.upload_cancelled_message) +
                                "\n\n" + getString(R.string.upload_partial_warning) +
                                cleanupNote(cleanupOk)
                        )
                    )
                }
                throw e
            } catch (e: Exception) {
                val cleanupOk = cleanupTemporaryFiles()
                plan = null
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
        sender.clearTemporaryFiles()
        plan = null
        super.onCleared()
    }

    private fun cleanupTemporaryFiles(): Boolean {
        return runCatching {
            sender.clearTemporaryFiles()
            true
        }.getOrElse { false }
    }

    private fun preparationMessage(progress: PreparationProgress): String =
        when (progress.stage) {
            PreparationStage.STARTING -> getString(R.string.progress_starting)
            PreparationStage.QUERYING_PHOTOS -> getString(R.string.progress_finding_photos)
            PreparationStage.READING_CONTACTS -> getString(R.string.progress_reading_contacts)
            PreparationStage.FINALIZING -> getString(R.string.progress_preparing_done)
        }

    private fun uploadProgressMessage(progress: UploadProgress): String {
        val item = getString(
            R.string.upload_item_counter,
            progress.itemIndex,
            progress.itemCount
        )
        val phase = when (progress.phase) {
            UploadPhase.DEVICE_INFO -> getString(R.string.upload_phase_device_info)
            UploadPhase.CONTACTS -> getString(R.string.upload_phase_contacts)
            UploadPhase.PHOTOS -> getString(R.string.upload_phase_photos)
        }
        val note = when (progress.note) {
            UploadNote.SENDING -> getString(R.string.upload_status_sending)
            UploadNote.WAITING_CONFIRMATION -> getString(R.string.upload_status_waiting)
            UploadNote.COMPRESSING -> getString(R.string.upload_status_compressing)
            UploadNote.RETRYING -> getString(R.string.upload_status_retrying)
            UploadNote.RATE_LIMITED -> getString(
                R.string.upload_status_rate_limited,
                progress.retryAfterSeconds ?: 0L
            )
        }
        return "$phase · $item\n$note"
    }

    private fun uploadResultTitle(report: UploadReport): String = when {
        !report.anythingDelivered -> getString(R.string.upload_failed_title)
        report.aborted || report.photosSkipped > 0 -> getString(R.string.upload_partial_title)
        else -> getString(R.string.upload_success_title)
    }

    private fun uploadResultMessage(report: UploadReport): String = buildString {
        append(
            if (report.anythingDelivered) {
                getString(R.string.upload_result_delivered_heading)
            } else {
                getString(R.string.upload_result_nothing_delivered)
            }
        )
        append('\n')

        if (report.infoSent) {
            append('\n')
            append(getString(R.string.upload_result_device_info_sent))
        }

        if (report.contactsIncluded) {
            append('\n')
            append(
                if (report.contactsSent) {
                    getString(R.string.upload_result_contacts_sent)
                } else {
                    getString(R.string.upload_result_contacts_failed)
                }
            )
        }

        if (report.photosAttempted > 0) {
            append('\n')
            append(
                getString(
                    R.string.upload_result_photos,
                    report.photosSent,
                    report.photosAttempted
                )
            )
            if (report.photosCompressedSent > 0) {
                append(' ')
                append(
                    getString(
                        R.string.upload_result_compressed,
                        report.photosCompressedSent
                    )
                )
            }
            if (report.photosSkipped > 0) {
                append(' ')
                append(getString(R.string.upload_result_skipped, report.photosSkipped))
            }
        }

        if (report.aborted) {
            append("\n\n")
            append(getString(R.string.upload_result_aborted))
        }

        if (!report.anythingDelivered) {
            append("\n\n")
            append(uploadFailureMessage(report.abortReason))
        }
    }

    private fun uploadFailureMessage(kind: UploadFailureKind?): String = when (kind) {
        null -> getString(R.string.upload_error_unexpected)
        UploadFailureKind.INVALID_CONFIGURATION -> getString(R.string.upload_error_invalid_config)
        UploadFailureKind.CONNECTIVITY -> getString(R.string.upload_error_connectivity)
        UploadFailureKind.TIMEOUT -> getString(R.string.upload_error_timeout)
        UploadFailureKind.TLS -> getString(R.string.upload_error_tls)
        UploadFailureKind.NETWORK -> getString(R.string.upload_error_network)
        UploadFailureKind.REDIRECT_REJECTED -> getString(R.string.upload_error_redirect)
        UploadFailureKind.UNAUTHORIZED,
        UploadFailureKind.NOT_FOUND -> getString(R.string.upload_error_webhook_rejected)

        UploadFailureKind.PAYLOAD_TOO_LARGE -> getString(R.string.upload_error_too_large)
        UploadFailureKind.RATE_LIMITED -> getString(R.string.upload_error_rate_limited)
        UploadFailureKind.SERVER_ERROR -> getString(R.string.upload_error_server)
        UploadFailureKind.REJECTED -> getString(R.string.upload_error_rejected)
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
