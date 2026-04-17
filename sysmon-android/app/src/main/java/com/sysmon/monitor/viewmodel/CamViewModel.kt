package com.sysmon.monitor.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "cam_settings")

private val KEY_STREAM_URL = stringPreferencesKey("stream_url")
private val KEY_AUDIO_ENABLED = booleanPreferencesKey("audio_enabled")
private val KEY_QUALITY_LEVEL = stringPreferencesKey("quality_level")

/** 推流状态 */
enum class StreamState {
    IDLE,
    CONNECTING,
    STREAMING,
    ERROR,
    DISCONNECTED
}

/** 画质档位 */
enum class QualityLevel(val label: String, val tag: String) {
    HIGH("高画质", "1080p · 30fps · 5Mbps"),
    LOW("省电模式", "720p · 24fps · 2Mbps");
}

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CameraViewModel"
        const val DEFAULT_URL = "rtmp://your-server/live/stream"

        // ── 高低档位参数 ──
        // HIGH: 1080p@30fps, 5Mbps, 立体声 44100Hz 128kbps
        // LOW:  720p@24fps, 2Mbps, 单声道 22050Hz 64kbps
        private val HIGH_PRESETS = listOf(
            Triple(1920, 1080, 5_000_000),   // 1080p
            Triple(1280, 720,  2_500_000),   // 720p fallback
            Triple(640,  480,  1_000_000),   // 480p fallback
        )
        private val LOW_PRESETS = listOf(
            Triple(1280, 720,  2_000_000),   // 720p
            Triple(640,  480,  800_000),     // 480p fallback
        )
    }

    // ── 当前设备最优推流参数（动态检测）──
    data class StreamProfile(val width: Int, val height: Int, val bitrate: Int, val fps: Int, val audioBitrate: Int, val audioSampleRate: Int, val audioStereo: Boolean)
    private var _streamProfile: StreamProfile = StreamProfile(1280, 720, 2_000_000, 24, 64_000, 22050, false)
    val streamProfile: StreamProfile get() = _streamProfile

    // ── 画质档位 ──
    private val _qualityLevel = MutableStateFlow(QualityLevel.LOW)
    val qualityLevel: StateFlow<QualityLevel> = _qualityLevel.asStateFlow()

    // ---- 摄像头方向 ----
    private val _lensFacing = MutableStateFlow(CameraHelper.Facing.BACK)
    val lensFacing: StateFlow<CameraHelper.Facing> = _lensFacing.asStateFlow()

    // ---- 麦克风开关 ----
    private val _audioEnabled = MutableStateFlow(true)
    val audioEnabled: StateFlow<Boolean> = _audioEnabled.asStateFlow()

    // ---- 推流地址 ----
    private val _streamUrl = MutableStateFlow(DEFAULT_URL)
    val streamUrl: StateFlow<String> = _streamUrl.asStateFlow()

    // ---- 推流状态 ----
    private val _streamState = MutableStateFlow(StreamState.IDLE)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    // ---- 错误信息 ----
    private val _errorMsg = MutableStateFlow("")
    val errorMsg: StateFlow<String> = _errorMsg.asStateFlow()

    // ---- 实时统计 ----
    data class Stats(
        val bitrate: Long = 0L,
        val duration: Long = 0L
    )
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    // ---- RtmpCamera2 实例 ----
    // 使用 OpenGlView 构造的实例（UI 可见时），或 Context 构造的实例（后台时）
    var rtmpCamera2: RtmpCamera2? = null
        private set

    // 当前是否绑定了预览 Surface
    private var currentGlView: OpenGlView? = null
    private var isSurfaceAvailable = false

    private var streamStartMs = 0L
    private var statsJob: kotlinx.coroutines.Job? = null

    // Surface 生命周期事件的专用 Job，确保 destroy/create 不交错执行
    private var surfaceJob: kotlinx.coroutines.Job? = null

    // ---- 视频帧内嵌时间戳滤镜 ----
    private var timestampFilter: TextObjectFilterRender? = null
    private var timestampJob: kotlinx.coroutines.Job? = null
    private val timestampFormatter = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault())

    // ---- 加载持久化配置 + 检测最优分辨率 ----
    init {
        viewModelScope.launch {
            val prefs = getApplication<Application>().dataStore.data.first()
            _streamUrl.value = prefs[KEY_STREAM_URL] ?: DEFAULT_URL
            _audioEnabled.value = prefs[KEY_AUDIO_ENABLED] ?: true
            _qualityLevel.value = try {
                QualityLevel.valueOf(prefs[KEY_QUALITY_LEVEL] ?: QualityLevel.LOW.name)
            } catch (_: Exception) { QualityLevel.LOW }
            detectBestResolution()
        }
    }

    /** 切换画质档位（非推流中才允许） */
    fun setQualityLevel(level: QualityLevel) {
        if (_streamState.value == StreamState.STREAMING || _streamState.value == StreamState.CONNECTING) return
        _qualityLevel.value = level
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[KEY_QUALITY_LEVEL] = level.name }
        }
        detectBestResolution()
    }

    /**
     * 根据当前档位 + 设备摄像头能力，选择最优推流配置。
     */
    private fun detectBestResolution() {
        val isHigh = _qualityLevel.value == QualityLevel.HIGH
        val presets = if (isHigh) HIGH_PRESETS else LOW_PRESETS
        val fps = if (isHigh) 30 else 24
        val audioBitrate = if (isHigh) 128_000 else 64_000
        val audioSampleRate = if (isHigh) 44100 else 22050
        val audioStereo = isHigh

        fun makeProfile(w: Int, h: Int, vBitrate: Int) =
            StreamProfile(w, h, vBitrate, fps, audioBitrate, audioSampleRate, audioStereo)

        try {
            val cm = getApplication<Application>()
                .getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull() ?: return

            val chars = cm.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList()
                ?: map.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList()
                ?: return

            Log.d(TAG, "设备支持的分辨率: ${sizes.sortedByDescending { it.width * it.height }.take(5)}")

            for ((w, h, bitrate) in presets) {
                if (sizes.any { it.width >= w && it.height >= h }) {
                    _streamProfile = makeProfile(w, h, bitrate)
                    Log.d(TAG, "✅ [${_qualityLevel.value.name}] 选定: ${w}x${h} @${fps}fps, ${bitrate / 1_000_000}Mbps")
                    return
                }
            }
            // fallback
            val maxSize = sizes.maxByOrNull { it.width * it.height }
            if (maxSize != null) {
                val capW = if (isHigh) 1920 else 1280
                val capH = if (isHigh) 1080 else 720
                val w = maxSize.width.coerceAtMost(capW)
                val h = maxSize.height.coerceAtMost(capH)
                val bitrate = (w * h * fps / 10).coerceIn(800_000, if (isHigh) 5_000_000 else 2_000_000)
                _streamProfile = makeProfile(w, h, bitrate)
                Log.d(TAG, "✅ [${_qualityLevel.value.name}] fallback: ${w}x${h}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "检测分辨率失败，使用默认配置: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Surface 生命周期管理
    // 关键原则：推流不随 Surface 销毁而停止
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 当 OpenGlView 的 SurfaceHolder 创建时调用
     * - 如果尚无 RtmpCamera2，创建一个并开始预览
     * - 如果已有（正在推流，Surface 重建），将预览重新附加上去
     */
    fun onSurfaceCreated(glView: OpenGlView) {
        currentGlView = glView
        isSurfaceAvailable = true

        // 取消任何尚未执行完的 surfaceDestroyed 延迟协程，避免和本次 created 交错
        surfaceJob?.cancel()
        surfaceJob = null

        val existing = rtmpCamera2
        if (existing != null) {
            val wasStreaming = existing.isStreaming
            Log.d(TAG, "Surface recreated, re-attaching to existing RtmpCamera2 (streaming=$wasStreaming)")
            if (wasStreaming) {
                // ❗ 推流中只做 replaceView，绝对不能调 startPreview！
                // 摄像头采集管线一直在跑，replaceView 只是把 GL 输出重新绑到新 Surface。
                try {
                    existing.replaceView(glView)
                } catch (e: Exception) {
                    Log.w(TAG, "replaceView failed while streaming: ${e.message}")
                }
                // 延迟重新挂载时间戳滤镜，等 GL 管线稳定；用 surfaceJob 管理以便随时取消
                surfaceJob = viewModelScope.launch(Dispatchers.Main) {
                    kotlinx.coroutines.delay(300)
                    if (_streamState.value == StreamState.STREAMING && isSurfaceAvailable) {
                        reattachTimestampFilter(existing)
                    }
                }
            } else {
                // 未推流，正常重新绑定预览
                try {
                    existing.replaceView(glView)
                    existing.startPreview(_lensFacing.value, _streamProfile.width, _streamProfile.height)
                } catch (e: Exception) {
                    Log.w(TAG, "re-attach preview failed, recreating camera: ${e.message}")
                    recreateCamera(glView)
                }
            }
        } else {
            // 首次创建
            recreateCamera(glView)
        }
    }

    /**
     * 当 SurfaceHolder 被销毁时调用
     * - 如果正在推流，调用 replaceView(context) 切换到无预览模式，推流继续！
     *   replaceView(context) 会重置 GL 管线，因此需要延迟重新挂载时间戳滤镜
     * - 如果未推流，正常停止预览
     */
    fun onSurfaceDestroyed() {
        isSurfaceAvailable = false
        currentGlView = null

        // 取消尚未执行的 surfaceCreated 延迟协程
        surfaceJob?.cancel()
        surfaceJob = null

        val camera = rtmpCamera2 ?: return
        val isStreaming = camera.isStreaming

        if (isStreaming) {
            // 正在推流 → 切到无预览模式（Context），推流不中断！
            Log.d(TAG, "Surface destroyed while streaming → replaceView(context) to keep streaming")
            try {
                camera.replaceView(getApplication<Application>())
            } catch (e: Exception) {
                Log.e(TAG, "replaceView(context) failed: ${e.message}", e)
            }
            // replaceView(context) 会重置 GL 管线并清空滤镜，延迟后重新挂载时间戳
            // 用 surfaceJob 管理，确保后续 onSurfaceCreated 可以及时 cancel 掉本协程
            surfaceJob = viewModelScope.launch(Dispatchers.Main) {
                kotlinx.coroutines.delay(300)
                // 仍处于推流中且 Surface 仍不可用（未回到录制页），才在后台模式下挂载滤镜
                if (_streamState.value == StreamState.STREAMING && !isSurfaceAvailable) {
                    reattachTimestampFilter(camera)
                    Log.d(TAG, "Surface destroyed → 已在无预览模式下重新挂载时间戳滤镜")
                }
            }
        } else {
            // 未推流 → 正常停止预览
            try {
                camera.stopPreview()
            } catch (_: Exception) {}
        }
    }

    private fun recreateCamera(glView: OpenGlView) {
        try {
            // 先清理旧实例
            rtmpCamera2?.let { old ->
                try { old.stopStream() } catch (_: Exception) {}
                try { old.stopPreview() } catch (_: Exception) {}
            }
            val camera = RtmpCamera2(glView, connectChecker)
            rtmpCamera2 = camera
            camera.startPreview(_lensFacing.value, _streamProfile.width, _streamProfile.height)
            Log.d(TAG, "RtmpCamera2 created, preview: ${_streamProfile.width}x${_streamProfile.height}")
        } catch (e: Exception) {
            Log.e(TAG, "recreateCamera error: ${e.message}", e)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 推流控制
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 切换前/后置摄像头（主线程调用）
     */
    fun toggleCamera() {
        val next = if (_lensFacing.value == CameraHelper.Facing.BACK)
            CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK
        _lensFacing.value = next
        try {
            rtmpCamera2?.switchCamera()
        } catch (e: Exception) {
            Log.e(TAG, "switchCamera error", e)
        }
    }

    /** 切换麦克风 */
    fun toggleAudio() {
        val next = !_audioEnabled.value
        _audioEnabled.value = next
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[KEY_AUDIO_ENABLED] = next }
        }
    }

    /** 保存推流地址 */
    fun saveStreamUrl(url: String) {
        _streamUrl.value = url
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[KEY_STREAM_URL] = url }
        }
    }

    /**
     * 开始推流
     *
     * 核心原则：全部操作在同一个主线程协程内顺序执行，杜绝竞态。
     * 流程：
     *   1. 校验 URL
     *   2. 确保 camera 实例存在且处于预览状态（不存在则重建）
     *   3. 停掉旧预览 → prepareVideo → prepareAudio → startPreview → 等帧就绪 → startStream
     */
    fun startStream() {
        val url = _streamUrl.value.trim()
        if (url.isBlank() || url == DEFAULT_URL) {
            _errorMsg.value = "请先在设置中配置推流地址"
            _streamState.value = StreamState.ERROR
            return
        }
        if (_streamState.value == StreamState.STREAMING ||
            _streamState.value == StreamState.CONNECTING) return

        _errorMsg.value = ""
        _streamState.value = StreamState.CONNECTING

        viewModelScope.launch(Dispatchers.Main) {
            try {
                val glView = currentGlView
                if (glView == null || !isSurfaceAvailable) {
                    _errorMsg.value = "摄像头预览未就绪，请稍后重试"
                    _streamState.value = StreamState.ERROR
                    return@launch
                }

                // ── Step 1: 确保有 camera 实例 ──
                var camera = rtmpCamera2
                if (camera == null) {
                    recreateCamera(glView)
                    camera = rtmpCamera2
                }
                if (camera == null) {
                    _errorMsg.value = "摄像头未初始化，请稍后重试"
                    _streamState.value = StreamState.ERROR
                    return@launch
                }

                // ── Step 2: 停掉当前预览，让管线完全干净 ──
                try {
                    if (camera.isOnPreview) {
                        camera.stopPreview()
                        Log.d(TAG, "推流前：先停止旧预览")
                    }
                } catch (_: Exception) {}

                // ── Step 3: prepareVideo + prepareAudio（编码器初始化）──
                val rotation = CameraHelper.getCameraOrientation(getApplication())
                val p = _streamProfile
                Log.d(TAG, "推流参数: ${p.width}x${p.height} @${p.fps}fps, bitrate=${p.bitrate / 1_000_000}Mbps, audio=${p.audioBitrate/1000}k/${p.audioSampleRate}Hz/${if (p.audioStereo) "stereo" else "mono"}")
                val videoOk = camera.prepareVideo(p.width, p.height, p.fps, p.bitrate, rotation)
                val audioOk = camera.prepareAudio(p.audioBitrate, p.audioSampleRate, p.audioStereo)
                if (!videoOk || !audioOk) {
                    Log.e(TAG, "编码器初始化失败 videoOk=$videoOk audioOk=$audioOk，尝试重建 camera")
                    // 重建一次再试
                    recreateCamera(glView)
                    camera = rtmpCamera2
                    if (camera == null) {
                        _errorMsg.value = "编码器初始化失败"
                        _streamState.value = StreamState.ERROR
                        return@launch
                    }
                    val videoOk2 = camera.prepareVideo(p.width, p.height, p.fps, p.bitrate, rotation)
                    val audioOk2 = camera.prepareAudio(p.audioBitrate, p.audioSampleRate, p.audioStereo)
                    if (!videoOk2 || !audioOk2) {
                        _errorMsg.value = "编码器初始化失败，请检查摄像头权限"
                        _streamState.value = StreamState.ERROR
                        return@launch
                    }
                }

                // ── Step 4: 重新启动预览，让摄像头产生帧 ──
                try {
                    camera.startPreview(_lensFacing.value, _streamProfile.width, _streamProfile.height)
                    Log.d(TAG, "推流前：已重新启动预览")
                } catch (e: Exception) {
                    Log.e(TAG, "推流前启动预览失败: ${e.message}")
                    _errorMsg.value = "摄像头预览启动失败"
                    _streamState.value = StreamState.ERROR
                    return@launch
                }

                // ── Step 5: 等待一小段时间让摄像头出帧 ──
                kotlinx.coroutines.delay(200)

                // ── Step 6: 添加时间戳滤镜 ──
                setupTimestampFilter(camera)

                // ── Step 7: 开始推流 ──
                camera.startStream(url)
                Log.d(TAG, "✅ startStream 调用完成，等待连接回调")
            } catch (e: Exception) {
                Log.e(TAG, "startStream error: ${e.message}", e)
                _errorMsg.value = e.message ?: "推流启动失败"
                _streamState.value = StreamState.ERROR
            }
        }
    }

    /** 停止推流 */
    fun stopStream() {
        stopTimestampUpdater()
        try {
            rtmpCamera2?.let { cam ->
                cam.getGlInterface().clearFilters()
                cam.stopStream()
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopStream error", e)
        }
        timestampFilter = null
        _streamState.value = StreamState.IDLE
        _stats.value = Stats()
        streamStartMs = 0L
        stopStatsTimer()

        // 异步恢复预览，避免与 stopStream 的内部锁冲突导致 ANR
        // 注意：startStream 会自己先 stopPreview 再 prepare，所以这里的恢复预览即使
        // 与 startStream 时间重叠也不会出问题——startStream 在协程里会重新走完整流程。
        viewModelScope.launch(Dispatchers.Main) {
            kotlinx.coroutines.delay(500)
            try {
                val cam = rtmpCamera2
                val glView = currentGlView
                // 只在仍处于 IDLE 状态时才恢复预览（如果用户已经点了开始推流则不干扰）
                if (cam != null && glView != null && isSurfaceAvailable
                    && !cam.isOnPreview && _streamState.value == StreamState.IDLE) {
                    cam.startPreview(_lensFacing.value, _streamProfile.width, _streamProfile.height)
                    Log.d(TAG, "推流停止后已恢复预览")
                }
            } catch (e: Exception) {
                Log.w(TAG, "恢复预览失败: ${e.message}")
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 视频帧内嵌时间戳（右下角）
    // 使用 RootEncoder 的 TextObjectFilterRender，文字会被编码进视频流
    // ────────────────────────────────────────────────────────────────

    /**
     * 创建并挂载时间戳滤镜
     * 使用 setDefaultScale 让滤镜根据文字实际像素大小自动计算缩放，不拉伸
     */
    private fun applyTimestampFilter(camera: RtmpCamera2) {
        try {
            val filter = TextObjectFilterRender()
            camera.getGlInterface().setFilter(filter)
            val p = _streamProfile
            filter.setText(timestampFormatter.format(Date()), 24f, Color.WHITE, Typeface.DEFAULT)
            filter.setDefaultScale(p.width, p.height)
            filter.setPosition(TranslateTo.BOTTOM_RIGHT)
            timestampFilter = filter
            Log.d(TAG, "✅ 时间戳滤镜已挂载（右下角，自动缩放）")
        } catch (e: Exception) {
            Log.w(TAG, "时间戳滤镜挂载失败: ${e.message}")
        }
    }

    private fun setupTimestampFilter(camera: RtmpCamera2) = applyTimestampFilter(camera)
    private fun reattachTimestampFilter(camera: RtmpCamera2) = applyTimestampFilter(camera)

    private fun startTimestampUpdater() {
        timestampJob?.cancel()
        timestampJob = viewModelScope.launch(Dispatchers.Main) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (_streamState.value != StreamState.STREAMING) break
                try {
                    timestampFilter?.setText(timestampFormatter.format(Date()), 24f, Color.WHITE, Typeface.DEFAULT)
                } catch (_: Exception) {}
            }
        }
    }

    private fun stopTimestampUpdater() {
        timestampJob?.cancel()
        timestampJob = null
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ConnectChecker 回调（可能在任意线程，必须切主线程）
    // ══════════════════════════════════════════════════════════════════════════

    val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            postMain { _streamState.value = StreamState.CONNECTING }
        }

        override fun onConnectionSuccess() {
            postMain {
                _streamState.value = StreamState.STREAMING
                streamStartMs = System.currentTimeMillis()
                startStatsTimer()
                startTimestampUpdater()
            }
        }

        override fun onConnectionFailed(reason: String) {
            postMain {
                _errorMsg.value = "连接失败: $reason"
                _streamState.value = StreamState.ERROR
            }
        }

        override fun onDisconnect() {
            postMain {
                _streamState.value = StreamState.DISCONNECTED
                stopStatsTimer()
            }
        }

        override fun onAuthError() {
            postMain {
                _errorMsg.value = "认证失败，请检查推流地址"
                _streamState.value = StreamState.ERROR
            }
        }

        override fun onAuthSuccess() {}

        override fun onNewBitrate(bitrate: Long) {
            postMain { _stats.value = _stats.value.copy(bitrate = bitrate) }
        }
    }

    private fun postMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main) { block() }
    }

    private fun startStatsTimer() {
        statsJob?.cancel()
        statsJob = viewModelScope.launch(Dispatchers.Main) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (_streamState.value != StreamState.STREAMING) break
                val elapsed = if (streamStartMs > 0)
                    (System.currentTimeMillis() - streamStartMs) / 1000L else 0L
                _stats.value = _stats.value.copy(duration = elapsed)
            }
        }
    }

    private fun stopStatsTimer() {
        statsJob?.cancel()
        statsJob = null
    }

    override fun onCleared() {
        stopStream()
        try {
            rtmpCamera2?.stopPreview()
        } catch (_: Exception) {}
        rtmpCamera2 = null
        super.onCleared()
    }
}
