package com.player.app

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
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
    private lateinit var seekBarProgress: SeekBar
    private lateinit var previousButton: ImageButton
    private lateinit var playPauseButton: ImageButton
    private lateinit var stopButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var rewindButton: Button
    private lateinit var forwardButton: Button
    private lateinit var shuffleButton: Button
    private lateinit var volumeSeekBar: SeekBar
    private lateinit var audioManager: AudioManager
    private lateinit var bitrateText: TextView
    private lateinit var frequencyText: TextView
    private lateinit var channelsText: TextView
    
    // Список треков в библиотеке
    private val trackLibrary = mutableListOf<Track>()
    private var currentTrackIndex = -1
    
    // Режим случайного воспроизведения
    private var isShuffleEnabled = false
    private val shuffledIndices = mutableListOf<Int>()
    private lateinit var trackAdapter: ArrayAdapter<String>
    
    // Режимы повтора
    private enum class RepeatMode {
        OFF,        // Без повтора
        ONE,        // Повтор одного трека
        ALL         // Повтор всего плейлиста
    }
    
    private var currentRepeatMode = RepeatMode.OFF
    private lateinit var repeatButton: Button
    
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
        currentTimeText = findViewById(R.id.tvCurrentTime)
        totalTimeText = findViewById(R.id.tvTotalTime)
        trackListView = findViewById(R.id.trackListView)
        seekBarProgress = findViewById(R.id.seekBarProgress)
        volumeSeekBar = findViewById(R.id.seekVolume)
        bitrateText = findViewById(R.id.tvBitrate)
        frequencyText = findViewById(R.id.tvFrequency)
        channelsText = findViewById(R.id.tvChannels)
        previousButton = findViewById(R.id.btnPrevious)
        playPauseButton = findViewById(R.id.btnPlayPause)
        stopButton = findViewById(R.id.btnStop)
        nextButton = findViewById(R.id.btnNext)
        rewindButton = findViewById(R.id.btnRewind)
        forwardButton = findViewById(R.id.btnForward)
        shuffleButton = findViewById(R.id.btnShuffle)
        repeatButton = findViewById(R.id.btnRepeat)
        
        // Инициализация AudioManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Инициализация адаптера для списка треков
        trackAdapter = ArrayAdapter(this, R.layout.track_list_item, mutableListOf<String>())
        trackListView.adapter = trackAdapter
        
        // Инициализация состояния кнопок
        updateRepeatButton()
        updateShuffleButton()
    }
    
    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        
        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlayPauseButton()
                
                // Обновляем информацию о треке когда плеер готов
                if (playbackState == Player.STATE_READY) {
                    updateTrackInfo()
                }
                
                // Обработка окончания трека
                if (playbackState == Player.STATE_ENDED) {
                    handleTrackEnded()
                }
            }
            
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseButton()
                if (isPlaying) {
                    startProgressUpdate()
                    updateTrackInfo()
                    animateTimeDisplay()
                }
            }
        })
    }
    
    private fun setupUI() {
        selectFileButton.setOnClickListener {
            showFileSelectionDialog()
        }
        
        playPauseButton.setOnClickListener {
            animateButtonPress(it)
            togglePlayPause()
        }
        
        previousButton.setOnClickListener {
            animateButtonPress(it)
            playPreviousTrack()
        }
        
        nextButton.setOnClickListener {
            animateButtonPress(it)
            playNextTrack()
        }
        
        stopButton.setOnClickListener {
            animateButtonPress(it)
            stopPlayback()
        }
        
        rewindButton.setOnClickListener {
            animateButtonPress(it)
            seekBackward()
        }
        
        forwardButton.setOnClickListener {
            animateButtonPress(it)
            seekForward()
        }
        
        shuffleButton.setOnClickListener {
            animateButtonPress(it)
            toggleShuffleMode()
        }
        
        repeatButton.setOnClickListener {
            animateButtonPress(it)
            toggleRepeatMode()
        }
        
        // Обработчик кликов по элементам списка треков
        trackListView.setOnItemClickListener { _, _, position, _ ->
            loadTrack(position)
        }
        
        // Обработчик навигации по треку
        seekBarProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    exoPlayer?.let { player ->
                        val duration = player.duration
                        if (duration > 0) {
                            val newPosition = (duration * progress / 100).toLong()
                            player.seekTo(newPosition)
                        }
                    }
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
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
    
    private fun stopPlayback() {
        exoPlayer?.let { player ->
            player.stop()
            player.seekTo(0)
            updatePlayPauseButton()
            // Остановить обновление прогресса
            updateProgressAction?.let { handler.removeCallbacks(it) }
            // Сбросить отображение времени
            currentTimeText.text = "00:00"
            seekBarProgress.progress = 0
        }
    }
    
    private fun seekBackward() {
        exoPlayer?.let { player ->
            val newPosition = (player.currentPosition - 10000).coerceAtLeast(0)
            player.seekTo(newPosition)
            updatePlayPauseButton()
        }
    }
    
    private fun seekForward() {
        exoPlayer?.let { player ->
            val newPosition = (player.currentPosition + 10000).coerceAtMost(player.duration)
            player.seekTo(newPosition)
            updatePlayPauseButton()
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
                        
                        // Обновление SeekBar прогресса
                        val progress = ((currentPosition * 100) / duration).toInt()
                        seekBarProgress.progress = progress
                        
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
            // Дополнительно обновляем информацию о треке
            handler.postDelayed({ updateTrackInfo() }, 500)
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
            
            // Обновляем информацию о качестве звука
            updateTrackInfo()
            
            // Выделяем текущий трек в списке
            trackListView.setSelection(index)
        }
    }
    
    // Переключение на следующий трек
    private fun playNextTrack() {
        if (trackLibrary.isNotEmpty()) {
            val nextIndex = if (isShuffleEnabled && shuffledIndices.isNotEmpty()) {
                // В режиме shuffle используем перемешанный список
                val currentShuffleIndex = shuffledIndices.indexOf(currentTrackIndex)
                if (currentShuffleIndex != -1 && currentShuffleIndex < shuffledIndices.size - 1) {
                    shuffledIndices[currentShuffleIndex + 1]
                } else {
                    shuffledIndices[0] // Возвращаемся к началу перемешанного списка
                }
            } else {
                // Обычный режим
                (currentTrackIndex + 1) % trackLibrary.size
            }
            loadTrack(nextIndex)
            exoPlayer?.play()
        }
    }
    
    // Переключение на предыдущий трек
    private fun playPreviousTrack() {
        if (trackLibrary.isNotEmpty()) {
            val prevIndex = if (isShuffleEnabled && shuffledIndices.isNotEmpty()) {
                // В режиме shuffle используем перемешанный список
                val currentShuffleIndex = shuffledIndices.indexOf(currentTrackIndex)
                if (currentShuffleIndex != -1 && currentShuffleIndex > 0) {
                    shuffledIndices[currentShuffleIndex - 1]
                } else {
                    shuffledIndices[shuffledIndices.size - 1] // Переходим к концу перемешанного списка
                }
            } else {
                // Обычный режим
                if (currentTrackIndex > 0) currentTrackIndex - 1 else trackLibrary.size - 1
            }
            loadTrack(prevIndex)
            exoPlayer?.play()
        }
    }
    
    // Переключение режима повтора
    private fun toggleRepeatMode() {
        currentRepeatMode = when (currentRepeatMode) {
            RepeatMode.OFF -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.OFF
        }
        updateRepeatButton()
    }
    
    // Переключение режима случайного воспроизведения
    private fun toggleShuffleMode() {
        isShuffleEnabled = !isShuffleEnabled
        updateShuffleButton()
        
        if (isShuffleEnabled) {
            // Создаем перемешанный список индексов
            shuffledIndices.clear()
            shuffledIndices.addAll(trackLibrary.indices.shuffled())
        } else {
            shuffledIndices.clear()
        }
    }
    
    // Обновление текста кнопки повтора
    private fun updateRepeatButton() {
        val text = when (currentRepeatMode) {
            RepeatMode.OFF -> "OFF"
            RepeatMode.ONE -> "1"
            RepeatMode.ALL -> "ALL"
        }
        repeatButton.text = text
    }
    
    // Обновление текста кнопки shuffle
    private fun updateShuffleButton() {
        val text = if (isShuffleEnabled) "ON" else "OFF"
        shuffleButton.text = text
    }
    
    // Обработка окончания трека
    private fun handleTrackEnded() {
        when (currentRepeatMode) {
            RepeatMode.OFF -> {
                // Переход к следующему треку, если есть
                if (isShuffleEnabled && shuffledIndices.isNotEmpty()) {
                    // В shuffle режиме проверяем, не последний ли это трек в перемешанном списке
                    val currentShuffleIndex = shuffledIndices.indexOf(currentTrackIndex)
                    if (currentShuffleIndex != -1 && currentShuffleIndex < shuffledIndices.size - 1) {
                        playNextTrack()
                    }
                } else {
                    // В обычном режиме
                    if (currentTrackIndex < trackLibrary.size - 1) {
                        playNextTrack()
                    }
                }
            }
            RepeatMode.ONE -> {
                // Повтор текущего трека
                exoPlayer?.seekTo(0)
                exoPlayer?.play()
            }
            RepeatMode.ALL -> {
                // Переход к следующему треку или к первому, если это последний
                playNextTrack()
            }
        }
    }
    
    // Обновление информации о треке
    private fun updateTrackInfo() {
        if (currentTrackIndex >= 0 && currentTrackIndex < trackLibrary.size) {
            val currentTrack = trackLibrary[currentTrackIndex]
            val retriever = MediaMetadataRetriever()
            
            try {
                retriever.setDataSource(this, currentTrack.uri)
                
                // Получаем битрейт
                val bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                val bitrate = if (bitrateStr != null) {
                    val bitrateInt = bitrateStr.toIntOrNull() ?: 0
                    if (bitrateInt > 0) {
                        "${bitrateInt / 1000} kbps"
                    } else {
                        "--- kbps"
                    }
                } else {
                    "--- kbps"
                }
                bitrateText.text = bitrate
                
                // Получаем частоту дискретизации (не всегда доступна через MediaMetadataRetriever)
                // Попробуем получить из ExoPlayer
                val format = exoPlayer?.audioFormat
                val sampleRate = if (format != null && format.sampleRate != -1) {
                    "${format.sampleRate / 1000} kHz"
                } else {
                    "44 kHz" // Значение по умолчанию
                }
                frequencyText.text = sampleRate
                
                // Получаем количество каналов
                val format2 = exoPlayer?.audioFormat
                val channels = if (format2 != null && format2.channelCount > 0) {
                    when (format2.channelCount) {
                        1 -> "mono"
                        2 -> "stereo"
                        else -> "${format2.channelCount}ch"
                    }
                } else {
                    "stereo" // Значение по умолчанию
                }
                channelsText.text = channels
                
            } catch (e: Exception) {
                android.util.Log.e("AudioInfo", "Error getting metadata: ${e.message}")
                // Значения по умолчанию при ошибке
                bitrateText.text = "--- kbps"
                frequencyText.text = "-- kHz"
                channelsText.text = "---"
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    android.util.Log.e("AudioInfo", "Error releasing retriever: ${e.message}")
                }
            }
        } else {
            // Если трек не выбран
            bitrateText.text = "--- kbps"
            frequencyText.text = "-- kHz"
            channelsText.text = "---"
        }
    }
    
    // Анимация нажатия кнопки
    private fun animateButtonPress(view: View) {
        val scaleDown = ObjectAnimator.ofFloat(view, "scaleX", 1.0f, 0.9f)
        scaleDown.duration = 100
        scaleDown.interpolator = AccelerateDecelerateInterpolator()
        
        val scaleUp = ObjectAnimator.ofFloat(view, "scaleX", 0.9f, 1.0f)
        scaleUp.duration = 100
        scaleUp.interpolator = AccelerateDecelerateInterpolator()
        
        val scaleDownY = ObjectAnimator.ofFloat(view, "scaleY", 1.0f, 0.9f)
        scaleDownY.duration = 100
        scaleDownY.interpolator = AccelerateDecelerateInterpolator()
        
        val scaleUpY = ObjectAnimator.ofFloat(view, "scaleY", 0.9f, 1.0f)
        scaleUpY.duration = 100
        scaleUpY.interpolator = AccelerateDecelerateInterpolator()
        
        scaleDown.start()
        scaleDownY.start()
        
        scaleDown.addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) {}
            override fun onAnimationEnd(animation: android.animation.Animator) {
                scaleUp.start()
                scaleUpY.start()
            }
            override fun onAnimationCancel(animation: android.animation.Animator) {}
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
        })
    }
    
    // Анимация пульсации для времени воспроизведения
    private fun animateTimeDisplay() {
        val pulse = ObjectAnimator.ofFloat(currentTimeText, "alpha", 1.0f, 0.7f, 1.0f)
        pulse.duration = 1000
        pulse.repeatCount = ObjectAnimator.INFINITE
        pulse.interpolator = AccelerateDecelerateInterpolator()
        pulse.start()
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