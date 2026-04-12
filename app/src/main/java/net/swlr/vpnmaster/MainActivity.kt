package net.swlr.vpnmaster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import net.swlr.vpnmaster.ui.navigation.AppNavigation
import net.swlr.vpnmaster.ui.theme.VpnMasterTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VpnMasterTheme {
                AppNavigation()
            }
        }
    }
}
