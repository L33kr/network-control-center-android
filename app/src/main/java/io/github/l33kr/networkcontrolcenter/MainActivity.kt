package io.github.l33kr.networkcontrolcenter

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.l33kr.networkcontrolcenter.byedpi.profile.GenericProfileLabelsMigration
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileEngineBridge
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileStore
import io.github.l33kr.networkcontrolcenter.core.AndroidUnifiedEngineController
import io.github.l33kr.networkcontrolcenter.tgws.TgWsController
import io.github.l33kr.networkcontrolcenter.ui.theme.DpiControlTheme
import io.github.l33kr.networkcontrolcenter.ui.v2.DpiControlApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ProfileStore.ensureInitialized(applicationContext)
        GenericProfileLabelsMigration.apply(applicationContext)
        ProfileEngineBridge.applyActiveSet(applicationContext)

        setContent {
            DpiControlTheme {
                val controller = remember { AndroidUnifiedEngineController(applicationContext) }
                val scope = rememberCoroutineScope()
                val vpnPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        ProfileEngineBridge.applyActiveSet(applicationContext)
                        scope.launch { controller.startByeDpi() }
                    }
                }

                DpiControlApp(
                    controller = controller,
                    onByeDpiChange = { enabled ->
                        if (!enabled) {
                            scope.launch { controller.stopByeDpi() }
                        } else {
                            ProfileEngineBridge.applyActiveSet(applicationContext)
                            val permissionIntent = VpnService.prepare(this@MainActivity)
                            if (permissionIntent == null) {
                                scope.launch { controller.startByeDpi() }
                            } else {
                                vpnPermissionLauncher.launch(permissionIntent)
                            }
                        }
                    },
                    onApplyTelegramProxy = {
                        if (!TgWsController.openTelegramProxy(applicationContext)) {
                            Toast.makeText(
                                this,
                                "Не удалось открыть Telegram-клиент",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }
    }
}
