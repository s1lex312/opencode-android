package ai.opencode.android

import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.navigation.OpencodeNavHost
import ai.opencode.android.ui.theme.OpencodeTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container: AppContainer = (application as OpencodeApp).container
        setContent {
            OpencodeTheme {
                OpencodeNavHost(container = container)
            }
        }
    }
}
