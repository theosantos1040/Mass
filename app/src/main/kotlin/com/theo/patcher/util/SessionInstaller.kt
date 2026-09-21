package com.theo.patcher.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

object SessionInstaller {

    fun install(context: Context, base: File, splits: List<File>, onLog: (String) -> Unit) {
        val pm = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val sessionId = pm.createSession(params)
        onLog("Session #$sessionId aberta")

        val session = pm.openSession(sessionId)
        try {
            writeApk(session, base, "base.apk", onLog)
            splits.forEachIndexed { i, s ->
                writeApk(session, s, "split_$i.apk", onLog)
            }

            val intent = Intent(context, InstallReceiver::class.java).apply {
                action = "com.theo.patcher.INSTALL_RESULT"
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)

            onLog("Commit session...")
            session.commit(pi.intentSender)
            onLog("Session commit enviada — o instalador do sistema vai aparecer")
        } finally {
            session.close()
        }
    }

    private fun writeApk(session: PackageInstaller.Session, apk: File, name: String, onLog: (String) -> Unit) {
        val len = apk.length()
        onLog("  → $name (${len / 1024} KB)")
        session.openWrite(name, 0, len).use { out ->
            apk.inputStream().use { it.copyTo(out) }
            session.fsync(out)
        }
    }
}
