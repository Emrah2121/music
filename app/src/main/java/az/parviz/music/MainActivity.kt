package az.parviz.music

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

data class Song(val id: Long, val title: String, val artist: String, val uri: Uri)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { PlayerApp() }
            }
        }
    }
}

fun loadSongs(ctx: Context): List<Song> {
    val list = mutableListOf<Song>()
    val collection = if (Build.VERSION.SDK_INT >= 29)
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST
    )
    ctx.contentResolver.query(
        collection, projection,
        "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
        "${MediaStore.Audio.Media.TITLE} ASC"
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            list += Song(
                id,
                c.getString(titleCol) ?: "Naməlum",
                c.getString(artistCol)?.takeIf { it != "<unknown>" } ?: "Naməlum ifaçı",
                Uri.withAppendedPath(collection, id.toString())
            )
        }
    }
    return list
}

@Composable
fun PlayerApp() {
    val ctx = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    var current by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    val player = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                current = player.currentMediaItemIndex
                duration = maxOf(player.duration, 0L)
            }
            override fun onPlaybackStateChanged(state: Int) {
                duration = maxOf(player.duration, 0L)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }

    LaunchedEffect(granted) {
        if (granted) {
            songs = loadSongs(ctx)
            player.setMediaItems(songs.map { MediaItem.fromUri(it.uri) })
            player.prepare()
        }
    }
    LaunchedEffect(isPlaying) {
        while (isPlaying) { position = player.currentPosition; delay(500) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Musiqi", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp))

        when {
            !granted -> Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Mahnıları göstərmək üçün icazə lazımdır")
                Spacer(Modifier.height(12.dp))
                Button(onClick = { launcher.launch(permission) }) { Text("İcazə ver") }
            }
            songs.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Text("Telefonda mahnı tapılmadı")
            }
            else -> LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(songs) { i, s ->
                    ListItem(
                        headlineContent = {
                            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (i == current) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface)
                        },
                        supportingContent = { Text(s.artist, maxLines = 1) },
                        leadingContent = { Icon(Icons.Default.MusicNote, null) },
                        modifier = Modifier.clickable {
                            player.seekTo(i, 0L); player.play(); current = i
                        }
                    )
                }
            }
        }

        if (current in songs.indices) {
            Surface(tonalElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
                    Text(songs[current].title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = if (duration > 0) position.toFloat() / duration else 0f,
                        onValueChange = { position = (it * duration).toLong() },
                        onValueChangeFinished = { player.seekTo(position) }
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { player.seekToPreviousMediaItem() }) {
                            Icon(Icons.Default.SkipPrevious, "Əvvəlki")
                        }
                        FilledIconButton(onClick = { if (isPlaying) player.pause() else player.play() }) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Oynat")
                        }
                        IconButton(onClick = { player.seekToNextMediaItem() }) {
                            Icon(Icons.Default.SkipNext, "Növbəti")
                        }
                    }
                }
            }
        }
    }
}
