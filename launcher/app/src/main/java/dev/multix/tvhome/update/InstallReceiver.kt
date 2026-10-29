package dev.multix.tvhome.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dev.multix.tvhome.core.Bg

/** Status of an update install session: shows Android's confirm screen, or reports how the install ended. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    Updater.installFinished(ctx, PackageInstaller.STATUS_FAILURE)
                    return
                }
                try {
                    ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Bg.warn("install confirm screen failed", e)
                    Updater.installFinished(ctx, PackageInstaller.STATUS_FAILURE)
                }
            }
            else -> {
                if (status != PackageInstaller.STATUS_SUCCESS) {
                    Bg.warn("update install ended: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                }
                Updater.installFinished(ctx, status)
            }
        }
    }
}
