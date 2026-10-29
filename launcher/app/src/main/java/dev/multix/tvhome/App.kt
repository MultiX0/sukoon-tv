package dev.multix.tvhome

import android.app.Application
import android.widget.Toast
import dev.multix.tvhome.update.Updater
import dev.multix.tvhome.core.Bg
import dev.multix.tvhome.core.Fonts
import dev.multix.tvhome.core.Lang
import dev.multix.tvhome.core.Prefs
import dev.multix.tvhome.core.Presets
import dev.multix.tvhome.core.Ui
import dev.multix.tvhome.media.NowPlaying

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        // The home screen as the user left it becomes their first saved look, before anything can change it.
        Presets.saveFirstLook(this)
        Ui.init(this)
        NowPlaying.init(this)
        // Parse the fonts off the main thread before the first frame needs them.
        val ctx = Lang.wrap(this)
        Bg.run { Fonts.preload(ctx) }
        // Android restarts the launcher after replacing it with an update: say so once, and drop the download.
        Updater.justUpdated(this)?.let { v ->
            Bg.main.postDelayed({ Toast.makeText(ctx, ctx.getString(R.string.u_updated, Lang.brand(ctx), v), Toast.LENGTH_LONG).show() }, 1500)
        }
        Updater.cleanUp(this)
    }
}
