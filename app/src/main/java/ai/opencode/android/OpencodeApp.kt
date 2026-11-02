package ai.opencode.android

import ai.opencode.android.di.AppContainer
import android.app.Application

class OpencodeApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.bootstrap()
    }
}
