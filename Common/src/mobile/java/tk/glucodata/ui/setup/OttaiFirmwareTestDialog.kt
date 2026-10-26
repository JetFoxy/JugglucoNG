@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import tk.glucodata.R
import tk.glucodata.drivers.ottai.OttaiFirmwareSession
import tk.glucodata.drivers.ottai.OttaiFirmwareStage
import tk.glucodata.drivers.ottai.OttaiFirmwareState

@Composable
internal fun OttaiFirmwareTestDialog(sensorId: String, address: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    var state by remember(sensorId, address) { mutableStateOf(OttaiFirmwareState()) }
    var session by remember(sensorId, address) { mutableStateOf<OttaiFirmwareSession?>(null) }
    var confirmInstall by remember { mutableStateOf(false) }
    var confirmParameters by remember { mutableStateOf(false) }
    DisposableEffect(sensorId, address) {
        var disposed = false
        val keepScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        val owned = OttaiFirmwareSession.start(context, sensorId, address) { if (!disposed) state = it }
        session = owned
        if (owned == null) state = state.copy(stage = OttaiFirmwareStage.FAILED, errorCode = "session_unavailable")
        onDispose {
            disposed = true
            owned?.dispose()
            view.keepScreenOn = keepScreenOn
        }
    }
    val stageText = when (state.stage) {
        OttaiFirmwareStage.CONNECTING -> R.string.ottai_ota_connecting
        OttaiFirmwareStage.AUTHENTICATING -> R.string.ottai_ota_authenticating
        OttaiFirmwareStage.CHECKING -> R.string.ottai_ota_checking
        OttaiFirmwareStage.READY -> if (state.recovery) R.string.ottai_ota_recovery else R.string.ottai_ota_ready
        OttaiFirmwareStage.NO_UPDATE -> R.string.ottai_ota_no_update
        OttaiFirmwareStage.INSTALLING -> R.string.ottai_ota_installing
        OttaiFirmwareStage.RECONNECTING -> R.string.ottai_ota_reconnecting
        OttaiFirmwareStage.PREPARING -> R.string.ottai_ota_preparing
        OttaiFirmwareStage.COMPLETE -> R.string.ottai_ota_complete
        OttaiFirmwareStage.FAILED -> R.string.ottai_ota_failed
        OttaiFirmwareStage.CLOSED -> R.string.cancel
    }
    AlertDialog(
        onDismissRequest = { if (!state.transferring) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !state.transferring, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.ottai_ota_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ottai_ota_warning))
                Text(stringResource(stageText))
                if (state.firmware.isNotEmpty()) Text(stringResource(R.string.ottai_ota_version, state.firmware, state.target.ifEmpty { "—" }))
                if (state.totalBytes > 0) {
                    Text(stringResource(R.string.ottai_ota_package, state.totalBytes, state.sha256))
                    LinearWavyProgressIndicator(
                        progress = { state.acknowledgedBytes.toFloat() / state.totalBytes },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.errorCode.isNotEmpty()) Text(stringResource(R.string.ottai_ota_error_code, state.errorCode))
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.stage == OttaiFirmwareStage.READY) {
                    Button(onClick = { confirmInstall = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.ottai_ota_install)) }
                }
                if (!state.recovery && state.stage in setOf(OttaiFirmwareStage.READY, OttaiFirmwareStage.NO_UPDATE)) {
                    OutlinedButton(onClick = { confirmParameters = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.ottai_ota_parameters)) }
                }
                TextButton(onClick = onDismiss, enabled = !state.transferring,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.close))
                }
            }
        },
    )
    if (confirmInstall || confirmParameters) {
        AlertDialog(
            onDismissRequest = { confirmInstall = false; confirmParameters = false },
            title = { Text(stringResource(if (confirmInstall) R.string.ottai_ota_install else R.string.ottai_ota_parameters)) },
            text = { Text(stringResource(if (confirmInstall) R.string.ottai_ota_confirm else R.string.ottai_ota_parameters_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    if (confirmInstall) session?.install() else session?.prepareOnly()
                    confirmInstall = false
                    confirmParameters = false
                }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmInstall = false; confirmParameters = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
