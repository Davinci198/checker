package ro.davinci.checker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import ro.davinci.checker.ui.CheckerApp
import ro.davinci.checker.ui.CheckerTheme
import ro.davinci.checker.watch.CpuWatch

class MainActivity : ComponentActivity() {

    private val viewModel: CheckerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dacă paznicul CPU era pornit, procesul poate fi fost omorât între timp;
        // pornirea se face din activitate, deci e permisă.
        if (CpuWatch.isEnabled(this)) {
            runCatching { CpuWatch.setEnabled(this, true) }
        }
        enableEdgeToEdge()
        setContent {
            CheckerTheme {
                CheckerApp(viewModel)
            }
        }
    }
}