package org.mihomet.app

import android.app.Application
import com.topjohnwu.superuser.Shell
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.mihomet.app.di.appModule

class MihometApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Configure LibSu
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )

        // Start Koin
        startKoin {
            androidLogger()
            androidContext(this@MihometApplication)
            modules(appModule)
        }
    }
}
