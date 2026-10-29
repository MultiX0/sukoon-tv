package dev.multix.tvhome.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import dev.multix.tvhome.LyricsDream

/**
 * System options that need WRITE_SECURE_SETTINGS, which the owner grants over
 * adb. Every caller checks [available] first and hides the option otherwise.
 */
object SystemTweaks {
    private const val COMPONENTS = "screensaver_components"
    private const val ENABLED = "screensaver_enabled"
    private const val ON_SLEEP = "screensaver_activate_on_sleep"

    fun available(ctx: Context): Boolean =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun ours(ctx: Context) = ComponentName(ctx, LyricsDream::class.java).flattenToString()

    fun screensaverIsOurs(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        val comps = Settings.Secure.getString(cr, COMPONENTS) ?: ""
        return Settings.Secure.getInt(cr, ENABLED, 0) == 1 && comps.contains(ctx.packageName)
    }

    /** Switches the system screensaver to ours, or back to whatever was set before. */
    fun setScreensaver(ctx: Context, on: Boolean): Boolean = try {
        val cr = ctx.contentResolver
        if (on) {
            val prev = Settings.Secure.getString(cr, COMPONENTS) ?: ""
            if (!prev.contains(ctx.packageName)) Prefs.putS("prev_dream", prev)
            Settings.Secure.putString(cr, COMPONENTS, ours(ctx))
            Settings.Secure.putInt(cr, ENABLED, 1)
            Settings.Secure.putInt(cr, ON_SLEEP, 1)
        } else {
            Settings.Secure.putString(cr, COMPONENTS, Prefs.getS("prev_dream", ""))
        }
        true
    } catch (e: Exception) {
        false
    }

    /** On Android TV the screensaver starts when the screen-off timeout runs out. */
    fun delayMinutes(ctx: Context): Int =
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 15 * 60_000) / 60_000

    fun setDelayMinutes(ctx: Context, min: Int): Boolean = try {
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, min * 60_000)
    } catch (e: Exception) {
        false
    }

    fun animationScale(ctx: Context): Float =
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    /** Window, transition and animator scales together: 1 normal, 0.5 fast, 0 off. */
    fun setAnimationScale(ctx: Context, f: Float): Boolean = try {
        val cr = ctx.contentResolver
        Settings.Global.putFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, f)
        Settings.Global.putFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, f)
        Settings.Global.putFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, f)
        true
    } catch (e: Exception) {
        false
    }
}
