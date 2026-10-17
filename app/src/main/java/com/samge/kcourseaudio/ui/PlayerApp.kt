package com.samge.kcourseaudio.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samge.kcourseaudio.data.ImportState
import com.samge.kcourseaudio.data.MigrateResult
import com.samge.kcourseaudio.data.Track
import com.samge.kcourseaudio.util.Fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val MODE_LABEL = mapOf("seq" to "顺序", "loop" to "循环", "one" to "单曲", "shuffle" to "随机")
private val SLEEPS = listOf(5, 10, 15, 30, 60)
private val PRESETS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)

@Composable
fun PlayerApp(vm: MainViewModel, onPickZip: () -> Unit, onPickDir: () -> Unit) {
    val ui = vm.ui
    var query by remember { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }
    var showDir by remember { mutableStateOf(false) }
    var showRatePop by remember { mutableStateOf(false) }
    var showSleepPop by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        var n = 0L
        while (true) {
            tick = n++
            vm.tickSub()
            delay(400)
        }
    }
    LaunchedEffect(ui.items) {
        if (ui.items.isNotEmpty()) vm.restoreIfNeeded()
    }
    // 首次播放带字幕的曲目：自动开启字幕悬浮窗
    LaunchedEffect(vm.curId) {
        val id = vm.curId ?: return@LaunchedEffect
        val t = ui.items.firstOrNull { it.id == id }
        if (t?.sub != null && vm.subOn) vm.ensureOverlayStarted()
    }

    // 续播序列 = 当前可见集（对齐 Web visibleItems）
    val q = query.trim()
    val vis = remember(ui.items, q) {
        if (q.isEmpty()) ui.items
        else ui.items.filter { it.name.contains(q, true) || it.group.contains(q, true) }
    }
    LaunchedEffect(vis) { vm.updateSeq(vis) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            MacTitleBar(onImport = { showImport = true }, onDir = { showDir = true })

            Box(Modifier.weight(1f)) {
                // 整页滚动：唱片区 + 搜索 + 列表 全部在一个 LazyColumn 里
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                val scope = androidx.compose.runtime.rememberCoroutineScope()

                // 滚过唱片区（前 2 个 item）后显示吸顶迷你栏（对齐 Web mini-bar）
                val showMini by androidx.compose.runtime.remember {
                    androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > 1 }
                }

                androidx.compose.foundation.lazy.LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // item 0：唱片区 + 标题 + 字幕
                    item(key = "stage") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Spacer(Modifier.height(8.dp))
                            VinylDisc(playing = vm.playing)
                            Spacer(Modifier.height(12.dp))
                            val cur = ui.items.firstOrNull { it.id == vm.curId }
                            Text(
                                cur?.name ?: "未在播放",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            cur?.let {
                                Text(
                                    (it.group.ifEmpty { "未分组" }) + " · " + Fmt.size(it.size) + " · " + fmtRate(vm.rate) + "x",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            // 字幕走悬浮窗（SubtitleOverlayService），不再内嵌布局
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    // item 1：搜索
                    item(key = "search") {
                        SearchField(query) { query = it }
                    }
                    // 列表内容
                    when {
                        ui.loading -> item(key = "loading") { Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { Text("加载中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                        ui.items.isEmpty() -> item(key = "empty") { Box(Modifier.fillParentMaxSize()) { EmptyBox(ui) } }
                        vis.isEmpty() -> item(key = "nores") { Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { Text("无匹配音频", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                        else -> trackListItems(vm, vis, q.isNotEmpty(), vm.collapsed)
                    }
                }

                // 吸顶迷你栏（对齐 Web mini-bar：播放/切集/标题+字幕镜像/倍速）
                androidx.compose.animation.AnimatedVisibility(
                    visible = showMini,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = androidx.compose.animation.slideInVertically { -it } + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.slideOutVertically { -it } + androidx.compose.animation.fadeOut(),
                ) {
                    MiniBar(
                        vm = vm,
                        onPlayPause = { vm.togglePlay() },
                        onPrev = { vm.step(-1) },
                        onNext = { vm.step(1) },
                        onBackToTop = { scope.launch { listState.animateScrollToItem(0) } },
                        onRate = { showRatePop = !showRatePop; showSleepPop = false },
                    )
                }
            }

            // ---- 播放器底栏（固定底部，对齐 Web .player）----
            PlayerBar(
                vm,
                showRatePop, showSleepPop,
                onToggleRate = { showRatePop = !showRatePop; showSleepPop = false },
                onToggleSleep = { showSleepPop = !showSleepPop; showRatePop = false },
            )
        }
    }

    if (showImport) ImportDialog(vm, onPickZip) { showImport = false }
    if (showDir) DirDialog(vm, onPickDir) { showDir = false }
    if (showRatePop) RatePopup(vm) { showRatePop = false }
    if (showSleepPop) SleepPopup(vm) { showSleepPop = false }

    val st = vm.importing
    if (st is ImportState.Running) ImportProgressDialog(st)
    val mig = vm.migrating
    if (mig != null) MigrateResultDialog(mig) { vm.dismissMigrate() }

    // Toast
    vm.toastMsg?.let { msg ->
        ToastView(msg) { vm.consumeToast() }
    }

    // 悬浮窗权限引导弹窗（字幕需要，带跳转入口）
    if (vm.showOverlayPerm) {
        AlertDialog(
            onDismissRequest = { vm.dismissOverlayPerm() },
            title = { Text("开启字幕悬浮窗", fontSize = 15.sp) },
            text = {
                Column {
                    Text(
                        "字幕以悬浮窗形式显示在屏幕上，需要「显示在其他应用上层」权限。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "步骤：点击下方「去设置」→ 找到「音频播放器」→ 打开「显示在其他应用上层」开关 → 返回应用。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissOverlayPerm()
                    vm.openOverlaySettings { }
                }) { Text("去设置", fontSize = 13.sp, color = Accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissOverlayPerm() }) { Text("暂不需要", fontSize = 13.sp) }
            },
        )
    }
}

/** Mac 交通灯标题栏。 */
@Composable
private fun MacTitleBar(onImport: () -> Unit, onDir: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左：交通灯（宽 3*11 + 2*7 = 47dp）
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.width(47.dp)) {
            Box(Modifier.size(11.dp).clip(CircleShape).background(Color(0xFFFF5F57)))
            Box(Modifier.size(11.dp).clip(CircleShape).background(Color(0xFFFEBC2E)))
            Box(Modifier.size(11.dp).clip(CircleShape).background(Color(0xFF28C840)))
        }
        // 中：标题（weight 伸展，视觉居中）+ 动态版本号
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(
                "音频播放器 " + com.samge.kcourseaudio.BuildConfig.VERSION_NAME,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 右：按钮组右对齐（自然宽度，不与标题争空间）
        Row(
            modifier = Modifier.align(Alignment.CenterVertically),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextLink("导入", onImport)
            TextLink("目录", onDir)
        }
    }
}

@Composable
private fun TextLink(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        color = Accent,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp),
    )
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp).height(50.dp),
        placeholder = { Text("搜索音频", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
    )
}

/** 播放时 60s/圈；暂停保持 0 度（对齐 Web spin 60s）。 */
private fun Modifier.spin() = this

@Composable
private fun rememberInfiniteTransitionSpin(playing: Boolean): Float {
    val t = rememberInfiniteTransition(label = "disc")
    val a = t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(60000, easing = LinearEasing)
        ),
        label = "spin",
    )
    return if (playing) a.value else 0f
}

/** 黑胶唱片（对齐 Web .artwork：渐变圆 + 光痕 + 中心 hub，播放时旋转）。 */
@Composable
private fun VinylDisc(playing: Boolean) {
    val angle = rememberInfiniteTransitionSpin(playing)
    androidx.compose.foundation.Canvas(
        modifier = Modifier.size(180.dp),
    ) {
        val r = size.minDimension / 2f
        rotate(if (playing) angle else 0f) {
            // 渐变盘面
            drawCircle(Brush.linearGradient(listOf(Color(0xFF5472F0), Color(0xFF7B5CF0))), radius = r)
            // 同心纹理
            for (i in 1..8) {
                drawCircle(
                    Color.Black.copy(alpha = 0.045f),
                    radius = r * (1f - i / 9f),
                    style = Stroke(width = r * 0.02f),
                )
            }
            // 光痕
            drawRoundRect(
                Color.White.copy(alpha = 0.22f),
                topLeft = androidx.compose.ui.geometry.Offset(size.width / 2f - r * 0.012f, r * 0.05f),
                size = androidx.compose.ui.geometry.Size(r * 0.024f, r * 0.17f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
            )
        }
        // 中心 hub（不旋转）
        drawCircle(Color(0xFFF2F2F5), radius = r * 0.065f)
        drawCircle(Color(0xFF1E2028), radius = r * 0.017f)
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyBox(ui: LibraryUi) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "音频目录为空",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "右上角「导入」选择本机 zip 或粘贴下载链接；" +
                "或「目录」选择系统文件夹（可自动迁移）。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 列表 items：分组可折叠（LazyListScope 扩展，供整页 LazyColumn 复用）。 */
private fun androidx.compose.foundation.lazy.LazyListScope.trackListItems(
    vm: MainViewModel,
    vis: List<Track>,
    searching: Boolean,
    collapsed: Set<String>,
) {
    // 预计算：每条记录是否为本组第一条（纯函数，无重组副作用）
    val firstOfGroup = HashSet<Int>()
    val countOfGroup = HashMap<String, Int>()
    for (t in vis) {
        val g = t.group.ifEmpty { "未分组" }
        countOfGroup[g] = (countOfGroup[g] ?: 0) + 1
    }
    var prev: String? = null
    for ((i, t) in vis.withIndex()) {
        val g = t.group.ifEmpty { "未分组" }
        if (g != prev) firstOfGroup += i
        prev = g
    }
    itemsIndexed(vis, key = { _, t -> t.id }) { i, t ->
        val gname = t.group.ifEmpty { "未分组" }
        val showHeader = i in firstOfGroup
        val fold = !searching && gname in collapsed   // 搜索时强制展开
        Column(Modifier.padding(horizontal = 10.dp).animateContentSize()) {
            if (showHeader) {
                GroupHeader(
                    name = gname,
                    count = countOfGroup[gname] ?: 1,
                    folded = fold,
                ) { vm.toggleGroup(gname) }
            }
            if (!fold) TrackRow(vm, t)
        }
    }
}

/** 吸顶迷你栏（对齐 Web mini-bar：上一首/播放/下一首 + 标题&字幕镜像 + 倍速）。 */
@Composable
private fun MiniBar(
    vm: MainViewModel,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBackToTop: () -> Unit,
    onRate: () -> Unit,
) {
    val cur = vm.ui.items.firstOrNull { it.id == vm.curId }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clip(CircleShape).clickable(onClick = onPrev).padding(8.dp)) {
                PrevIcon(color = MaterialTheme.colorScheme.onBackground)
            }
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Accent).clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                if (vm.playing) PauseIcon(scale = 0.8f) else PlayIcon(scale = 0.8f)
            }
            Box(Modifier.clip(CircleShape).clickable(onClick = onNext).padding(8.dp)) {
                NextIcon(color = MaterialTheme.colorScheme.onBackground)
            }
            // 标题 + 字幕镜像（点击回顶部）
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onBackToTop)
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    cur?.name ?: "未在播放",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (vm.subOn && vm.subText != null) {
                    Text(
                        vm.subText ?: "",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                fmtRate(vm.rate) + "x",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onRate)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun GroupHeader(name: String, count: Int, folded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            count.toString(),
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        )
        Text(
            if (folded) "›" else "⌄",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

@Composable
private fun TrackRow(vm: MainViewModel, t: Track) {
    // 读取 state：curId / playing 变化都会触发本行重组 → 选中态即时刷新
    val curId = vm.curId
    val playing = vm.playing
    val isCur = curId == t.id
    val posSec = vm.posOf(t.id)
    val rowBg = if (isCur) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(rowBg)
            .clickable { vm.play(t) }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 选中指示：左侧 accent 竖条（不依赖播放状态，点击即亮）
        Box(
            Modifier
                .width(4.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isCur) Accent else Color.Transparent)
        )
        Spacer(Modifier.width(6.dp))
        // 序号位：选中即显示 ▶（播放中加深）
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            if (isCur) {
                Text("▶", fontSize = 10.sp, color = Accent, fontWeight = FontWeight.Bold)
            } else {
                Text(
                    (vm.ui.items.indexOf(t) + 1).toString(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                t.name,
                fontSize = 13.sp,
                color = if (isCur) Accent else MaterialTheme.colorScheme.onBackground,
                fontWeight = if (isCur) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (t.group.ifEmpty { "" }) + " · " + Fmt.size(t.size) + " · " + t.ext.uppercase(),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // pos/total 双时长（对齐 Web .t-dur）
        Row(verticalAlignment = Alignment.Bottom) {
            if (posSec > 2) {
                Text(Fmt.time(posSec.toLong()), fontSize = 10.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                Text(" / ", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("-", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 倍速文案：两位精度去尾零（对齐 Web fmtRate）。 */
private fun fmtRate(r: Float): String =
    String.format("%.2f", r).trimEnd('0').let { if (it.endsWith(".")) it + "0" else it }.let { if (it == "1.") "1.0" else it }

@Composable
private fun PlayerBar(
    vm: MainViewModel,
    showRatePop: Boolean, showSleepPop: Boolean,
    onToggleRate: () -> Unit,
    onToggleSleep: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            val dur = vm.durationMs()
            val cur = vm.positionMs()
            var drag by remember { mutableStateOf<Float?>(null) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.time(cur / 1000), fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp))
                Slider(
                    value = when {
                        drag != null -> drag!!
                        dur > 0 -> cur.toFloat() / dur
                        else -> 0f
                    },
                    onValueChange = { drag = it },
                    onValueChangeFinished = {
                        drag?.let { vm.seekTo(it) }
                        drag = null
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp).height(24.dp),
                )
                Text(Fmt.time(dur / 1000), fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp))
            }

            // 主控制：上一首 / -15s / 播放 / +15s / 下一首（对齐 Web .ctrls）
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleBtnIcon({ PrevIcon() }, Modifier.weight(1f)) { vm.step(-1) }
                CircleBtn("↺15", Modifier.weight(1f)) { vm.seekBy(-15) }
                Box(Modifier.weight(1.3f), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(Accent)
                            .clickable { vm.togglePlay() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (vm.playing) PauseIcon() else PlayIcon()
                    }
                }
                CircleBtn("15↻", Modifier.weight(1f)) { vm.seekBy(15) }
                CircleBtnIcon({ NextIcon() }, Modifier.weight(1f)) { vm.step(1) }
            }

            // chips：模式 / 倍速 / 字幕 / 定时（对齐 Web .ctrls2）
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip(MODE_LABEL[vm.mode] ?: "顺序", active = false) { vm.cycleMode() }
                Spacer(Modifier.weight(1f))
                Chip(fmtRate(vm.rate) + "x", active = showRatePop, onClick = onToggleRate)
                Spacer(Modifier.weight(1f))
                val cur2 = vm.ui.items.firstOrNull { it.id == vm.curId }
                val hasSub = cur2?.sub != null
                Chip(
                    if (hasSub) (if (vm.subOn) "字幕开" else "字幕关") else "字幕",
                    active = hasSub && vm.subOn,
                ) {
                    when (vm.toggleSub()) {
                        1 -> vm.toast("当前音频没有字幕")
                        2 -> { /* 无权限：已弹引导窗，不切换状态 */ }
                    }
                }
                Spacer(Modifier.weight(1f))
                Chip(
                    if (vm.sleepLeft > 0) ((vm.sleepLeft / 60) + 1).toString() + "分" else "定时",
                    active = vm.sleepLeft > 0,
                    onClick = onToggleSleep,
                )
            }
        }
    }
}


@Composable
private fun CircleBtnIcon(icon: @Composable () -> Unit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

@Composable
private fun CircleBtn(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) Accent else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Toast（对齐 Web #toast）。 */
@Composable
private fun ToastView(msg: String, onTimeout: () -> Unit) {
    LaunchedEffect(msg) {
        delay(1800)
        onTimeout()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .padding(bottom = 130.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xE01E1E24))
                .padding(horizontal = 16.dp, vertical = 7.dp),
        ) {
            Text(msg, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 倍速弹层（对齐 Web .pop：预设网格 + ±0.1 微调）。 */
@Composable
private fun RatePopup(vm: MainViewModel, onClose: () -> Unit) {
    PopupSurface(onClose) {
        Text(
            buildAnnotatedStringRate(vm.rate),
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
        )
        // 预设 5 列
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0 until 2) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (col in 0 until 5) {
                    val idx = row * 5 + col
                    if (idx < PRESETS.size) {
                        val p = PRESETS[idx]
                        PresetBtn(fmtRate(p), on = kotlin.math.abs(p - vm.rate) < 0.001f) {
                            vm.applyRate(p)
                            onClose()
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FineBtn("−") { vm.applyRate(vm.rate - 0.1f) }
            Text(fmtRate(vm.rate) + "x", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Accent,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            FineBtn("＋") { vm.applyRate(vm.rate + 0.1f) }
        }
        Text(
            "0.1 ~ 4.0，步进 0.1",
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 5.dp).align(Alignment.CenterHorizontally),
        )
    }
        }
}

private fun buildAnnotatedStringRate(rate: Float): String = "播放速度 " + fmtRate(rate) + "x"

@Composable
private fun PopupSurface(onClose: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onClose() },
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.padding(bottom = 120.dp, start = 30.dp, end = 30.dp),
        ) {
            Column(Modifier.padding(12.dp), content = content)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PresetBtn(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) Accent else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FineBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 38.dp, height = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 定时弹层（对齐 Web sleep-pop：预设 + 自定义分钟）。 */
@Composable
private fun SleepPopup(vm: MainViewModel, onClose: () -> Unit) {
    var custom by remember { mutableStateOf("") }
    val leftMin: Int = if (vm.sleepLeft > 0) ((vm.sleepLeft / 60) + 1).toInt() else 0
    PopupSurface(onClose) {
        Text(
            "定时停止 " + (if (leftMin > 0) leftMin.toString() + "分" else "关"),
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0 until 2) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val rowVals = if (row == 0) SLEEPS else listOf(0)
                for (m in rowVals) {
                    val label = if (m == 0) "关" else m.toString() + "分"
                    PresetBtn(label, on = (leftMin == m) && (m != 0 || vm.sleepLeft == 0L)) {
                        if (m == 0) vm.cancelSleep() else vm.setSleep(m)
                        onClose()
                    }
                }
            }
        }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it.filter { ch -> ch.isDigit() }.take(3) },
                modifier = Modifier.weight(1f).height(52.dp),
                placeholder = { Text("自定义分钟", fontSize = 11.sp) },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AccentBrush)
                    .clickable {
                        val v = custom.toIntOrNull()
                        if (v != null && v in 1..720) {
                            vm.setSleep(v)
                            onClose()
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text("设定", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        Text(
            "1 ~ 720 分钟",
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 5.dp).align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun ImportDialog(vm: MainViewModel, onPickZip: () -> Unit, onClose: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var sub by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("从 zip 导入", fontSize = 15.sp) },
        text = {
            Column {
                TextLink("选择本机 zip 文件…", onClick = {
                    onClose()
                    onPickZip()
                })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://…/xxx.zip 下载链接", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = sub,
                    onValueChange = { sub = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("子目录（可选）", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "解压到当前音频目录，保留目录结构；同名同大小自动跳过；zip 内字幕(.srt/.vtt)一并导入。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.importZip(url, sub)
                onClose()
            }) { Text("开始导入", fontSize = 13.sp) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消", fontSize = 13.sp) } },
    )
}

@Composable
private fun ImportProgressDialog(st: ImportState.Running) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(st.phase + "中…", fontSize = 15.sp) },
        text = {
            Column {
                LinearProgressIndicator(
                    progress = { st.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(st.detail, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun MigrateResultDialog(res: MigrateResult, onClose: () -> Unit) {
    val msg = buildString {
        append("已移动 ")
        append(res.moved)
        append(" 个文件，跳过 ")
        append(res.skipped)
        append(" 个（新目录已存在同名同大小）")
        if (res.errors.isNotEmpty()) {
            append("\n\n失败 ")
            append(res.errors.size)
            append(" 项：")
            append(res.errors.take(3).joinToString("\n"))
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("目录迁移完成", fontSize = 15.sp) },
        text = { Text(msg, fontSize = 12.sp) },
        confirmButton = { TextButton(onClick = onClose) { Text("好") } },
    )
}

@Composable
private fun DirDialog(vm: MainViewModel, onPickDir: () -> Unit, onClose: () -> Unit) {
    val ui = vm.ui
    var path by remember { mutableStateOf(ui.dir) }
    var migrate by remember { mutableStateOf(true) }
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("确认清空目录？", fontSize = 15.sp, color = Color(0xFFE5484D)) },
            text = {
                Text(
                    "将删除当前音频目录内的全部文件和子目录：\n" + ui.dir,
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAudioDir { _ -> }
                    confirmClear = false
                    onClose()
                }) { Text("清空", fontSize = 13.sp, color = Color(0xFFE5484D)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消", fontSize = 13.sp) } },
        )
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("音频目录", fontSize = 15.sp) },
        text = {
            Column {
                Text(
                    "当前：" + ui.dir + if (ui.customDir) "（自定义）" else "（默认）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                TextLink("浏览系统目录…", onClick = {
                    onClose()
                    onPickDir()
                })
                Spacer(Modifier.height(4.dp))
                Text(
                    "🗑 清空当前目录所有文件",
                    fontSize = 12.sp,
                    color = Color(0xFFE5484D),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { confirmClear = true }
                        .padding(horizontal = 4.dp, vertical = 5.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("/storage/emulated/0/Music/kcourse", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = migrate, onCheckedChange = { migrate = it })
                    Text("把旧目录的音频自动迁移过来", fontSize = 12.sp)
                }
                Text(
                    "选私有目录以外的文件夹需授予「所有文件访问」权限；迁移保留子目录结构。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.changeDir(path.trim(), migrate) { _ -> }
                onClose()
            }) { Text("保存", fontSize = 13.sp) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消", fontSize = 13.sp) } },
    )
}

/** 矢量播放/暂停/前后曲图标（避免 emoji 字体渲染出黄色方块）。 */
@Composable
private fun PlayIcon(scale: Float = 1f, color: Color = Color.White) {
    androidx.compose.foundation.Canvas(Modifier.size((22 * scale).dp)) {
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.30f, h * 0.18f)
            lineTo(w * 0.88f, h * 0.50f)
            lineTo(w * 0.30f, h * 0.82f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun PauseIcon(scale: Float = 1f, color: Color = Color.White) {
    androidx.compose.foundation.Canvas(Modifier.size((22 * scale).dp)) {
        val w = size.width
        val h = size.height
        val bar = w * 0.16f
        drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.30f, h * 0.20f),
            size = androidx.compose.ui.geometry.Size(bar, h * 0.60f))
        drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.58f, h * 0.20f),
            size = androidx.compose.ui.geometry.Size(bar, h * 0.60f))
    }
}

@Composable
private fun PrevIcon(color: Color = androidx.compose.ui.graphics.Color(0xFF444444)) {
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        // 竖条 + 左向三角
        drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.25f),
            size = androidx.compose.ui.geometry.Size(w * 0.10f, h * 0.50f))
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.82f, h * 0.25f)
            lineTo(w * 0.38f, h * 0.50f)
            lineTo(w * 0.82f, h * 0.75f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun NextIcon(color: Color = androidx.compose.ui.graphics.Color(0xFF444444)) {
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.25f),
            size = androidx.compose.ui.geometry.Size(w * 0.10f, h * 0.50f))
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.18f, h * 0.25f)
            lineTo(w * 0.62f, h * 0.50f)
            lineTo(w * 0.18f, h * 0.75f)
            close()
        }
        drawPath(path, color)
    }
}
