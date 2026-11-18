package eu.bmesoft.insvmagik.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Where testers report (docs/tester-guide.md): the project's GitHub issues. */
const val ISSUES_URL = "https://github.com/bmesoft/insvMagik/issues"

/**
 * Opens a new GitHub issue in the browser with [title] and the report filled in; the tester adds
 * what they saw and submits it (a GitHub account is needed). The report also goes to the clipboard
 * first, in case the form comes up empty (after signing in, say): then it's one paste away.
 * Nothing is sent by the app itself.
 */
fun reportIssue(context: Context, title: String, report: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("insvMagik report", report))
    val body = "**What I saw:**\n\n\n\n**Report from the app:**\n\n```text\n$report\n```\n"
    val url = Uri.parse("$ISSUES_URL/new").buildUpon()
        .appendQueryParameter("title", title)
        .appendQueryParameter("body", body)
        .build()
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url))
        Toast.makeText(context, "Report copied too: paste it if the form is empty", Toast.LENGTH_LONG).show()
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No browser: the report is copied; open $ISSUES_URL to report it", Toast.LENGTH_LONG).show()
    }
}
