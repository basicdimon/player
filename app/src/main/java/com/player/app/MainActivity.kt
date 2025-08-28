package com.player.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.TimeUnit

// Класс для хранения информации о треке
data class Track(
    val uri: Uri,
    val name: String,
    val mediaItem: MediaItem
)

class MainActivity : AppCompatActivity() {
    
    private var exoPlayer: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var updateProgressAction: Runnable? = null
    
    private lateinit var fileNameText: TextView
    private lateinit var selectFileButton: Button
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var trackListView: ListView
    // private lateinit var seekBar: SeekBar // Removed with equalizer
    private lateinit var previousButton: ImageButton
    private lateinit var playPauseButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var volumeSeekBar: SeekBar
    private lateinit var audioManager: AudioManager
    
    // Список треков в библиотеке
    private val trackLibrary = mutableListOf<Track>()
    private var currentTrackIndex = -1
    private lateinit var trackAdapter: ArrayAdapter<String>
    
    // Лаунчер для выбора одного файла
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            addTrackToLibrary(it)
        }
    }
    
    // Лаунчер для выбора нескольких файлов
    private val multipleFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                addTrackToLibrary(uri)
            }
            Toast.makeText(this, "Добавлено треков: ${uris.size}", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Лаунчер для запроса разрешений (одиночный файл)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            openFilePicker()
        } else {
            Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show()
        }
    }
    
    // Лаунчер для разрешений (множественный выбор)
    private val permissionMultipleLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            openMultipleFilePicker()
        } else {
            Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable fullscreen mode
        enableFullscreen()
        
        setContentView(R.layout.activity_main)
        
        initializeViews()
        initializePlayer()
        setupUI()
        handleIntent(intent)
    }
    
    private fun enableFullscreen() {
        // Hide system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }
    
    private fun initializeViews() {
        fileNameText = findViewById(R.id.tvFileName)
        selectFileButton = findViewById(R.id.btnEQ) // Temporary using EQ button
        currentTimeText = findViewById(R.id.tvTrackNumber) // Temporary using track number
        totalTimeText = findViewById(R.id.tvTrackNumber) // Using same element for now
        trackListView = findViewById(R.id.trackListView)
        volumeSeekBar = findViewById(R.id.seekVolume)
        previousButton = findViewById(R.id.btnPrevious)
        playPauseButton = findViewById(R.id.btnPlayPause)
        nextButton = findViewById(R.id.btnNext)
        
        // Инициализация AudioManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Инициализация адаптера для списка треков
        trackAdapter = ArrayAdapter(this, R.layout.track_list_item, mutableListOf<String>())
        trackListView.adapter = trackAdapter
    }
    
    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        
        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlayPauseButton()
            }
            
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseButton()
                if (isPlaying) {
                    startProgressUpdate()
                }
            }
        })
    }
    
    private fun setupUI() {
        selectFileButton.setOnClickListener {
            showFileSelectionDialog()
        }
        
        playPauseButton.setOnClickListener {
            togglePlayPause()
        }
        
        previousButton.setOnClickListener {
            playPreviousTrack()
        }
        
        nextButton.setOnClickListener {
            playNextTrack()
        }
        
        // Обработчик кликов по элементам списка треков
        trackListView.setOnItemClickListener { _, _, position, _ ->
            loadTrack(position)
        }
        
        // Обработчик изменения громкости
        setupVolumeControl()
    }
    
    private fun setupVolumeControl() {
        // Получаем максимальную громкость системы
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        
        // Устанавливаем максимальное значение SeekBar равным максимальной громкости системы
        volumeSeekBar.max = maxVolume
        volumeSeekBar.progress = currentVolume
        
        volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Устанавливаем системную громкость
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                    // Также устанавливаем громкость плеера для двойного эффекта
                    val playerVolume = progress.toFloat() / maxVolume.toFloat()
                    exoPlayer?.volume = playerVolume
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    // Диалог выбора типа загрузки файлов
    private fun showFileSelectionDialog() {
        val options = arrayOf("Выбрать один файл", "Выбрать несколько файлов")
        AlertDialog.Builder(this)
            .setTitle("Выберите тип загрузки")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> checkPermissionsAndOpenFile()
                    1 -> checkPermissionsAndOpenMultipleFiles()
                }
            }
            .show()
    }
    
    // Проверка разрешений для множественного выбора файлов
    private fun checkPermissionsAndOpenMultipleFiles() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            openMultipleFilePicker()
        } else {
            if (permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }) {
                showPermissionDeniedDialog()
            } else {
                permissionMultipleLauncher.launch(permissions)
            }
        }
    }
    
    // Открытие множественного выбора файлов
    private fun openMultipleFilePicker() {
        multipleFilePickerLauncher.launch("audio/*")
    }
    
    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                addTrackToLibrary(uri)
            }
        }
    }
    
    private fun checkPermissionsAndOpenFile() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isEmpty()) {
            openFilePicker()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
    
    private fun openFilePicker() {
        filePickerLauncher.launch("*/*")
    }
    

    
    private fun togglePlayPause() {
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
            updatePlayPauseButton()
        }
    }
    
    private fun updatePlayPauseButton() {
        val isPlaying = exoPlayer?.isPlaying ?: false
        playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
    }
    
    private fun seekBackward() {
        exoPlayer?.let { player ->
            val newPosition = (player.currentPosition - 10000).coerceAtLeast(0)
            player.seekTo(newPosition)
        }
    }
    
    private fun seekForward() {
        exoPlayer?.let { player ->
            val newPosition = (player.currentPosition + 10000).coerceAtMost(player.duration)
            player.seekTo(newPosition)
        }
    }
    
    private fun seekTo(offsetMs: Long) {
        exoPlayer?.let { player ->
            val newPosition = (player.currentPosition + offsetMs).coerceAtLeast(0)
            player.seekTo(newPosition)
        }
    }
    
    private fun startProgressUpdate() {
        updateProgressAction = object : Runnable {
            override fun run() {
                exoPlayer?.let { player ->
                    if (player.duration > 0) {
                        val currentPosition = player.currentPosition
                        val duration = player.duration
                        
                        // SeekBar updates removed with equalizer
                        
                        currentTimeText.text = formatTime(currentPosition)
                        totalTimeText.text = formatTime(duration)
                        
                        handler.postDelayed(this, 1000)
                    }
                }
            }
        }
        handler.post(updateProgressAction!!)
    }
    
    // updateSeekBarMax function removed with equalizer
    
    private fun formatTime(timeMs: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(timeMs)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(timeMs) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
    
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Разрешение требуется")
            .setMessage(getString(R.string.permission_required))
            .setPositiveButton(getString(R.string.grant_permission)) { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.fromParts("package", packageName, null)
        startActivity(intent)
    }
    
    // Добавление трека в библиотеку
    private fun addTrackToLibrary(uri: Uri) {
        val fileName = getFileName(uri) ?: "Неизвестный файл"
        val mediaItem = MediaItem.fromUri(uri)
        val track = Track(uri, fileName, mediaItem)
        
        trackLibrary.add(track)
        updateTrackList()
        
        // Если это первый трек, загружаем его
        if (trackLibrary.size == 1) {
            currentTrackIndex = 0
            loadTrack(0)
        }
    }
    
    // Обновление списка треков в UI
    private fun updateTrackList() {
        val trackNames = trackLibrary.map { it.name }
        trackAdapter.clear()
        trackAdapter.addAll(trackNames)
        trackAdapter.notifyDataSetChanged()
    }
    
    // Загрузка конкретного трека
    private fun loadTrack(index: Int) {
        if (index >= 0 && index < trackLibrary.size) {
            currentTrackIndex = index
            val track = trackLibrary[index]
            
            exoPlayer?.setMediaItem(track.mediaItem)
            exoPlayer?.prepare()
            
            fileNameText.text = track.name
            
            // Выделяем текущий трек в списке
            trackListView.setSelection(index)
        }
    }
    
    // Переключение на следующий трек
    private fun playNextTrack() {
        if (trackLibrary.isNotEmpty()) {
            val nextIndex = (currentTrackIndex + 1) % trackLibrary.size
            loadTrack(nextIndex)
            exoPlayer?.play()
        }
    }
    
    // Переключение на предыдущий трек
    private fun playPreviousTrack() {
        if (trackLibrary.isNotEmpty()) {
            val prevIndex = if (currentTrackIndex > 0) currentTrackIndex - 1 else trackLibrary.size - 1
            loadTrack(prevIndex)
            exoPlayer?.play()
        }
    }
    
    // Получение имени файла из URI
    private fun getFileName(uri: Uri): String? {
        var fileName: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                fileName = cursor.getString(nameIndex)
            }
        }
        return fileName ?: uri.lastPathSegment
    }
    
    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
    }
    
    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }
}