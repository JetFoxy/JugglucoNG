// JugglucoNG — Medtrum EasySense setup wizard.
// Medtrum patches have no QR onboarding: the user types the serial printed on the patch,
// entered as a decimal number (the advert carries it little-endian in manufacturer 0x4781).

package tk.glucodata.ui.setup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.drivers.medtrum.MedtrumPersistence
import tk.glucodata.drivers.medtrum.MedtrumRegistry

private const val TAG = "MedtrumSetupWizard"

private enum class MedtrumSetupStep { ENTRY, CONNECTING, SUCCESS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedtrumSetupWizard(
    onDismiss: () -> Unit,
    onNavigateToReadiness: () -> Unit = {},
    onComplete: () -> Unit,
) {
    val ui = rememberWizardUiMetrics()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(MedtrumSetupStep.ENTRY) }
    var serialInput by remember { mutableStateOf("") }
    var attachedSerial by remember { mutableStateOf("") }
    var pendingSerial by remember { mutableStateOf<Long?>(null) }

    val normalized = serialInput.trim()
    val serialNumber = MedtrumPersistence.parseSerialNumber(normalized)
    val isValid = serialNumber != null

    fun startAttach(serial: Long) {
        attachedSerial = MedtrumPersistence.formatSerialHex(serial)
        step = MedtrumSetupStep.CONNECTING
        scope.launch {
            try {
                MedtrumRegistry.addSensor(context, serial)
                delay(2000)
                step = MedtrumSetupStep.SUCCESS
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to add Medtrum sensor: ${t.message}")
                step = MedtrumSetupStep.ENTRY
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val pending = pendingSerial
        pendingSerial = null
        if (pending != null && hasBleScanPermissions(context)) startAttach(pending)
    }

    fun requestPermissionsAndAttach(serial: Long) {
        if (!hasBleScanPermissions(context)) {
            val required = requiredBleScanPermissions()
            if (required.isNotEmpty()) {
                pendingSerial = serial
                permissionLauncher.launch(required)
                return
            }
        }
        startAttach(serial)
    }

    BackHandler {
        if (step == MedtrumSetupStep.ENTRY) onDismiss() else step = MedtrumSetupStep.ENTRY
    }

    LaunchedEffect(step) {
        if (step == MedtrumSetupStep.SUCCESS) {
            delay(SENSOR_SETUP_SUCCESS_AUTO_ADVANCE_MS)
            onComplete()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.medtrum_sensor)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                }
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = step,
            modifier = Modifier.padding(padding),
            label = "MedtrumWizard",
        ) { current ->
            when (current) {
                MedtrumSetupStep.ENTRY -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = ui.horizontalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top,
                ) {
                    Spacer(Modifier.height(ui.spacerMedium))

                    tk.glucodata.ui.CgmReadinessSetupBanner(onOpenReadiness = onNavigateToReadiness)

                    Spacer(Modifier.height(ui.spacerLarge))

                    Text(
                        text = stringResource(R.string.medtrum_sensor_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(ui.spacerMedium))

                    OutlinedTextField(
                        value = serialInput,
                        onValueChange = { input ->
                            serialInput = input.filter {
                                it.isDigit() || it in 'a'..'f' || it in 'A'..'F'
                            }.take(8).uppercase()
                        },
                        label = { Text(stringResource(R.string.serial_number_label)) },
                        placeholder = { Text(stringResource(R.string.medtrum_serial_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        isError = normalized.isNotEmpty() && !isValid,
                        supportingText = if (isValid && serialNumber != null) {
                            { Text(stringResource(R.string.medtrum_serial_echo, normalized, serialNumber)) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(ui.spacerMedium))

                    OutlinedButton(
                        onClick = { serialNumber?.let { requestPermissionsAndAttach(it) } },
                        enabled = isValid,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ui.buttonHeight),
                    ) {
                        Text(stringResource(R.string.confirm))
                    }
                }

                MedtrumSetupStep.CONNECTING -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    SensorSetupConnectingScreen(ui = ui, sensorLabel = attachedSerial.ifBlank { null })
                }

                MedtrumSetupStep.SUCCESS -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    SensorSetupSuccessScreen(ui = ui, sensorLabel = attachedSerial.ifBlank { null })
                }
            }
        }
    }
}
