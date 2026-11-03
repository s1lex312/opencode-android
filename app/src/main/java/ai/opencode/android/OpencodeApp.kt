package ai.opencode.android

import ai.opencode.android.di.AppContainer
import android.app.Application
import kotlinx.coroutines.runBlocking

class OpencodeApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Before anything reads settings: apply a cloud restore Android dropped in at install time.
        runBlocking { runCatching { container.configBackup.onAppStart() } }
        container.bootstrap()
    }
}
