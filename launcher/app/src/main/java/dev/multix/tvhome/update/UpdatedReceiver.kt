package dev.multix.tvhome.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.multix.tvhome.HomeActivity
import dev.multix.tvhome.core.Bg

/**
 * Android kills the launcher to replace it and then shows whatever app was underneath. After an update that
 * Settings > About installed, the new version brings the home screen straight back. (Installs over adb leave
 * no mark, so they don't trigger this.) On Android 10 a background start needs the "display over other
 * apps" grant, which debloat.sh gives.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Updater.consumeInstallMark(ctx)) return
        try {
            ctx.startActivity(Intent(ctx, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Bg.warn("could not reopen home after the update", e)
        }
    }
}
