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
import io.github.l33kr.networkcontrolcenter.nativecore.EngineMode
import io.github.l33kr.networkcontrolcenter.nativecore.EngineModeStore
import io.github.l33kr.networkcontrolcenter.tgws.TgWsController
import io.github.l33kr.networkcontrolcenter.ui.nativealpha.NativeControlApp
import io.github.l33kr.networkcontrolcenter.ui.theme.DpiControlTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ProfileStore.ensureInitialized(applicationContext)
        GenericProfileLabelsMigration.apply(applicationContext)
        ProfileEngineBridge.applyActiveSet(applicationContext)
        // This experimental build deliberately uses our own stream engine.
        EngineModeStore.save(applicationContext, EngineMode.NATIVE_ALPHA)

        setContent {
            DpiControlTheme {
                val controller = remember { AndroidUnifiedEngineController(applicationContext) }
                val scope = rememberCoroutineScope()
                val vpnPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        scope.launch { controller.startByeDpi() }
                    }
                }

                NativeControlApp(
                    controller = controller,
                    onVpnChange = { enabled ->
                        if (!enabled) {
                            scope.launch { controller.stopByeDpi() }
                        } else {
                            val permissionIntent = VpnService.prepare(this@MainActivity)
                            if (permissionIntent == null) {
                                scope.launch { controller.startByeDpi() }
                            } else {
                                vpnPermissionLauncher.launch(permissionIntent)
                            }
                        }
                    },
                    onTelegramChange = { enabled ->
                        scope.launch {
                            if (enabled) controller.startTgWs() else controller.stopTgWs()
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
