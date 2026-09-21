package tk.glucodata.ui.alerts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tk.glucodata.Applic
import tk.glucodata.R
import tk.glucodata.settings.store.SettingKey
import tk.glucodata.settings.store.SettingsStore
import tk.glucodata.settings.store.SettingsStoreImpl
import tk.glucodata.settings.store.SharedPreferencesKeyValueStore

/**
 * Special marker values for sound selection:
 * - null/empty: App Default Sound (Ember)
 * - SYSTEM_DEFAULT_SOUND: System's default notification sound
 * - Any other URI: Custom sound
 */
const val SYSTEM_DEFAULT_SOUND = "SYSTEM_DEFAULT"

private const val PREFS_NAME = "custom_sounds"

/** The custom-sounds area's keys, on the `custom_sounds` prefs file. */
object CustomSoundKeys {
    val CUSTOM_SOUNDS = SettingKey(PREFS_NAME, "user_sounds", emptySet<String>())
}

data class SoundItem(val uri: String?, val title: String)

/**
 * Get display text for a sound URI.
 */
fun getSoundDisplayText(uri: String?, alertTypeId: Int = 0): String {
    return when {
        uri.isNullOrEmpty() -> Applic.app.getString(R.string.app_default_sound)
        uri == SYSTEM_DEFAULT_SOUND -> Applic.app.getString(R.string.system_default_sound)
        else -> BundledAlertSounds.styleFor(uri, Applic.app.packageName)
            ?: Applic.app.getString(R.string.custom_sound_selected)
    }
}

/**
 * User-added custom sounds, as a set of URIs under one key (plan task T2.3).
 * The logic takes a [SettingsStore] so it is testable without Android; the
 * repository below wires it to the app's SharedPreferences.
 */
class CustomSoundStore(private val store: SettingsStore) {

    fun customSounds(): Set<String> = store.get(CustomSoundKeys.CUSTOM_SOUNDS)

    fun add(uri: String) {
        store.set(CustomSoundKeys.CUSTOM_SOUNDS, customSounds() + uri)
    }

    fun remove(uri: String) {
        store.set(CustomSoundKeys.CUSTOM_SOUNDS, customSounds() - uri)
    }
}

object CustomSoundRepository {
    private val logic by lazy {
        CustomSoundStore(
            SettingsStoreImpl(SharedPreferencesKeyValueStore(Applic.app.applicationContext))
        )
    }

    fun getCustomSounds(): Set<String> = logic.customSounds()

    fun addCustomSound(uri: String) = logic.add(uri)

    fun removeCustomSound(uri: String) = logic.remove(uri)
}

@Composable
fun SoundPicker(
    currentUri: String?,
    alertTypeId: Int = 0,
    onSoundSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var systemSounds by remember { mutableStateOf<List<SoundItem>>(emptyList()) }
    var customSounds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var isPlaying by remember { mutableStateOf(false) }
    var playingUri by remember { mutableStateOf<String?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // Preview what choosing App Default will save, not the previous native selection.
    val appDefaultUri = remember(alertTypeId, context.packageName) {
        tk.glucodata.alerts.AlertSoundDefaults.uri(context.packageName, alertTypeId)
    }

    fun stopSound() {
        // release also cancels asynchronous preparation; stop/isPlaying are invalid
        // in some MediaPlayer states, including a preview that has not prepared yet.
        val previous = mediaPlayer
        mediaPlayer = null
        previous?.release()
        isPlaying = false
        playingUri = null
    }

    DisposableEffect(Unit) {
        onDispose {
            val previous = mediaPlayer
            mediaPlayer = null
            previous?.release()
        }
    }

    // Tapping a row confirms it immediately; there is no OK button.
    fun chooseSound(uri: String?) {
        stopSound()
        onSoundSelected(uri)
    }

    // File picker launcher
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) { /* ignore */ }
                val uriString = uri.toString()
                CustomSoundRepository.addCustomSound(uriString)
                customSounds = CustomSoundRepository.getCustomSounds()
                chooseSound(uriString)
            }
        }
    }

    LaunchedEffect(alertTypeId) {
        // Load custom sounds
        customSounds = CustomSoundRepository.getCustomSounds()
        
        // Load system sounds
        val soundList = mutableListOf<SoundItem>()
        soundList.add(SoundItem(null, context.getString(R.string.app_default_sound)))
        soundList.add(SoundItem(SYSTEM_DEFAULT_SOUND, context.getString(R.string.system_default_sound)))
        BundledAlertSounds.styles.forEach { style ->
            soundList.add(SoundItem(BundledAlertSounds.uri(context.packageName, style, alertTypeId), style))
        }

        val ringtoneManager = RingtoneManager(context)
        ringtoneManager.setType(RingtoneManager.TYPE_NOTIFICATION)
        val cursor = ringtoneManager.cursor
        try {
            while (cursor != null && cursor.moveToNext()) {
                val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                val uri = ringtoneManager.getRingtoneUri(cursor.position).toString()
                soundList.add(SoundItem(uri, title))
            }
        } finally { /* cursor managed by RingtoneManager */ }
        systemSounds = soundList
    }

    fun playSound(uri: String?) {
        stopSound()
        val player = MediaPlayer()
        mediaPlayer = player
        try {
            val actualUri = when {
                uri.isNullOrEmpty() -> appDefaultUri?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
                uri == SYSTEM_DEFAULT_SOUND -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                else -> Uri.parse(uri)
            } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setDataSource(context, actualUri)
            player.setOnPreparedListener { prepared ->
                if (mediaPlayer === prepared) prepared.start()
            }
            player.setOnCompletionListener { completed ->
                if (mediaPlayer === completed) stopSound()
            }
            player.setOnErrorListener { failed, what, extra ->
                android.util.Log.w("SoundPicker", "Preview failed: $what/$extra")
                if (mediaPlayer === failed) stopSound()
                true
            }
            // Show Stop while preparing too, so a pending preview can be cancelled.
            isPlaying = true
            playingUri = uri
            player.prepareAsync()
        } catch (e: Exception) {
            android.util.Log.w("SoundPicker", "Could not preview sound", e)
            stopSound()
        }
    }

    AlertDialog(
        onDismissRequest = {
            stopSound()
            onDismiss()
        },
        title = { Text(stringResource(R.string.select_alert_sound)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                // === ADD CUSTOM SOUND (at top) ===
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable {
                                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "audio/*"
                                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                filePicker.launch(intent)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            Icons.Default.Add, 
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.add_custom_sound),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                
                // === USER CUSTOM SOUNDS ===
                if (customSounds.isNotEmpty()) {
                    items(customSounds.toList()) { uri ->
                        SoundRow(
                            title = context.getString(R.string.custom_sound_prefix, uri.substringAfterLast("/").take(25)),
                            isSelected = currentUri == uri,
                            isPlaying = isPlaying && playingUri == uri,
                            onClick = { chooseSound(uri) },
                            onPlay = { if (isPlaying && playingUri == uri) stopSound() else playSound(uri) }
                        )
                    }
                }
                
                // === SYSTEM SOUNDS (App Default, System Default, Notifications) ===
                items(systemSounds) { sound ->
                    SoundRow(
                        title = sound.title,
                        isSelected = if (sound.uri == null) currentUri.isNullOrEmpty() else currentUri == sound.uri,
                        isPlaying = isPlaying && playingUri == sound.uri,
                        onClick = { chooseSound(sound.uri) },
                        onPlay = { if (isPlaying && playingUri == sound.uri) stopSound() else playSound(sound.uri) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                stopSound()
                onDismiss()
            }) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun SoundRow(
    title: String,
    isSelected: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = isSelected, onClick = onClick)
        Text(
            text = title,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            style = MaterialTheme.typography.bodyLarge
        )
        IconButton(onClick = onPlay) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) stringResource(R.string.stop) else stringResource(R.string.preview)
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}
