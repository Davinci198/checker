package ro.davinci.checker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import ro.davinci.checker.ui.CheckerApp
import ro.davinci.checker.ui.CheckerTheme

class MainActivity : ComponentActivity() {

    private val viewModel: CheckerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CheckerTheme {
                CheckerApp(viewModel)
            }
        }
    }
}