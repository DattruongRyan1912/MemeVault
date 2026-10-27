package com.memevault.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class ShareReceiverActivity : Activity() {
    private var jobId: Long = 0L
    private var removeObserver: (() -> Unit)? = null
    private var navigated = false
    @Volatile
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val message = TextView(this).apply {
            text = getString(R.string.share_receiver_loading)
            gravity = Gravity.CENTER
            textSize = 18f
        }
        setContentView(message)

        navigated = savedInstanceState?.getBoolean(KEY_NAVIGATED, false) ?: false
        val restoredJobId = savedInstanceState?.getLong(KEY_JOB_ID, 0L)?.takeIf { it > 0L }
        val uris = IntentMediaExtractor.uris(intent)
        jobId = restoredJobId?.takeIf { ShareImportJobCoordinator.snapshot(it) != null }
            ?: if (uris.isNotEmpty()) ShareImportJobCoordinator.start(this, uris) else 0L
        if (jobId == 0L) {
            finishWithMessage("Không tìm thấy media")
            return
        }

        removeObserver = ShareImportJobCoordinator.observe(jobId) { snapshot ->
            runOnUiThread {
                if (destroyed || snapshot.running || navigated) return@runOnUiThread
                val outcome = snapshot.outcome ?: return@runOnUiThread
                navigated = true
                val detail = outcome.errorMessage ?: buildString {
                    append("Đã lưu ${outcome.saved}")
                    if (outcome.duplicates > 0) append(", trùng ${outcome.duplicates}")
                    if (outcome.errors > 0) append(", lỗi ${outcome.errors}")
                }
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
                android.widget.Toast.makeText(this, detail, android.widget.Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(KEY_JOB_ID, jobId)
        outState.putBoolean(KEY_NAVIGATED, navigated)
        super.onSaveInstanceState(outState)
    }

    private fun finishWithMessage(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        destroyed = true
        removeObserver?.invoke()
        removeObserver = null
        super.onDestroy()
    }

    companion object {
        private const val KEY_JOB_ID = "share_import_job_id"
        private const val KEY_NAVIGATED = "share_import_navigated"
    }
}
