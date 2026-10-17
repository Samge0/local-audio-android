package com.samge.localaudio.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.media3.session.SessionToken
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.MoreExecutors
import com.samge.localaudio.data.DirMigrator
import com.samge.localaudio.data.ImportState
import com.samge.localaudio.data.Library
import com.samge.localaudio.data.MigrateResult
import com.samge.localaudio.data.PosStore
import com.samge.localaudio.data.SettingsStore
import com.samge.localaudio.data.Subtitle
import com.samge.localaudio.data.SubtitleBridge
import com.samge.localaudio.data.Track
import com.samge.localaudio.data.ZipImporter
import com.samge.localaudio.player.PlayerService
import com.samge.localaudio.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** UI 状态。 */
data class LibraryUi(
    val items: List<Track> = emptyList(),
    val loading: Boolean = true,
    val dir: String = "",
    val customDir: Boolean = false,
    val hasPerm: Boolean = true,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val settings = SettingsStore(app)
    private val pos = PosStore(app)

    var ui by mutableStateOf(LibraryUi())
        private set
    var importing by mutableStateOf<ImportState>(ImportState.Idle)
        private set
    var migrating by mutableStateOf<MigrateResult?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var curId by mutableStateOf<String?>(settings.curId)
        private set
    var rate by mutableFloatStateOf(settings.rate)
        private set
    var mode by mutableStateOf(settings.mode)
        private set
    var sleepLeft by mutableLongStateOf(0L)
        private set
    var subOn by mutableStateOf(true)
        private set
    var subText by mutableStateOf<String?>(null)
        private set
    var collapsed by mutableStateOf(setOf<String>())
        private set
    /** 上一首/下一首/自动续播使用的序列（搜索时=过滤集）。 */
    private var playSeq: List<Track> = emptyList()

    private var controller: MediaController? = null
    private var importJob: Job? = null
    private var sleepJob: Job? = null
    private var sleepIdx = 0

    private val appContext get() = getApplication<Application>()

    fun bindController() {
        if (controller != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlayerService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            try {
                controller = future.get().also { c ->
                    c.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            playing = isPlaying
                        }
                        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                            mediaItem?.mediaId?.let { curIdSet(it) }
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) {
                                // 播完：清进度 + 自动续播（mode=one 由 repeatMode 处理）
                                currentId()?.let { pos.clear(it) }
                                val nid = nextTrack(1)
                                if (nid != null && mode != "one") play(nid)
                            }
                        }
                    })
                }
            } catch (e: Exception) {
                android.util.Log.e("MainVM", "controller failed", e)
            }
        }, MoreExecutors.directExecutor())
    }

    // ---------- 列表 ----------
    fun refresh() {
        viewModelScope.launch {
            val dir = settings.audioDir()
            val items = withContext(Dispatchers.IO) { Library.scanSorted(dir) }
            ui = LibraryUi(
                items = items,
                loading = false,
                dir = dir.absolutePath,
                customDir = settings.isCustomDir(),
                hasPerm = settings.hasStoragePerm(dir),
            )
        }
    }

    // ---------- 播放控制 ----------
    private var curCues: List<com.samge.localaudio.data.Cue> = emptyList()

    fun play(track: Track) {
        val c = controller ?: return
        val list = ui.items
        // 目标组若折叠先展开（对齐 Web expandGroupOf）
        val gname = track.group.ifEmpty { "未分组" }
        if (gname in settings.collapsedGroups()) {
            toggleGroup(gname)
        }
        curIdSet(track.id)
        // 字幕预载（IO 线程解析，避免大 vtt 阻塞主线程造成卡顿）
        curCues = emptyList()
        subText = null
        val subFile = track.sub
        if (subFile != null) {
            viewModelScope.launch {
                val parsed = withContext(Dispatchers.IO) {
                    runCatching { Subtitle.parse(subFile) }.getOrDefault(emptyList())
                }
                // 仅当仍是当前曲目时应用（快速切歌防串台）；回主线程写状态
                if (currentId() == track.id) {
                    curCues = parsed
                    if (subOn && parsed.isNotEmpty()) subText = ""
                }
            }
        }
        val idx = list.indexOfFirst { it.id == track.id }
        if (c.currentMediaItem == null || c.currentMediaItem!!.mediaId != track.id) {
            val startIndex = if (idx >= 0) idx else 0
            val mediaItems: List<MediaItem> = list.map { t -> t.toMediaItem() }
            if (mode == "shuffle") {
                // 随机模式：单文件播放，播完由 ended → nextTrack 随机选（对齐 Web 行为）
                c.setMediaItems(listOf(mediaItems[startIndex]), 0, 0L)
            } else {
                c.setMediaItems(mediaItems, startIndex, 0L)
            }
            // 恢复上次进度
            val p = pos.get(track.id)
            if (p > 2) {
                c.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            c.removeListener(this)
                            val dur = c.contentDuration
                            if (dur <= 0 || p * 1000L < dur - 1500) {
                                c.seekTo(p * 1000L)
                                toast("已从上次进度 " + Fmt.time(p.toLong()) + " 继续")
                            }
                        }
                    }
                })
            }
        }
        c.setPlaybackSpeed(rate)
        c.playWhenReady = true
        c.prepare()
    }

    private fun curIdSet(id: String) {
        settings.curId = id
        curId = id
    }

    private fun Track.toMediaItem(): MediaItem {
        val builder = MediaItem.Builder()
            .setMediaId(id)
            .setUri(android.net.Uri.fromFile(File(settings.audioDir(), id)))
        val meta = MediaMetadata.Builder()
            .setTitle(name)
            .setArtist(group.ifEmpty { "audio" })
            .build()
        builder.setMediaMetadata(meta)
        return builder.build()
    }

    fun currentId(): String? = controller?.currentMediaItem?.mediaId ?: settings.curId

    fun togglePlay() {
        val c = controller ?: return
        if (c.currentMediaItem == null) {
            ui.items.firstOrNull()?.let { play(it) }
            return
        }
        if (c.isPlaying) c.pause() else c.play()
    }

    /** 对齐 Web nextId：在续播序列内走；shuffle 随机；loop 末尾回绕；无下一步 toast。 */
    private fun nextTrack(dir: Int): Track? {
        val seq = if (playSeq.isNotEmpty()) playSeq else ui.items
        if (seq.isEmpty()) return null
        val cur = currentId()
        if (mode == "shuffle") {
            if (seq.size == 1) return seq[0]
            while (true) {
                val n = seq[ kotlin.random.Random.nextInt(seq.size) ]
                if (n.id != cur) return n
            }
        }
        var i = seq.indexOfFirst { it.id == cur }
        if (i == -1) return seq[0]
        val j = i + dir
        if (j >= seq.size) {
            return if (mode == "loop") seq[0] else null
        }
        if (j < 0) return seq[0]
        return seq[j]
    }

    fun step(dir: Int) {
        val nid = nextTrack(dir)
        if (nid == null) {
            toast(if (dir > 0) "已经是最后一个了" else "已经是第一个了")
            return
        }
        play(nid)
    }

    fun seekBy(sec: Long) {
        val c = controller ?: return
        val max = if (c.duration > 0) c.duration else Long.MAX_VALUE
        c.seekTo((c.currentPosition + sec * 1000).coerceIn(0L, max))
    }

    fun seekTo(fraction: Float) {
        val c = controller ?: return
        if (c.duration > 0) {
            c.seekTo((fraction * c.duration).toLong().coerceIn(0L, c.duration))
        }
    }

    fun applyRate(r: Float) {
        val rr = r.coerceIn(0.1f, 4.0f)
        rate = rr
        settings.rate = rr
        controller?.setPlaybackSpeed(rr)
    }

    /** 返回新模式 key。 */
    fun cycleMode(): String {
        val order = listOf("seq", "loop", "one", "shuffle")
        val next = order[(order.indexOf(mode) + 1) % order.size]
        mode = next
        settings.mode = next
        controller?.repeatMode = when (next) {
            "loop" -> Player.REPEAT_MODE_ALL
            "one" -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        return next
    }

    /** UI 每 tick 调：更新当前字幕文本（推送到悬浮窗桥）。 */
    fun tickSub() {
        if (!subOn) {
            SubtitleBridge.setShow(false)
            return
        }
        // 字幕开但悬浮窗服务未启动时兜底启动（幂等）
        if (SubtitleBridge.onChanged == null && android.provider.Settings.canDrawOverlays(getApplication())) {
            SubtitleBridge.setShow(true)
            startOverlayService()
        }
        val c = controller ?: return
        if (curCues.isEmpty()) {
            SubtitleBridge.push(null)
            return
        }
        val txt = Subtitle.currentText(curCues, c.currentPosition)
        if (txt != subText) {
            subText = txt
            SubtitleBridge.push(txt)
        }
    }

    /** 冷启动恢复上次曲目（不自动播放，对齐 Web restoreLast）。 */
    fun restoreIfNeeded() {
        val cid = settings.curId ?: return
        val t = ui.items.firstOrNull { it.id == cid } ?: return
        if (controller?.currentMediaItem == null) {
            play(t)
            controller?.pause()
        }
    }

    fun posOf(id: String): Int = pos.get(id)

    fun durationMs(): Long = controller?.duration ?: 0L

    fun positionMs(): Long = controller?.currentPosition ?: 0L

    fun saveNow() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId ?: return
        if (c.playbackState == Player.STATE_ENDED) return
        val dur = c.contentDuration
        val cur = c.currentPosition
        // 接近末尾（<1.5s）视为已听完：不记进度，清掉旧值
        if (dur > 0 && cur >= dur - 1500) {
            pos.clear(id)
            return
        }
        val sec = (cur / 1000L).toInt()
        if (sec >= 1) pos.put(id, sec)
    }

    // ---------- 定时停止 ----------
    /** 返回本次设置的分钟数；null = 已取消/关闭。 */
    fun cycleSleep(minutesList: List<Int>): Int? {
        if (sleepJob != null) {
            sleepJob?.cancel()
            sleepJob = null
            sleepLeft = 0
            return null
        }
        val mins = minutesList[sleepIdx % minutesList.size]
        sleepIdx++
        val endAt = System.currentTimeMillis() + mins * 60_000L
        sleepJob = viewModelScope.launch {
            while (true) {
                val left = endAt - System.currentTimeMillis()
                if (left <= 0) {
                    controller?.pause()
                    saveNow()
                    sleepJob = null
                    sleepLeft = 0
                    break
                }
                sleepLeft = left / 1000
                delay(1000)
            }
        }
        return mins
    }

    /** 直接设定 N 分钟（弹层用）。 */
    fun setSleep(mins: Int) {
        sleepJob?.cancel()
        sleepJob = null
        sleepLeft = 0
        val endAt = System.currentTimeMillis() + mins * 60_000L
        sleepJob = viewModelScope.launch {
            while (true) {
                val left = endAt - System.currentTimeMillis()
                if (left <= 0) {
                    controller?.pause()
                    saveNow()
                    sleepJob = null
                    sleepLeft = 0
                    toast("定时结束，已停止播放")
                    break
                }
                sleepLeft = left / 1000
                delay(1000)
            }
        }
        toast(mins.toString() + " 分钟后停止播放")
    }

    fun cancelSleep() {
        sleepJob?.cancel()
        sleepJob = null
        sleepLeft = 0
        toast("已取消定时")
    }

    // ---------- zip 导入 ----------
    fun importZip(url: String, subDir: String) {
        if (url.isBlank()) return
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            ZipImporter(settings) { st ->
                importing = st
                if (st is ImportState.Failed) toast("导入失败：" + st.message)
            }
                .import(url.trim(), subDir.trim().trimStart('/'))
            refresh()
        }
    }


    /** 从本地 zip（SAF 选择的 content:// URI）导入，复用解压逻辑。 */
    fun importLocalZip(uri: Uri) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            val ctx = getApplication<Application>()
            val tmp = kotlin.io.path.createTempFile("kaudio_local_", ".zip").toFile()
            try {
                importing = ImportState.Running("读取", 0f, "复制 zip…")
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { ins ->
                        tmp.outputStream().use { ins.copyTo(it) }
                    } ?: throw IllegalStateException("无法读取所选文件")
                }
                val settingsDir = settings.audioDir()
                val (added, skipped) = withContext(Dispatchers.IO) {
                    unzipLocal(tmp, settingsDir)
                }
                importing = ImportState.Done(added, skipped, tmp.length(), settingsDir.absolutePath)
            } catch (e: Exception) {
                android.util.Log.e("MainVM", "local import failed", e)
                importing = ImportState.Failed(e.message ?: "导入失败")
                toast("导入失败：" + (e.message ?: "未知错误"))
            } finally {
                tmp.delete()
            }
            refresh()
        }
    }

    private fun unzipLocal(zip: File, target: File): Pair<Int, Int> {
        // GBK charset：Windows 打包的中文 zip（无 UTF-8 标志位）正确解码；标准 UTF-8 包不受影响
        val zis = java.util.zip.ZipInputStream(zip.inputStream().buffered(),
            java.nio.charset.Charset.forName("GBK"))
        try {
            var added = 0
            var skipped = 0
            while (true) {
                val e = zis.nextEntry ?: break
                val name = com.samge.localaudio.data.ZipImporter.fixZipName(e.name)
                if (e.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val base = name.substringAfterLast('/')
                val ext = base.substringAfterLast('.', "").lowercase()
                if (base.startsWith("._") || base.startsWith(".") ||
                    name.contains("__MACOSX") ||
                    ext !in com.samge.localaudio.data.Library.AUDIO_EXT
                    && ext !in com.samge.localaudio.data.Library.SUB_EXTS) {
                    zis.closeEntry()
                    continue
                }
                val outFile = File(target, name)
                val rootPath = target.canonicalFile.absolutePath
                val outPath = outFile.canonicalFile.absolutePath
                if (!outPath.startsWith(rootPath)) {
                    zis.closeEntry()
                    continue
                }
                outFile.parentFile?.mkdirs()
                if (outFile.exists() && outFile.length() == e.size) {
                    skipped++
                } else {
                    java.io.FileOutputStream(outFile).use { zis.copyTo(it) }
                    added++
                }
                zis.closeEntry()
            }
            return Pair(added, skipped)
        } finally {
            zis.close()
        }
    }

    // ---------- 目录迁移 ----------
    fun changeDir(newDir: String, autoMigrate: Boolean, onDone: (MigrateResult?) -> Unit) {
        viewModelScope.launch {
            val old = settings.audioDir()
            val newDirF = File(newDir)
            var res: MigrateResult? = null
            if (autoMigrate && old.isDirectory && old.canonicalPath != newDirF.canonicalPath) {
                res = DirMigrator.migrate(old, newDirF)
            }
            settings.dirPath = newDirF.absolutePath
            migrating = res
            refresh()
            onDone(res)
        }
    }

    fun toggleGroup(name: String) {
        val cur = settings.collapsedGroups()
        val next = if (name in cur) cur - name else cur + name
        settings.saveCollapsed(next)
        collapsed = next
    }

    fun collapseAll(names: Collection<String>) {
        settings.saveCollapsed(names.toSet())
        collapsed = names.toSet()
    }

    fun toast(msg: String) {
        toastMsg = msg
    }

    var toastMsg by mutableStateOf<String?>(null)
        private set

    fun consumeToast() {
        toastMsg = null
    }

    /** 更新续播序列（列表渲染时调用；搜索过滤后传可见集）。 */
    fun updateSeq(seq: List<Track>) {
        playSeq = seq
    }

    /** 返回值：0=正常切换；1=无字幕提示；2=无权限（已弹引导窗，不切换状态）。 */
    fun toggleSub(): Int {
        val cur = ui.items.firstOrNull { it.id == currentId() }
        if (cur?.sub == null) return 1
        // 开启方向需要悬浮窗权限；未授权时不切换状态，弹引导窗
        if (!subOn && !android.provider.Settings.canDrawOverlays(getApplication())) {
            showOverlayPerm = true
            return 2
        }
        applySubOn(!subOn)
        return 0
    }

    /** 字幕开关：控制悬浮窗服务 + 桥状态。 */
    fun applySubOn(on: Boolean) {
        subOn = on
        settings.subOn = on
        if (!on) {
            subText = null
            SubtitleBridge.setShow(false)
            stopOverlayService()
        } else {
            SubtitleBridge.folded = false   // 新会话默认展开显示
            SubtitleBridge.setShow(true)
            startOverlayService()
        }
    }

    /** 悬浮窗权限缺失 → UI 弹引导窗（带跳转入口）。 */
    var showOverlayPerm by mutableStateOf(false)
        private set

    /** 幂等：字幕开启且当前曲目有字幕时启动悬浮窗；权限缺失时触发引导弹窗。 */
    fun ensureOverlayStarted() {
        if (!subOn) return
        if (!android.provider.Settings.canDrawOverlays(getApplication())) {
            if (!overlayPermAsked) {
                overlayPermAsked = true
                showOverlayPerm = true
            }
            return
        }
        SubtitleBridge.setShow(true)
        startOverlayService()
    }
    private var overlayPermAsked = false

    /** 跳转到本应用的「显示在其他应用上层」系统设置页。 */
    fun openOverlaySettings(onOpen: () -> Unit) {
        val ctx = getApplication<Application>()
        runCatching {
            ctx.startActivity(android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + ctx.packageName)
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            onOpen()
        }
    }

    fun dismissOverlayPerm() {
        showOverlayPerm = false
    }

    private fun startOverlayService() {
        val ctx = getApplication<Application>()
        if (android.provider.Settings.canDrawOverlays(ctx)) {
            ctx.startService(android.content.Intent(ctx,
                com.samge.localaudio.player.SubtitleOverlayService::class.java))
        } else {
            showOverlayPerm = true
        }
    }

    private fun stopOverlayService() {
        val ctx = getApplication<Application>()
        runCatching {
            ctx.stopService(android.content.Intent(ctx,
                com.samge.localaudio.player.SubtitleOverlayService::class.java))
        }
    }

    /** 清空当前音频目录（所有文件+子目录）。 */
    fun clearAudioDir(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            controller?.stop()
            val dir = settings.audioDir()
            var deleted = 0
            withContext(Dispatchers.IO) {
                dir.walkBottomUp().filter { it != dir }.forEach {
                    if (runCatching { it.delete() }.getOrDefault(false)) deleted++
                }
            }
            toast("已清理 " + deleted + " 个文件/目录")
            refresh()
            onDone(deleted)
        }
    }

    fun dismissMigrate() {
        migrating = null
    }

    override fun onCleared() {
        saveNow()
        controller?.release()
        controller = null
        super.onCleared()
    }
}
