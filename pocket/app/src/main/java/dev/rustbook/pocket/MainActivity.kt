package dev.rustbook.pocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.rustbook.pocket.ui.PocketTheme
import dev.rustbook.pocket.ui.ReaderRoute

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
    }
}
