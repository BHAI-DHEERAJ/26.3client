package com.example.htmlbackup

import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * The only screen is a locked-down local WebView showing Hello World.
 *
 * All backup, permission, ZIP, and upload work is native Kotlin. The WebView
 * has JavaScript, file access, content access, and network loads disabled; it
 * is presentation only and never receives personal data.
 */
class MainActivity : AppCompatActivity() {
    private val viewModel: BackupViewModel by viewModels()
    private lateinit var webView: WebView

    private var introDialog: AlertDialog? = null
    private var progressDialog: AlertDialog? = null
    private var consentDialog: AlertDialog? = null
    private var consentPlanStamp: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onPermissionsReturned()
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        onSettingsReturned()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.helloWebView)
        configureWebView()
        loadHelloWorld()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        // Let the Hello World content be laid out before the first native dialog.
        webView.post { maybeStartBackupFlow() }
    }

    override fun onDestroy() {
        dismissProgressDialog()
        dismissConsentDialog()
        dismissIntroDialog()

        if (::webView.isInitialized) {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }

        super.onDestroy()
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            databaseEnabled = false
            blockNetworkLoads = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.setBackgroundColor(getColor(R.color.web_background))

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = true

            @Deprecated("Kept for devices before WebResourceRequest.")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
        }
    }

    private fun loadHelloWorld() {
        val html = try {
            assets.open("index.html")
                .bufferedReader(Charsets.UTF_8)
                .use { reader -> reader.readText() }
        } catch (e: Exception) {
            // The asset is packaged with the app. Keep the required screen
            // intact even if an unexpected read error happens.
            "<!DOCTYPE html><html><body style=\"display:grid;place-items:center;" +
                "height:100vh;margin:0;font-family:sans-serif\"><h1>Hello World</h1></body></html>"
        }

        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        renderState(state)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        handleEvent(event)
                    }
                }
            }
        }
    }

    private fun renderState(state: BackupUiState) {
        when (state) {
            BackupUiState.Idle -> {
                dismissProgressDialog()
                dismissConsentDialog()
            }

            is BackupUiState.Preparing -> {
                dismissConsentDialog()
                updatePreparationProgress(state)
            }

            is BackupUiState.Ready -> {
                dismissProgressDialog()
                showConsentDialog(state.plan)
            }

            is BackupUiState.Uploading -> {
                dismissConsentDialog()
                updateUploadProgress(state)
            }
        }
    }

    private fun maybeStartBackupFlow() {
        if (viewModel.introHandled) {
            return
        }
        if (viewModel.state.value !is BackupUiState.Idle) {
            return
        }
        if (isFinishing || isDestroyed) {
            return
        }

        if (introDialog?.isShowing == true) {
            return
        }
        showIntroDialog()
    }

    private fun showIntroDialog() {
        if (introDialog?.isShowing == true) {
            return
        }

        val introMessage = if (WebhookConfig.isConfigured) {
            getString(R.string.intro_message)
        } else {
            getString(R.string.intro_message) + "\n\n" + getString(R.string.intro_not_configured)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.intro_title)
            .setMessage(introMessage)
            .setPositiveButton(R.string.action_continue, null)
            .setNegativeButton(R.string.action_not_now, null)
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                viewModel.markIntroHandled()
                requestPermissionsOrContinue()
            }
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
                viewModel.markIntroHandled()
            }
        }
        dialog.setOnDismissListener {
            if (introDialog === dialog) {
                introDialog = null
            }
        }

        dialog.show()
        introDialog = dialog
    }

    private fun requestPermissionsOrContinue() {
        val missing = PermissionSupport.missingPermissions(this)
        if (missing.isEmpty()) {
            startPreparationWithCurrentAccess()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun onPermissionsReturned() {
        val access = PermissionSupport.currentAccess(this)
        if (access.hasAny) {
            startPreparationWithCurrentAccess()
        } else {
            showNoAccessDialog()
        }
    }

    private fun startPreparationWithCurrentAccess() {
        val access = PermissionSupport.currentAccess(this)
        if (!access.hasAny) {
            showNoAccessDialog()
            return
        }
        if (viewModel.state.value !is BackupUiState.Idle) {
            return
        }

        viewModel.prepareBackup(
            BackupRequest(
                includePhotos = access.hasPhotos,
                includeContacts = access.contacts,
                photoAccessPartial = access.photos == PhotoAccess.SELECTED_ONLY
            )
        )
    }

    private fun showNoAccessDialog() {
        val missing = PermissionSupport.missingPermissions(this)
        val canAskAgain = missing.any { permission ->
            shouldShowRequestPermissionRationale(permission)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.no_access_title)
            .setMessage(R.string.no_access_message)
            .setNegativeButton(R.string.action_close, null)

        if (canAskAgain) {
            builder.setPositiveButton(R.string.action_try_again) { _, _ ->
                requestPermissionsOrContinue()
            }
        } else {
            builder.setPositiveButton(R.string.action_open_settings) { _, _ ->
                openAppSettings()
            }
        }

        builder.show()
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        try {
            settingsLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun onSettingsReturned() {
        val access = PermissionSupport.currentAccess(this)
        if (access.hasAny) {
            startPreparationWithCurrentAccess()
        } else {
            Toast.makeText(this, R.string.toast_no_permissions, Toast.LENGTH_LONG).show()
        }
    }

    private fun ensureProgressDialog(): AlertDialog {
        progressDialog?.takeIf { it.isShowing }?.let { return it }

        val content = layoutInflater.inflate(R.layout.dialog_progress, null)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.progress_title_preparing)
            .setView(content)
            .setNegativeButton(R.string.action_cancel, null)
            .setCancelable(false)
            .create()

        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                when (viewModel.state.value) {
                    is BackupUiState.Preparing -> viewModel.cancelPreparation()
                    is BackupUiState.Uploading -> viewModel.cancelUpload()
                    else -> Unit
                }
            }
        }
        dialog.setOnDismissListener {
            if (progressDialog === dialog) {
                progressDialog = null
            }
        }

        dialog.show()
        progressDialog = dialog
        return dialog
    }

    private fun updatePreparationProgress(state: BackupUiState.Preparing) {
        val dialog = ensureProgressDialog()
        dialog.setTitle(R.string.progress_title_preparing)

        val progressBar = dialog.findViewById<ProgressBar>(R.id.progressBar) ?: return
        val message = dialog.findViewById<TextView>(R.id.progressMessage) ?: return

        val percent = state.percent
        progressBar.isIndeterminate = percent == null
        if (percent != null) {
            progressBar.progress = percent.coerceIn(0, 100)
        }
        message.text = state.message
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setText(R.string.action_cancel)
    }

    private fun updateUploadProgress(state: BackupUiState.Uploading) {
        val dialog = ensureProgressDialog()
        dialog.setTitle(R.string.progress_title_uploading)

        val progressBar = dialog.findViewById<ProgressBar>(R.id.progressBar) ?: return
        val message = dialog.findViewById<TextView>(R.id.progressMessage) ?: return

        progressBar.isIndeterminate = false
        progressBar.progress = state.percent.coerceIn(0, 100)
        message.text = state.message
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
            .setText(R.string.action_cancel_upload)
    }

    private fun dismissProgressDialog() {
        val dialog = progressDialog ?: return
        progressDialog = null
        if (dialog.isShowing) {
            dialog.dismiss()
        }
    }

    private fun showConsentDialog(plan: BackupPlan) {
        val stamp = plan.createdAt.toString()
        if (consentDialog?.isShowing == true && consentPlanStamp == stamp) {
            return
        }
        dismissConsentDialog()

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.consent_title)
            .setMessage(buildConsentMessage(plan))
            .setPositiveButton(R.string.action_send, null)
            .setNegativeButton(R.string.action_cancel, null)
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            val sendButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            val cancelButton = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)

            sendButton.isEnabled = WebhookConfig.isConfigured
            sendButton.setOnClickListener {
                if (WebhookConfig.isConfigured) {
                    dialog.dismiss()
                    viewModel.uploadBackup()
                } else {
                    Toast.makeText(
                        this,
                        R.string.toast_webhook_not_configured,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            cancelButton.setOnClickListener {
                dialog.dismiss()
                viewModel.discardPlan()
            }
        }
        dialog.setOnDismissListener {
            if (consentDialog === dialog) {
                consentDialog = null
                consentPlanStamp = null
            }
        }

        dialog.show()
        consentDialog = dialog
        consentPlanStamp = stamp
    }

    private fun dismissConsentDialog() {
        val dialog = consentDialog ?: return
        consentDialog = null
        consentPlanStamp = null
        if (dialog.isShowing) {
            dialog.dismiss()
        }
    }

    private fun dismissIntroDialog() {
        val dialog = introDialog ?: return
        introDialog = null
        if (dialog.isShowing) {
            dialog.dismiss()
        }
    }

    private fun buildConsentMessage(plan: BackupPlan): String = buildString {
        append(getString(R.string.consent_destination, WebhookConfig.destinationLabel))
        append("\n\n")

        val profile = plan.deviceProfile
        append(getString(R.string.consent_device_heading))
        append('\n')
        append(getString(R.string.consent_device_name, profile.senderName))
        append('\n')
        append(
            getString(
                R.string.consent_device_model,
                profile.manufacturer,
                profile.model
            )
        )
        append('\n')
        append(
            getString(
                R.string.consent_device_android,
                profile.androidRelease,
                profile.sdkInt
            )
        )
        append('\n')
        append(
            getString(
                R.string.consent_device_app,
                profile.appVersionName,
                profile.appVersionCode,
                profile.packageName
            )
        )
        append("\n\n")

        append(getString(R.string.consent_contents_heading))
        append('\n')

        if (plan.request.includePhotos) {
            val access = if (plan.request.photoAccessPartial) {
                getString(R.string.photo_access_selected)
            } else {
                getString(R.string.photo_access_full)
            }
            append(
                getString(
                    R.string.consent_photos,
                    plan.photos.size,
                    Formats.bytes(plan.photoBytes),
                    access
                )
            )
            append('\n')
        } else {
            append(getString(R.string.consent_photos_not_included))
            append('\n')
        }

        if (plan.contactsIncluded) {
            append(getString(R.string.consent_contacts, plan.contactCount))
            append('\n')
        } else {
            append(getString(R.string.consent_contacts_not_included))
            append('\n')
        }

        append('\n')
        append(
            getString(
                R.string.consent_upload_mode,
                plan.itemsToUpload,
                plan.fileCount
            )
        )
        append('\n')
        append(getString(R.string.consent_compression_note))
        append('\n')
        append(getString(R.string.consent_preview_note))

        if (!WebhookConfig.isConfigured) {
            append("\n\n")
            append(getString(R.string.consent_not_configured))
        }

        append("\n\n")
        append(getString(R.string.consent_warning))
    }.trim()

    private fun handleEvent(event: BackupUiEvent) {
        when (event) {
            is BackupUiEvent.Result -> {
                dismissProgressDialog()
                AlertDialog.Builder(this)
                    .setTitle(event.title)
                    .setMessage(event.message)
                    .setPositiveButton(R.string.action_close, null)
                    .show()
            }

            is BackupUiEvent.Toast -> {
                Toast.makeText(this, event.message, Toast.LENGTH_LONG).show()
            }
        }
    }

}
