package com.memevault.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Experimental, user-granted Zalo folder bridge. The Android document provider decides whether a
 * Zalo private folder can be selected; this screen never assumes that it can access app-private
 * data or that Zalo will index the copied PNGs as native stickers.
 */
class ZaloSyncActivity : Activity() {
    private lateinit var manager: ZaloSyncManager
    private lateinit var folderStatus: TextView
    private lateinit var syncStatus: TextView
    private lateinit var chooseButton: Button
    private lateinit var forgetButton: Button
    private lateinit var syncButton: Button
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var destroyed = false
    @Volatile
    private var syncing = false
    private var managerTransferred = false
    private var removeSyncObserver: (() -> Unit)? = null
    private var lastShownJobId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = ZaloSyncManager(this, AssetRepository(this))
        configureSystemBars()
        buildUi()
        renderFolderState()
        removeSyncObserver = ZaloSyncJobCoordinator.observe { snapshot ->
            mainHandler.post {
                if (!destroyed) applyJobSnapshot(snapshot)
            }
        }
        // observe() delivers asynchronously to the main handler so worker callbacks never touch
        // views directly; render once synchronously to close the tiny rotation/tap race window.
        applyJobSnapshot(ZaloSyncJobCoordinator.snapshot())
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(mvBackground)
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        applySystemBarInsets(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(mvIconButton(R.drawable.ic_arrow_back, getString(R.string.back), ::finish),
            LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            text = getString(R.string.zalo_sync_title)
            applyMvTextStyle(20f, mvText, 1)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(TextView(this).apply {
            text = getString(R.string.zalo_sync_experimental_label)
            textSize = 10f
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(mvWarningInk)
            setPadding(dp(8), 0, dp(8), 0)
            background = roundedBackground(mvWarningSurface, radiusDp = MV_RADIUS_SMALL, strokeColor = mvLine)
            contentDescription = getString(R.string.zalo_sync_experimental_label)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))
        root.addView(header)

        root.addView(TextView(this).apply {
            text = getString(R.string.zalo_sync_subtitle)
            applyMvTextStyle(13f, mvMuted, 4)
            setPadding(dp(52), 0, 0, dp(14))
        })

        val noticeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBackground(mvWarningSurface, radiusDp = 6,
                strokeColor = mvPrimaryPressed)
        }
        noticeCard.addView(TextView(this).apply {
            text = getString(R.string.zalo_sync_experimental_label)
            applyMvTextStyle(11f, mvWarningInk, 1)
            letterSpacing = 0.08f
        })
        noticeCard.addView(TextView(this).apply {
            text = getString(R.string.zalo_sync_warning)
            applyMvTextStyle(13f, mvWarningInk, 6)
            setPadding(0, dp(5), 0, 0)
        })
        root.addView(noticeCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(18) })

        root.addView(TextView(this).apply {
            text = getString(R.string.zalo_sync_folder_section)
            applyMvTextStyle(16f, mvText, 1)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) })
        root.addView(TextView(this).apply {
            text = getString(R.string.zalo_sync_folder_label)
            applyMvTextStyle(12f, mvMuted, 1)
        })
        folderStatus = TextView(this).apply {
            applyMvTextStyle(13f, mvText, 4)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = roundedBackground(mvSurface, radiusDp = 6,
                strokeColor = mvLine)
            contentDescription = getString(R.string.zalo_sync_folder_content_description)
        }
        root.addView(folderStatus, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6); bottomMargin = dp(10) })

        val folderActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chooseButton = mvButton(getString(R.string.zalo_sync_choose_folder), ::chooseFolder, primary = true)
        folderActions.addView(chooseButton, LinearLayout.LayoutParams(0, dp(50), 1f))
        forgetButton = mvButton(getString(R.string.zalo_sync_forget_folder), ::forgetFolder)
        folderActions.addView(forgetButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginStart = dp(8)
        })
        root.addView(folderActions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(50),
        ).apply { bottomMargin = dp(16) })

        syncButton = mvButton(getString(R.string.zalo_sync_action), ::startSync, primary = true).apply {
            contentDescription = getString(R.string.zalo_sync_action_content_description)
        }
        root.addView(syncButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52),
        ).apply { bottomMargin = dp(10) })

        syncStatus = TextView(this).apply {
            applyMvTextStyle(13f, mvMuted, 20)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(mvSurface, radiusDp = 6, strokeColor = mvLine)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(syncStatus, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(18) })

        val fallbackCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBackground(mvSurfaceAlt, radiusDp = 6, strokeColor = mvLine)
        }
        fallbackCard.addView(TextView(this).apply {
            text = getString(R.string.zalo_sync_fallback_title)
            applyMvTextStyle(13f, mvText, 2)
        })
        fallbackCard.addView(TextView(this).apply {
            text = getString(R.string.zalo_sync_fallback)
            applyMvTextStyle(12f, mvMuted, 8)
            setPadding(0, dp(5), 0, 0)
        })
        root.addView(fallbackCard)

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun renderFolderState() {
        val uri = manager.savedTreeUri()
        if (uri == null) {
            folderStatus.text = getString(R.string.zalo_sync_no_folder)
            forgetButton.isEnabled = false
            syncButton.isEnabled = false
            syncStatus.text = getString(R.string.zalo_sync_select_first)
            return
        }
        folderStatus.text = getString(R.string.zalo_sync_selected_folder, manager.displayName(uri))
        forgetButton.isEnabled = true
        syncButton.isEnabled = !syncing
        if (!syncing) syncStatus.text = getString(R.string.zalo_sync_ready)
    }

    @Suppress("DEPRECATION")
    private fun chooseFolder() {
        if (syncing || ZaloSyncJobCoordinator.snapshot().running) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }, REQUEST_FOLDER)
    }

    private fun forgetFolder() {
        if (syncing || ZaloSyncJobCoordinator.snapshot().running) return
        manager.forgetTreeUri()
        renderFolderState()
        Toast.makeText(this, R.string.zalo_sync_folder_forgotten, Toast.LENGTH_SHORT).show()
    }

    private fun startSync() {
        if (syncing || ZaloSyncJobCoordinator.snapshot().running || manager.savedTreeUri() == null) return
        if (ZaloSyncJobCoordinator.start(manager) == null) {
            applyJobSnapshot(ZaloSyncJobCoordinator.snapshot())
            return
        }
        // The process coordinator owns the manager while this job is running. The Activity must
        // not close it during a configuration change; ownership is released from onDestroy().
        managerTransferred = true
        applyJobSnapshot(ZaloSyncJobCoordinator.snapshot())
    }

    private fun applyJobSnapshot(snapshot: ZaloSyncJobCoordinator.Snapshot) {
        syncing = snapshot.running
        if (snapshot.running) {
            chooseButton.isEnabled = false
            forgetButton.isEnabled = false
            syncButton.isEnabled = false
            syncStatus.text = getString(R.string.zalo_sync_running)
            return
        }

        renderFolderState()
        val completedJobId = snapshot.jobId ?: return
        val outcome = snapshot.outcome ?: return
        if (lastShownJobId == completedJobId) return
        lastShownJobId = completedJobId
        outcome.summary?.let(::showSummary) ?: run {
            val message = outcome.errorMessage ?: getString(R.string.zalo_sync_failed)
            syncStatus.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun showSummary(summary: ZaloSyncManager.SyncSummary) {
        val message = buildString {
            append(getString(R.string.zalo_sync_complete))
            append("\n")
            append(getString(R.string.zalo_sync_pulled, summary.pulled))
            append("\n")
            append(getString(R.string.zalo_sync_pushed, summary.pushed))
            append("\n")
            append(getString(R.string.zalo_sync_existing, summary.alreadyInVault, summary.alreadyOnDevice))
            if (summary.errors.isNotEmpty()) {
                append("\n")
                append(getString(R.string.zalo_sync_error_count, summary.errors.size))
                summary.errors.forEach { error -> append("\n• ").append(error) }
            }
        }
        syncStatus.text = message
        Toast.makeText(this, getString(R.string.zalo_sync_complete), Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (destroyed || requestCode != REQUEST_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (manager.rememberTreeUri(uri, flags)) {
            renderFolderState()
            Toast.makeText(this, R.string.zalo_sync_folder_saved, Toast.LENGTH_SHORT).show()
        } else {
            syncStatus.text = getString(R.string.zalo_sync_permission_failed)
            Toast.makeText(this, R.string.zalo_sync_permission_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun configureSystemBars() {
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            window.isNavigationBarContrastEnforced = false
            window.decorView.windowInsetsController?.setSystemBarsAppearance(
                if (night) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            @Suppress("DEPRECATION")
            run {
                window.statusBarColor = mvBackground
                window.navigationBarColor = mvBackground
                window.decorView.systemUiVisibility = if (night) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }

    private fun applySystemBarInsets(root: View) {
        val left = dp(16)
        val top = dp(8)
        val right = dp(16)
        val bottom = dp(22)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    left + insets.systemWindowInsetLeft,
                    top + insets.systemWindowInsetTop,
                    right + insets.systemWindowInsetRight,
                    bottom + insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        root.requestApplyInsets()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        destroyed = true
        removeSyncObserver?.invoke()
        removeSyncObserver = null
        if (managerTransferred) {
            ZaloSyncJobCoordinator.releaseManager(manager)
        } else {
            val currentManager = manager
            Thread {
                runCatching { currentManager.close() }
            }.apply {
                name = "MemeVault-zalo-manager-close"
                isDaemon = true
                start()
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_FOLDER = 701
    }
}
