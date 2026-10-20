package az.emrah.music

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import java.io.FileNotFoundException
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.animateItemPlacement
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val albumId: Long,
    val uri: Uri
)

private enum class MusicTab(val label: String) {
    Home("Ana səhifə"),
    Search("Axtar"),
    Favorites("Sevimlilər"),
    Library("Kitabxana")
}

private val Purple = Color(0xFF7856E8)
private val Coral = Color(0xFFFF8B74)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionToken = SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        setContent {
            var player by remember { mutableStateOf<MediaController?>(null) }
            var connectionError by remember { mutableStateOf<String?>(null) }
            DisposableEffect(sessionToken) {
                val future = MediaController.Builder(this@MainActivity, sessionToken).buildAsync()
                future.addListener(
                    {
                        try {
                            player = future.get()
                        } catch (error: Exception) {
                            connectionError = error.message ?: "Pleyerə qoşulmaq mümkün olmadı"
                        }
                    },
                    ContextCompat.getMainExecutor(this@MainActivity)
                )
                onDispose { MediaController.releaseFuture(future) }
            }
            when {
                player != null -> player?.let { PlayerApp(it) }
                connectionError != null -> MaterialTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Pleyer açıla bilmədi: $connectionError")
                    }
                }
                else -> MaterialTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

private fun loadSongs(ctx: Context): List<Song> {
    val songs = mutableListOf<Song>()
    val collection = if (Build.VERSION.SDK_INT >= 29) {
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM_ID
    )
    ctx.contentResolver.query(
        collection,
        projection,
        "${MediaStore.Audio.Media.IS_MUSIC} != 0",
        null,
        "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            songs += Song(
                id = id,
                title = cursor.getString(titleColumn)?.takeIf(String::isNotBlank) ?: "Naməlum mahnı",
                artist = cursor.getString(artistColumn)
                    ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Naməlum ifaçı",
                albumId = cursor.getLong(albumIdColumn),
                uri = Uri.withAppendedPath(collection, id.toString())
            )
        }
    }
    return songs
}

@Composable
private fun PlayerApp(player: Player) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember { context.getSharedPreferences("music_preferences", Context.MODE_PRIVATE) }
    var isDark by remember { mutableStateOf(preferences.getBoolean("dark_mode", true)) }
    val colors = if (isDark) {
        darkColorScheme(
            primary = Color(0xFFB9A4FF),
            secondary = Coral,
            background = Color(0xFF101016),
            surface = Color(0xFF191820),
            surfaceVariant = Color(0xFF25232D)
        )
    } else {
        lightColorScheme(
            primary = Purple,
            secondary = Color(0xFFD65F4C),
            background = Color(0xFFF8F7FC),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFF0EDF7)
        )
    }

    MaterialTheme(colorScheme = colors) {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        var hasPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            )
        }
        var songs by remember { mutableStateOf(emptyList<Song>()) }
        var selectedTab by remember { mutableStateOf(MusicTab.Home) }
        var searchQuery by remember { mutableStateOf("") }
        var debouncedQuery by remember { mutableStateOf("") }
        var currentIndex by remember { mutableIntStateOf(-1) }
        var songTransitionDirection by remember { mutableIntStateOf(1) }
        var isPlaying by remember { mutableStateOf(false) }
        var position by remember { mutableLongStateOf(0L) }
        var duration by remember { mutableLongStateOf(0L) }
        var isPlayerOpen by remember { mutableStateOf(false) }
        var isShuffleEnabled by remember { mutableStateOf(player.shuffleModeEnabled) }
        var repeatMode by remember { mutableIntStateOf(player.repeatMode) }
        var favoriteIds by remember {
            mutableStateOf(
                preferences.getStringSet("favorites", emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet()
            )
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { hasPermission = it }

        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    val nextIndex = player.currentMediaItemIndex
                    if (currentIndex >= 0 && nextIndex != currentIndex) {
                        songTransitionDirection = if (nextIndex > currentIndex) 1 else -1
                    }
                    currentIndex = nextIndex
                    duration = player.duration.coerceAtLeast(0L)
                    position = 0L
                }

                override fun onPlaybackStateChanged(state: Int) {
                    duration = player.duration.coerceAtLeast(0L)
                }
            }
            currentIndex = player.currentMediaItemIndex
            isPlaying = player.isPlaying
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            player.addListener(listener)
            onDispose { player.removeListener(listener) }
        }

        LaunchedEffect(hasPermission) {
            if (hasPermission) {
                songs = loadSongs(context)
                val mediaItems = songs.map(::toMediaItem)
                if (player.mediaItemCount != mediaItems.size ||
                    (mediaItems.isNotEmpty() && player.currentMediaItem?.mediaId != mediaItems.getOrNull(player.currentMediaItemIndex)?.mediaId)
                ) {
                    player.setMediaItems(mediaItems)
                    player.prepare()
                }
                currentIndex = player.currentMediaItemIndex
                isPlaying = player.isPlaying
                position = player.currentPosition.coerceAtLeast(0L)
                duration = player.duration.coerceAtLeast(0L)
            }
        }

        LaunchedEffect(searchQuery) {
            delay(400)
            debouncedQuery = searchQuery
        }

        LaunchedEffect(isPlaying) {
            while (isPlaying) {
                position = player.currentPosition.coerceAtLeast(0L)
                delay(500)
            }
        }

        val currentSong = songs.getOrNull(currentIndex)
        val favoriteSongs = songs.filter { it.id in favoriteIds }
        val toggleFavorite: (Song) -> Unit = { song ->
            favoriteIds = favoriteIds.toggle(song.id)
            preferences.edit().putStringSet("favorites", favoriteIds.map(Long::toString).toSet()).apply()
        }
        val visibleSongs = when (selectedTab) {
            MusicTab.Favorites -> favoriteSongs
            MusicTab.Search -> songs.filter {
                it.title.contains(debouncedQuery, ignoreCase = true) ||
                    it.artist.contains(debouncedQuery, ignoreCase = true)
            }
            else -> songs
        }

        BackHandler(enabled = isPlayerOpen && currentSong != null) {
            isPlayerOpen = false
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = .10f),
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
            ) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                AnimatedContent(
                    targetState = isPlayerOpen && currentSong != null,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        (slideInHorizontally { it / 5 } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 5 } + fadeOut()) using
                            SizeTransform(clip = false)
                    },
                    label = "player screen transition"
                ) { showFullPlayer ->
                    if (showFullPlayer && currentSong != null) {
                        AnimatedContent(
                            targetState = currentSong,
                            contentKey = { it.id },
                            transitionSpec = {
                                if (songTransitionDirection > 0) {
                                    (slideInHorizontally { it } + fadeIn()) togetherWith
                                        (slideOutHorizontally { -it } + fadeOut())
                                } else {
                                    (slideInHorizontally { -it } + fadeIn()) togetherWith
                                        (slideOutHorizontally { it } + fadeOut())
                                }
                            },
                            label = "track slide transition"
                        ) { displayedSong ->
                            FullPlayer(
                                song = displayedSong,
                                isPlaying = isPlaying,
                                isFavorite = displayedSong.id in favoriteIds,
                                position = position,
                                duration = duration,
                                shuffleEnabled = isShuffleEnabled,
                                repeatMode = repeatMode,
                                onBack = { isPlayerOpen = false },
                                onTogglePlay = { if (isPlaying) player.pause() else player.play() },
                                onPrevious = { player.seekToPreviousMediaItem() },
                                onNext = { player.seekToNextMediaItem() },
                                onSeek = { position = it; player.seekTo(it) },
                                onToggleFavorite = { toggleFavorite(displayedSong) },
                                onToggleShuffle = {
                                    isShuffleEnabled = !isShuffleEnabled
                                    player.shuffleModeEnabled = isShuffleEnabled
                                },
                                onToggleRepeat = {
                                    repeatMode = when (repeatMode) {
                                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                        else -> Player.REPEAT_MODE_OFF
                                    }
                                    player.repeatMode = repeatMode
                                },
                                onPreviousFromSwipe = { player.seekToPreviousMediaItem() },
                                onNextFromSwipe = { player.seekToNextMediaItem() }
                            )
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            if (selectedTab == MusicTab.Search) {
                                SearchHeader(
                                    query = searchQuery,
                                    onQueryChange = { searchQuery = it },
                                    isDark = isDark,
                                    onToggleTheme = {
                                        isDark = !isDark
                                        preferences.edit().putBoolean("dark_mode", isDark).apply()
                                    }
                                )
                            } else {
                                HomeHeader(
                                    tab = selectedTab,
                                    isDark = isDark,
                                    onToggleTheme = {
                                        isDark = !isDark
                                        preferences.edit().putBoolean("dark_mode", isDark).apply()
                                    }
                                )
                            }

                            if (!hasPermission) {
                                PermissionPrompt(
                                    onRequestPermission = { permissionLauncher.launch(permission) },
                                    modifier = Modifier.weight(1f)
                                )
                            } else if (songs.isEmpty()) {
                                EmptyLibrary(modifier = Modifier.weight(1f))
                            } else {
                                AnimatedContent(
                                    targetState = selectedTab,
                                    modifier = Modifier.weight(1f),
                                    transitionSpec = {
                                        (fadeIn() + slideInHorizontally { it / 12 }) togetherWith
                                            (fadeOut() + slideOutHorizontally { -it / 12 })
                                    },
                                    label = "tab transition"
                                ) { tab ->
                                    when (tab) {
                                        MusicTab.Home -> HomeContent(
                                            songs = songs,
                                            currentIndex = currentIndex,
                                            isPlaying = isPlaying,
                                            favoriteIds = favoriteIds,
                                            onToggleFavorite = toggleFavorite,
                                            onPlay = { playSong(player, it, songs.size); isPlayerOpen = true }
                                        )
                                        MusicTab.Search -> SongList(
                                            songs = visibleSongs,
                                            currentSongId = currentSong?.id,
                                            isPlaying = isPlaying,
                                            favoriteIds = favoriteIds,
                                            onToggleFavorite = toggleFavorite,
                                            emptyMessage = "Axtarışa uyğun mahnı tapılmadı",
                                            onPlay = { playSong(player, it, songs); isPlayerOpen = true }
                                        )
                                        MusicTab.Favorites -> SongList(
                                            songs = visibleSongs,
                                            currentSongId = currentSong?.id,
                                            isPlaying = isPlaying,
                                            favoriteIds = favoriteIds,
                                            onToggleFavorite = toggleFavorite,
                                            emptyMessage = "Sevimlilərə mahnı əlavə etməmisən",
                                            onPlay = { playSong(player, it, songs); isPlayerOpen = true }
                                        )
                                        MusicTab.Library -> LibraryContent(
                                            songs = songs,
                                            currentIndex = currentIndex,
                                            isPlaying = isPlaying,
                                            favoriteIds = favoriteIds,
                                            onToggleFavorite = toggleFavorite,
                                            onPlay = { playSong(player, it, songs.size); isPlayerOpen = true }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (!isPlayerOpen && currentSong != null) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        MiniPlayer(
                            song = currentSong,
                            isPlaying = isPlaying,
                            onOpen = { isPlayerOpen = true },
                            onTogglePlay = { if (isPlaying) player.pause() else player.play() },
                            onNext = { player.seekToNextMediaItem() }
                        )
                    }
                }
                if (!isPlayerOpen) {
                    LiquidTabBar(selectedTab = selectedTab, onSelect = { selectedTab = it })
                }
            }
            }
        }
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> =
    if (id in this) this - id else this + id

private fun toMediaItem(song: Song): MediaItem =
    MediaItem.Builder()
        .setMediaId(song.id.toString())
        .setUri(song.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setArtworkUri(
                    if (song.albumId > 0) Uri.parse("content://media/external/audio/albumart/${song.albumId}")
                    else null
                )
                .build()
        )
        .build()

private fun playSong(player: Player, song: Song, songs: List<Song>) {
    val index = songs.indexOfFirst { it.id == song.id }
    if (index >= 0) {
        if (player.currentMediaItemIndex != index || player.currentMediaItem?.mediaId != song.id.toString()) {
            player.seekTo(index, 0L)
        }
        player.play()
    }
}

private fun playSong(player: Player, index: Int, songCount: Int) {
    if (index in 0 until songCount) {
        player.seekTo(index, 0L)
        player.play()
    }
}

@Composable
private fun HomeHeader(
    tab: MusicTab,
    isDark: Boolean,
    onToggleTheme: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "MELODIA",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when (tab) {
                    MusicTab.Home -> "Musiqi səninlə"
                    MusicTab.Favorites -> "Ən sevdiyin mahnılar"
                    MusicTab.Library -> "Bütün musiqilərin bir yerdə"
                    MusicTab.Search -> tab.label
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = .72f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .10f))
        ) {
            IconButton(onClick = onToggleTheme, modifier = Modifier.size(44.dp)) {
                Icon(
                    if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = if (isDark) "Açıq rejimə keç" else "Qaranlıq rejimə keç",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun LiquidTabBar(selectedTab: MusicTab, onSelect: (MusicTab) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .68f),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = .24f),
                    MaterialTheme.colorScheme.primary.copy(alpha = .28f),
                    MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)
                )
            )
        ),
        shadowElevation = 12.dp,
        tonalElevation = 4.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MusicTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                val tint by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = spring(stiffness = 500f),
                    label = "tab tint"
                )
                val scale by animateFloatAsState(
                    targetValue = if (selected) 1.08f else 1f,
                    animationSpec = spring(stiffness = 500f),
                    label = "tab icon scale"
                )
                val selectedColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .15f)
                    else Color.Transparent,
                    animationSpec = spring(stiffness = 500f),
                    label = "tab glass highlight"
                )
                val icon = when (tab) {
                    MusicTab.Home -> Icons.Default.Home
                    MusicTab.Search -> Icons.Default.Search
                    MusicTab.Favorites -> Icons.Default.Favorite
                    MusicTab.Library -> Icons.Default.LibraryMusic
                }
                Column(
                    Modifier.weight(1f)
                        .height(56.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.verticalGradient(
                                listOf(selectedColor, selectedColor.copy(alpha = selectedColor.alpha * .55f))
                            )
                        )
                        .clickable { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = tab.label,
                        tint = tint,
                        modifier = Modifier.size(21.dp * scale)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        tab.label,
                        color = tint,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    isDark: Boolean,
    onToggleTheme: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Kəşf et",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = .72f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .10f))
            ) {
                IconButton(onClick = onToggleTheme, modifier = Modifier.size(44.dp)) {
                    Icon(
                        if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = if (isDark) "Açıq rejimə keç" else "Qaranlıq rejimə keç",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Mahnı və ya ifaçı axtar") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Axtarışı təmizlə")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = .62f),
                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = .82f)
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() })
        )
    }
}

@Composable
private fun HomeContent(
    songs: List<Song>,
    currentIndex: Int,
    isPlaying: Boolean,
    favoriteIds: Set<Long>,
    onToggleFavorite: (Song) -> Unit,
    onPlay: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 18.dp)
    ) {
        item {
            FeaturedCard(
                title = "Musiqini hiss et",
                subtitle = "Sevdiyin ritmlər, hər an səninlə",
                onClick = { if (songs.isNotEmpty()) onPlay(0) }
            )
        }
        item {
            SectionHeading(
                title = "Sənin musiqilərin",
                subtitle = "${songs.size} mahnı"
            )
        }
        items(songs.take(5), key = { it.id }) { song ->
            SongRow(
                modifier = Modifier.animateItemPlacement(animationSpec = spring()),
                song = song,
                isPlaying = isPlaying && songs.getOrNull(currentIndex)?.id == song.id,
                onClick = { onPlay(songs.indexOf(song)) },
                isFavorite = song.id in favoriteIds,
                onToggleFavorite = { onToggleFavorite(song) },
                onPlay = { onPlay(songs.indexOf(song)) }
            )
        }
        if (songs.size > 5) {
            item {
                Text(
                    "Bütün mahnıları Kitabxana bölməsində görə bilərsən.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun FeaturedCard(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Purple, Color(0xFFB252B2), Coral)))
            .border(1.dp, Color.White.copy(alpha = .28f), RoundedCornerShape(26.dp))
            .clickable(onClick = onClick)
            .padding(start = 20.dp, top = 20.dp, bottom = 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("SƏNİN ÜÇÜN", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .8f))
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .88f))
            Spacer(Modifier.height(16.dp))
            Surface(color = Color.White, shape = RoundedCornerShape(30.dp)) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = Purple, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("İndi dinlə", color = Purple, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Icon(
            Icons.Default.GraphicEq,
            contentDescription = null,
            tint = Color.White.copy(alpha = .88f),
            modifier = Modifier.size(72.dp).padding(start = 10.dp)
        )
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun SongList(
    songs: List<Song>,
    currentSongId: Long?,
    isPlaying: Boolean,
    favoriteIds: Set<Long>,
    onToggleFavorite: (Song) -> Unit,
    emptyMessage: String,
    onPlay: (Song) -> Unit
) {
    if (songs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.MusicOff, null, modifier = Modifier.size(46.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 18.dp)
        ) {
            item { SectionHeading("${songs.size} mahnı") }
            items(songs, key = { it.id }) { song ->
                SongRow(
                    modifier = Modifier.animateItemPlacement(animationSpec = spring()),
                    song = song,
                    isPlaying = isPlaying && song.id == currentSongId,
                    onClick = { onPlay(song) },
                    isFavorite = song.id in favoriteIds,
                    onToggleFavorite = { onToggleFavorite(song) },
                    onPlay = { onPlay(song) }
                )
            }
        }
    }
}

@Composable
private fun LibraryContent(
    songs: List<Song>,
    currentIndex: Int,
    isPlaying: Boolean,
    favoriteIds: Set<Long>,
    onToggleFavorite: (Song) -> Unit,
    onPlay: (Int) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 18.dp)) {
        item {
            SectionHeading("Bütün mahnılar", "${songs.size} mahnı")
        }
        items(songs, key = { it.id }) { song ->
            SongRow(
                modifier = Modifier.animateItemPlacement(animationSpec = spring()),
                song = song,
                isPlaying = isPlaying && songs.getOrNull(currentIndex)?.id == song.id,
                onClick = { onPlay(songs.indexOf(song)) },
                isFavorite = song.id in favoriteIds,
                onToggleFavorite = { onToggleFavorite(song) },
                onPlay = { onPlay(songs.indexOf(song)) }
            )
        }
    }
}

@Composable
private fun SongRow(
    modifier: Modifier = Modifier,
    song: Song,
    isPlaying: Boolean,
    onClick: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(180)) +
            slideInVertically(animationSpec = tween(180)) { it / 8 }
    ) {
        Row(
            modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlbumTile(song = song, modifier = Modifier.size(52.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    song.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SongActionsMenu(
                songTitle = song.title,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                onPlay = onPlay
            )
        }
    }
}

@Composable
private fun SongActionsMenu(
    songTitle: String,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "$songTitle üçün əməliyyatlar",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.clip(RoundedCornerShape(18.dp))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .12f), RoundedCornerShape(18.dp))
        ) {
            DropdownMenuItem(
                text = { Text(if (isFavorite) "Sevimlilərdən sil" else "Sevimlilərə əlavə et") },
                leadingIcon = {
                    Icon(
                        if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null
                    )
                },
                onClick = {
                    expanded = false
                    onToggleFavorite()
                }
            )
            DropdownMenuItem(
                text = { Text("İndi oxut") },
                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                onClick = {
                    expanded = false
                    onPlay()
                }
            )
        }
    }
}

@Composable
private fun AlbumTile(song: Song, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val palettes = listOf(
        listOf(Color(0xFF7356D8), Color(0xFFB779E5)),
        listOf(Color(0xFFDF6B66), Color(0xFFF2AA70)),
        listOf(Color(0xFF318F91), Color(0xFF69C7A9)),
        listOf(Color(0xFF4058A7), Color(0xFF7D94E4)),
        listOf(Color(0xFFB44F84), Color(0xFFE795AE))
    )
    val palette = palettes[(song.id % palettes.size).toInt()]
    val artwork by produceState<ImageBitmap?>(initialValue = null, song.albumId) {
        value = if (song.albumId > 0L) {
            withContext(Dispatchers.IO) {
                try {
                    val artworkUri = Uri.parse("content://media/external/audio/albumart/${song.albumId}")
                    context.contentResolver.openInputStream(artworkUri)?.use {
                        BitmapFactory.decodeStream(it)?.asImageBitmap()
                    }
                } catch (_: FileNotFoundException) {
                    null
                }
            }
        } else {
            null
        }
    }
    Box(
        modifier.clip(RoundedCornerShape(15.dp))
            .background(Brush.linearGradient(palette)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = .92f), modifier = Modifier.size(25.dp))
        artwork?.let {
            Image(
                bitmap = it,
                contentDescription = "${song.title} albom üzlüyü",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .78f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)),
        tonalElevation = 2.dp
    ) {
        Row(
            Modifier.clickable(onClick = onOpen).padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlbumTile(song, Modifier.size(42.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onTogglePlay) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Oynat və ya dayandır")
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Default.SkipNext, "Növbəti mahnı")
            }
        }
    }
}

@Composable
private fun FullPlayer(
    song: Song,
    isPlaying: Boolean,
    isFavorite: Boolean,
    position: Long,
    duration: Long,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onPreviousFromSwipe: () -> Unit,
    onNextFromSwipe: () -> Unit
) {
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 72.dp.toPx() }
    Column(
        Modifier.fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 22.dp)
            .pointerInput(onBack, swipeThreshold) {
                var dragDistance = 0f
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount -> dragDistance += dragAmount },
                    onDragEnd = {
                        if (dragDistance > swipeThreshold) onBack()
                        dragDistance = 0f
                    },
                    onDragCancel = { dragDistance = 0f }
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "Pleyeri bağla") }
            Text("İndi oxudulur", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }
        Spacer(Modifier.weight(.3f))
        AlbumTile(
            song,
            Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 12.dp)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .12f), RoundedCornerShape(19.dp))
                .pointerInput(song.id, swipeThreshold) {
                    var dragDistance = 0f
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dragAmount -> dragDistance += dragAmount },
                        onDragEnd = {
                            when {
                                dragDistance < -swipeThreshold -> onPreviousFromSwipe()
                                dragDistance > swipeThreshold -> onNextFromSwipe()
                            }
                            dragDistance = 0f
                        },
                        onDragCancel = { dragDistance = 0f }
                    )
                }
        )
        Spacer(Modifier.height(30.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(song.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isFavorite) "Sevimlilərdən çıxar" else "Sevimlilərə əlavə et",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Slider(
            value = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { onSeek((it * duration).toLong()) },
            enabled = duration > 0L
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleShuffle) {
                Icon(Icons.Default.Shuffle, "Qarışıq oxutma", tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Əvvəlki mahnı", modifier = Modifier.size(34.dp)) }
            FilledIconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(68.dp),
                shape = CircleShape
            ) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Oynat və ya dayandır", modifier = Modifier.size(34.dp))
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Növbəti mahnı", modifier = Modifier.size(34.dp)) }
            IconButton(onClick = onToggleRepeat) {
                Icon(
                    if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    "Təkrar rejimi",
                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.weight(.7f))
    }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun PermissionPrompt(onRequestPermission: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.LibraryMusic, null, modifier = Modifier.size(58.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Musiqilərinə qulaq as", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Telefonundakı mahnıları göstərmək üçün media icazəsi lazımdır.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = onRequestPermission) { Text("İcazə ver") }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.MusicOff, null, modifier = Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(14.dp))
        Text("Hələ mahnı yoxdur", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Musiqi fayllarını cihazına əlavə etdikdən sonra burada görünəcək.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
