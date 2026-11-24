package com.shilapi.xcertplay.backup

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/**
 * Hosts a temporary LAN HTTP + QR dialog for phone-based settings backup/restore and
 * diagnostic report download. The server stops automatically when the dialog closes or idles.
 */
object SettingsBackupDialog {
    fun show(
        activity: Activity,
        appVersion: String,
        reportProvider: (() -> ByteArray)? = null,
        reportMode: Boolean = false,
        onImported: (SettingsImportOutcome) -> Unit = {},
    ) {
        val page = runCatching {
            activity.assets.open("settings-backup.html").use { it.readBytes() }
        }.getOrNull()
        val codePage = runCatching {
            activity.assets.open("settings-code.html").use { it.readBytes() }
        }.getOrNull()
        if (page == null) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.qr_backup_title)
                .setMessage(R.string.qr_backup_start_failed)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val server = SettingsBackupServer(
            page = page,
            export = { SettingsBackupCodec.export(activity.applicationContext, appVersion) },
            import = { body -> SettingsBackupCodec.import(activity.applicationContext, body) },
            report = reportProvider,
            codePage = codePage,
        ).apply {
            if (reportMode) landing = "#report"
        }

        val endpoint = server.start()
        val addresses = SettingsBackupServer.addressCandidates()
        if (endpoint == null || addresses.isEmpty()) {
            server.stop()
            AlertDialog.Builder(activity)
                .setTitle(if (reportMode) R.string.qr_report_title else R.string.qr_backup_title)
                .setMessage(R.string.qr_backup_no_network)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val primaryIp = addresses.first()
        val fullUrl = endpoint.url(primaryIp) + if (reportMode) "#report" else ""
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val qrSize = dp(240).coerceAtLeast(240)
        val qrBitmap = runCatching { SettingsQrCode.encode(fullUrl, qrSize) }.getOrNull()

        val statusView = TextView(activity).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#9AA0A6"))
            setPadding(0, dp(10), 0, 0)
            text = activity.getString(
                if (reportMode) R.string.qr_report_hint else R.string.qr_backup_hint,
            )
        }

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            if (qrBitmap != null) {
                addView(
                    ImageView(activity).apply {
                        setImageBitmap(qrBitmap)
                    },
                    LinearLayout.LayoutParams(qrSize, qrSize),
                )
            }
            addView(
                TextView(activity).apply {
                    textSize = 15f
                    setTextColor(Color.parseColor("#E8EAED"))
                    setPadding(0, dp(12), 0, 0)
                    gravity = Gravity.CENTER_HORIZONTAL
                    text = buildString {
                        append(activity.getString(R.string.qr_backup_url_label, fullUrl))
                        if (endpoint.code.isNotEmpty()) {
                            append('\n')
                            append(
                                activity.getString(
                                    R.string.qr_backup_typed_label,
                                    endpoint.typedAddress(primaryIp),
                                    endpoint.code,
                                ),
                            )
                        }
                        if (addresses.size > 1) {
                            append('\n')
                            append(
                                activity.getString(
                                    R.string.qr_backup_alt_ips,
                                    addresses.drop(1).joinToString(", ") { endpoint.typedAddress(it) },
                                ),
                            )
                        }
                    }
                },
            )
            addView(statusView)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (reportMode) R.string.qr_report_title else R.string.qr_backup_title)
            .setView(ScrollView(activity).apply { addView(body) })
            .setNegativeButton(R.string.qr_backup_close, null)
            .create()

        server.onImported = { outcome ->
            activity.runOnUiThread {
                statusView.text = if (outcome.invalid) {
                    activity.getString(R.string.qr_backup_invalid)
                } else {
                    activity.getString(R.string.qr_backup_imported, outcome.applied, outcome.skipped)
                }
                onImported(outcome)
            }
        }
        server.onIdle = {
            activity.runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            Thread({ server.stop() }, "settings-backup-stop").apply { isDaemon = true; start() }
        }
        dialog.show()
    }
}
