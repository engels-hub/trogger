package lv.papart.trogger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import lv.papart.trogger.ui.TroggerTheme
import lv.papart.trogger.ui.ExpensesScreen
import lv.papart.trogger.ui.ReportsScreen
import lv.papart.trogger.ui.SettingsDialog
import lv.papart.trogger.ui.ShiftScreen
import lv.papart.trogger.ui.TripsScreen

private data class Tab(val title: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Maiņa", Icons.Default.DirectionsCar),
    Tab("Braucieni", Icons.Default.Route),
    Tab("Izdevumi", Icons.AutoMirrored.Filled.ReceiptLong),
    Tab("Atskaites", Icons.Default.Assessment),
)

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        // Bring tracking back if the system killed it while a shift was open or auto mode is on.
        val granted = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted && TrackingService.live.value == null &&
            (Repo.settings.value.autoEnabled || Repo.db.activeTrip() != null)
        ) TrackingService.sync(this)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Repo.refresh()
        setContent {
            TroggerTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var settings by rememberSaveable { mutableStateOf(false) }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(if (tab == 0) "trogger" else TABS[tab].title) },
                            actions = {
                                IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Iestatījumi") }
                            },
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            TABS.forEachIndexed { i, t ->
                                NavigationBarItem(
                                    selected = tab == i, onClick = { tab = i },
                                    icon = { Icon(t.icon, null) }, label = { Text(t.title) },
                                )
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.padding(pad)) {
                        when (tab) {
                            0 -> ShiftScreen()
                            1 -> TripsScreen()
                            2 -> ExpensesScreen()
                            else -> ReportsScreen()
                        }
                    }
                }
                if (settings) SettingsDialog { settings = false }
            }
        }
    }
}
