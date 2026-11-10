package dev.rustbook.pocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import dev.rustbook.pocket.ui.PocketTheme
import dev.rustbook.pocket.ui.ReaderRoute
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: ReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PocketTheme {
                ReaderRoute(viewModel)
            }
        }

        // Debug-only experiment used while bringing up on-device execution:
        //   adb shell am start -n <pkg>/dev.rustbook.pocket.MainActivity \
        //       --ez execprobe true
        if (intent?.getBooleanExtra("execprobe", false) == true) {
            lifecycleScope.launch {
                dev.rustbook.pocket.execprobe.ExecProbe
                    .run(applicationContext)
                    .also(dev.rustbook.pocket.execprobe.ExecProbe::report)
            }
        }

        if (intent?.getBooleanExtra("localexec", false) == true) {
            lifecycleScope.launch {
                dev.rustbook.pocket.execprobe.LocalExecProbe.run(applicationContext)
            }
        }
    }
}
