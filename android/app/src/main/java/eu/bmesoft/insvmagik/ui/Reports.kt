package eu.bmesoft.insvmagik.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Where testers send reports (docs/tester-guide.md). */
const val REPORT_ADDRESS = "office@bmesoft.eu"

/**
 * Opens an email to [REPORT_ADDRESS] with the report as its text, in the phone's email app; the
 * tester adds a line on what they saw and sends it. Nothing is sent by the app itself. Without an
 * email app the report goes to the clipboard instead, to paste anywhere.
 */
fun emailReport(context: Context, subject: String, report: String) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_ADDRESS))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, "What I saw:\n\n\n---\n$report")
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("insvMagik report", report))
        Toast.makeText(context, "No email app: the report is copied, send it to $REPORT_ADDRESS", Toast.LENGTH_LONG).show()
    }
}
