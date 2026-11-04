package app.drivespin

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// 차가 아닌 곳에서 돌릴 때 쓰는 값(서울시청, 120km)
private const val DEMO_LAT = 37.5663
private const val DEMO_LNG = 126.9779
private const val DEMO_RANGE_KM = 120.0

// 글자 대비 4.5:1 이상(Pleos 디자인 가이드) — 낮 색은 계산해서 골랐다
// 색: Pleos 화면처럼 낮엔 밝은 회색 바탕·검은 글씨, 밤엔 어둡게. 강조색(주황)은 돌림판 버튼·당첨 칸에만
private class Pal(val bg: Color, val panel: Color, val segA: Color, val segB: Color, val line: Color,
                  val ink: Color, val sub: Color, val accent: Color, val onAccent: Color, val ok: Color, val warn: Color)
private val Day = Pal(Color(0xFFF3F4F6), Color.White, Color.White, Color(0xFFEEF0F3), Color(0xFFD9DCE2),
    Color(0xFF111318), Color(0xFF666C78), Color(0xFFB8470A), Color.White, Color(0xFF117A47), Color(0xFF9A5A00))
private val Night = Pal(Color(0xFF0B0D11), Color(0xFF171A21), Color(0xFF1B1F27), Color(0xFF232832), Color(0xFF353B48),
    Color(0xFFF2F3F5), Color(0xFF9AA1AE), Color(0xFFFF7A2F), Color(0xFF111318), Color(0xFF3DDC97), Color(0xFFFFC145))
// ponytail: 낮·밤이 바뀌면 안드로이드가 화면을 새로 만들어서 전역 하나로 충분하다
private var pal = Day
private val Bg get() = pal.bg
private val Panel get() = pal.panel
private val SegA get() = pal.segA
private val SegB get() = pal.segB
private val Line get() = pal.line
private val Ink get() = pal.ink
private val Sub get() = pal.sub
private val Accent get() = pal.accent
private val OnAccent get() = pal.onAccent
private val Ok get() = pal.ok
private val Warn get() = pal.warn

// 1km 안쪽은 "약 0km" 대신 "1km 이내"
private fun kmText(km: Double, unit: String = "km") = if (km < 1) "1${unit} 이내" else "약 ${km.toInt()}$unit"

// 발자취(다녀온 축제 도장): "날짜\t이름\t위도\t경도\t사진\t누구와\t남긴 말\t폰 사진들" 줄 목록. 차 안에 두고, 바뀔 때마다 기록 금고(Vault.kt)에 잠가 맡긴다.
// 차는 여럿이 같이 타서 다녀온 곳이 남으면 곤란할 수 있다 → 기본은 꺼짐, 켠 사람만 기록, 남기기 싫은 곳은 골라서 지운다
private var demoPick: String? = null // 시험 신호(디버그 빌드만): 돌림판이 멈출 축제 이름 일부
private fun stampPrefs(c: Context) = c.getSharedPreferences("stamps", 0)
private fun kidList(c: Context) = stampPrefs(c).getString("kids", "")!!.split(',').filter { it.isNotEmpty() }
private fun saveKids(c: Context, k: List<String>) = stampPrefs(c).edit().putString("kids", k.joinToString(",")).apply()
// 축제 길이 아닐 때 남긴 한마디는 그 자리에 이 이름으로 남는다
private const val ROAD = "지나던 길"
private fun loadStamps(c: Context) = stampPrefs(c).getString("list", "")!!.lines().filter { it.isNotBlank() }
// 괄호 속 부제는 소리 내면 길고 어색하다
private fun spoken(t: String) = t.replace(Regex("\\s*\\(.*?\\)"), "")

// 사진: 축제 사진은 인터넷 주소, 폰에서 받은 사진은 차 안 파일 경로("/"로 시작)
// ponytail: 축제 사진은 볼 때마다 인터넷에서 받는다. 터널·산속 대비가 필요하면 도장 찍을 때 파일로 저장할 것
private suspend fun loadImage(url: String): ImageBitmap? = if (url.isEmpty()) null else runCatching {
    withContext(Dispatchers.IO) {
        if (url.startsWith("/")) BitmapFactory.decodeFile(url).asImageBitmap()
        else URL(url).openStream().use { BitmapFactory.decodeStream(it).asImageBitmap() }
    }
}.getOrNull()

private fun saveStamps(c: Context, list: List<String>) = list.also { stampPrefs(c).edit().putString("list", it.joinToString("\n")).apply(); backupLater(c, it) }
private fun photoDir(c: Context) = java.io.File(c.filesDir, "photos")
// 기록이 바뀔 때마다 금고에 맡긴다. 한 줄로 차례대로 — 인터넷이 끊겨 실패하면 다음 변경이나 다음 실행 때 다시
private val vaultQ = java.util.concurrent.Executors.newSingleThreadExecutor()
private fun backupLater(c: Context, list: List<String>) {
    val app = c.applicationContext
    val k = stampPrefs(app).getString("key", null) ?: return
    val text = list.joinToString("\n")
    vaultQ.execute {
        if (runCatching { backup(k, text, photoDir(app)) }.getOrDefault(false)) stampPrefs(app).edit().putLong("savedAt", System.currentTimeMillis()).apply()
    }
}
// 도착 뒤 고른 "누구와"(5번 칸)·남긴 말(6번 칸)·폰에서 받은 사진(7번 칸)을 그 발자취에 넣는다.
// 줄은 날짜+이름으로 찾고, 새 값은 저장된 지금 값에서 만든다(다른 칸이 먼저 바뀌어도)
private fun setField(c: Context, line: String, i: Int, v: (String) -> String) = saveStamps(c, loadStamps(c).map {
    if (it.split('\t').take(2) != line.split('\t').take(2)) it
    else it.split('\t').toMutableList().apply { while (size <= i) add(""); this[i] = v(this[i]) }.joinToString("\t")
})
private fun note(line: String) = line.split('\t').getOrElse(6) { "" }
// 한마디를 여러 번 남기면 이어 붙인다
private fun plusNote(old: String, new: String) = listOf(old, new).filter { it.isNotEmpty() }.joinToString(" / ")

private fun addStamp(c: Context, f: Festival, who: String, note: String = ""): List<String> {
    val list = loadStamps(c)
    if (list.any { it.split('\t').take(2) == listOf(kstToday(), f.title) }) return list
    return saveStamps(c, list + "${kstToday()}\t${f.title}\t${f.lat}\t${f.lng}\t${f.image}\t$who\t$note")
}

// 시험용(디버그 빌드만): 앱 files/say.pcm이 있으면 마이크 대신 그 소리로 듣는다(pc_emu.sh say)
private fun demoPcm(c: Context) = if (c.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) null
    else java.io.File(c.filesDir, "say.pcm").takeIf { it.exists() }?.readBytes()

class MainActivity : ComponentActivity() {
    private lateinit var carLink: CarLink

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf("android.car.permission.CAR_ENERGY"), 1)
        carLink = CarLink(this)
        val driving = mutableStateOf(false)
        // 시험용(디버그 빌드만): am start --ez demoDrive true — 에뮬레이터는 개발 앱을 주행 중 차단 화면으로 덮어서 주행 화면을 못 본다
        val demo = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && intent.getBooleanExtra("demoDrive", false)
        carLink.start { driving.value = it || demo }
        if (demo) driving.value = true
        setContent {
            val night = androidx.compose.foundation.isSystemInDarkTheme()
            pal = if (night) Night else Day
            MaterialTheme(colorScheme = if (night) darkColorScheme() else lightColorScheme()) { DriveSpinScreen(carLink, driving.value) }
        }
    }

    // 시험용(디버그 빌드만): 에뮬레이터는 차가 지도 위를 움직이지 않아 도착 신호를 흉내 낸다
    // am start -f 0x20000000 -n app.drivespin/.MainActivity --ez demoArrive true
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (intent.getBooleanExtra("demoArrive", false)) carLink.onArrived("")
        demoPick = intent.getStringExtra("demoPick")
        // --ez demoRear true|false: 뒷좌석에 사람이 탄 척/내린 척(에뮬레이터 좌석 값은 늘 '탑승'이라)
        if (intent.hasExtra("demoRear")) carLink.rearChanged(intent.getBooleanExtra("demoRear", false))
        // --ez demoNear true: 마지막 발자취 자리로 차가 온 척
        if (intent.getBooleanExtra("demoNear", false)) loadStamps(this).lastOrNull()?.split('\t')?.let { p ->
            val la = p.getOrNull(2)?.toDoubleOrNull(); val ln = p.getOrNull(3)?.toDoubleOrNull()
            if (la != null && ln != null) carLink.onLocation(la + 0.003 to ln)
        }
    }

    override fun onDestroy() {
        carLink.stop()
        super.onDestroy()
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DriveSpinScreen(car: CarLink, driving: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }
    var all by remember { mutableStateOf<List<Festival>?>(null) }
    var range by remember { mutableStateOf<Double?>(null) }
    var here by remember { mutableStateOf(DEMO_LAT to DEMO_LNG) }
    // 가족 모드: 뒷좌석에 사람이 탔다고 아이와 가는 건 아니다 → 가족(아이)을 등록해 둔 차에서 뒷좌석 탑승이 감지되면 누구랑 가는지 묻고, 아이를 고르면 켠다
    var kids by remember { mutableStateOf(kidList(ctx)) }
    var kid by remember { mutableStateOf<String?>(null) } // 오늘 같이 가는 아이
    val family = kid != null
    var rearIn by remember { mutableStateOf(false) }
    var askKid by remember { mutableStateOf(false) }
    var kidsOpen by remember { mutableStateOf(false) } // 가족 등록 창
    var slots by remember { mutableStateOf<List<Pick>>(emptyList()) }
    var result by remember { mutableStateOf<Pick?>(null) }
    var target by remember { mutableStateOf<Pick?>(null) } // 안내 중인 곳
    var arriveCharge by remember { mutableStateOf<Int?>(null) }
    var spinning by remember { mutableStateOf(false) }
    var footOn by remember { mutableStateOf(stampPrefs(ctx).getBoolean("on", false)) }
    var stamps by remember { mutableStateOf(loadStamps(ctx)) }
    var listOpen by remember { mutableStateOf(false) }
    val told = remember { mutableSetOf<String>() } // 이번에 이미 알려 준 발자취
    var askWho by remember { mutableStateOf<String?>(null) } // 방금 찍은 발자취: 누구와 왔나
    var memOpen by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var tripNote by remember { mutableStateOf("") } // 가는 길에 남긴 말: 도착하면 그 발자취에 붙는다
    var photoFor by remember { mutableStateOf<String?>(null) } // 폰 사진을 붙일 발자취(QR 창)
    val todayStamp = stamps.lastOrNull()?.takeIf { it.startsWith(kstToday()) }
    var keyOpen by remember { mutableStateOf(false) } // 기록 열쇠 보기
    var restoreOpen by remember { mutableStateOf(false) } // 예전 차 기록 가져오기
    var mapOpen by remember { mutableStateOf(false) } // 발자취 지도(해마다 어디를 다녀왔나)
    var popFor by remember { mutableStateOf<String?>(null) } // 우리가 찍은 사진이 있는 옛 발자취: 차가 서면 사진을 크게 띄운다
    // 근처에 예전에 왔던 곳이 있으면 추억 카드(최근 방문부터)
    val memories = if (footOn) nearStamps(stamps, here.first, here.second) else emptyList()
    val angle = remember { Animatable(0f) }
    val r = range ?: DEMO_RANGE_KM
    val kidsNow by rememberUpdatedState(kids)
    val drivingNow by rememberUpdatedState(driving) // 한 번만 걸어 두는 차 신호 콜백 안에서도 지금 주행 상태를 보게

    LaunchedEffect(Unit) {
        range = car.rangeKm()
        car.onRange { km -> main.post { if (km > 0) range = km } }
        car.location?.let { here = it }
        car.onLocation = { main.post { here = it } }
        car.rearOccupied { rear -> main.post {
            val now = rear == true
            if (now && !rearIn && kidsNow.isNotEmpty() && !drivingNow) {
                askKid = true
                car.speak("뒷자리에 누가 탔네요. 오늘 " + (kidsNow.singleOrNull()?.let { withRang(it) } ?: "아이랑") + " 가요?")
            }
            if (!now) { kid = null; askKid = false } // 뒷좌석이 비면 가족 모드도 끈다
            rearIn = now
        } }
        // 열쇠가 생기기 전부터 발자취를 켜 둔 차: 열쇠를 만들고 한 번 보여 준다
        if (footOn && stampPrefs(ctx).getString("key", null) == null) { stampPrefs(ctx).edit().putString("key", newVaultKey()).apply(); keyOpen = true }
        backupLater(ctx, stamps) // 지난번에 못 맡긴 게 있으면
        all = runCatching { withContext(Dispatchers.IO) { fetchFestivals() } }.getOrElse { emptyList() }
    }
    // 축제·위치·주행거리·가족 모드가 바뀌면 칸을 다시 고른다(돌리는 중엔 그대로)
    LaunchedEffect(all, family, range, here) {
        if (!spinning) all?.let { slots = pickSlots(it, here.first, here.second, r, family = family) }
    }
    // 음성 일기: 가는 길(도착 전)에 남긴 말은 모아 뒀다가 도착 때 붙이고, 도착 뒤엔 오늘 발자취에 바로 붙인다.
    // 축제 길이 아니어도 남긴다 — 예전에 왔던 곳 근처면 그곳에, 아니면 지금 자리에 오늘 날짜로 새 발자취(다음에 지나가면 읽어 줌)
    fun record(prompt: String?) {
        if (listening) return
        listening = true
        car.listen(prompt, demoPcm(ctx)) { t -> main.post {
            listening = false
            val n = t?.replace(Regex("\\s+"), " ") ?: return@post
            val line = todayStampHere(stamps, here.first, here.second)
            when {
                target != null -> tripNote = plusNote(tripNote, n)
                line != null -> { stamps = setField(ctx, line, 6) { plusNote(it, n) }; told += stamps }
                else -> {
                    val p = nearStamps(stamps, here.first, here.second).firstOrNull()?.split('\t')
                    val f = if (p != null) Festival(p[1], "", p[2].toDouble(), p[3].toDouble(), "", "", p.getOrElse(4) { "" })
                        else Festival(ROAD, "", here.first, here.second, "", "")
                    stamps = addStamp(ctx, f, if (family) "아이와" else "", n); told += stamps
                }
            }
            car.speak("남겼어요.")
        } }
    }

    DisposableEffect(Unit) {
        car.onArriveCharge = { pct ->
            main.post {
                if (target != null && arriveCharge == null) {
                    arriveCharge = pct
                    // 돌아올 몫까지 생각하면 도착 때 절반은 남아야 한다
                    // 주행 중엔 충전소 버튼이 숨어 있다 → 누르라고 하지 않고 차가 서면 보인다고 말한다
                    if (pct < 50) car.speak("도착하면 배터리가 ${pct}퍼센트 남아요. 돌아올 땐 충전이 필요해요. " +
                        if (drivingNow) "차를 세우면 충전소 버튼이 보여요." else "충전소 버튼을 눌러 보세요.")
                }
            }
        }
        car.onArrived = {
            main.post {
                target?.let { t ->
                    if (footOn) {
                        stamps = addStamp(ctx, t.f, if (family) "아이와" else "", tripNote)
                        tripNote = ""
                        askWho = stamps.last()
                        told += stamps.last() // 방금 도착한 곳을 "예전에 왔던 곳"이라 하지 않게
                        record("${spoken(t.f.title)} 도착! 발자취 남겼어요. ${nthText(stamps.count { it.split('\t')[1] != ROAD })} 축제예요. 오는 길 어땠어요? 한마디 남겨 주세요.")
                    } else car.speak("${spoken(t.f.title)} 도착! 즐거운 시간 보내세요.")
                    target = null
                }
            }
        }
        onDispose { car.onArriveCharge = {}; car.onArrived = {} }
    }

    // 예전에 왔던 곳 근처를 지나면 한 번 말해 준다
    LaunchedEffect(memories) {
        val m = memories.firstOrNull { it !in told } ?: return@LaunchedEffect
        told += memories
        val p = m.split('\t')
        val who = p.getOrElse(5) { "" }.let { if (it.isEmpty()) "" else "$it " }
        val more = if (memories.size > 1) " 여기 ${memories.size}번 왔어요." else ""
        val said = note(m).let { if (it.isEmpty()) "" else " 그때 남긴 말, ${it.replace(" / ", ". ")}." }
        val place = if (p[1] == ROAD) "지나던 길이에요" else "왔던 ${spoken(p[1])} 근처예요"
        car.speak("여기는 ${agoText(p[0])} $who$place.$said$more" + if (driving) " 차가 서면 사진 보여 드릴게요." else "")
        memOpen = true
        popFor = memories.firstOrNull { ownPhotos(it).isNotEmpty() }
    }

    fun go(p: Pick) {
        target = p; arriveCharge = null
        car.navigate(p)
    }

    fun spin() {
        if (spinning || slots.isEmpty()) return
        spinning = true; result = null; memOpen = false
        // 시험(디버그 빌드): --es demoPick 이름 → 그 이름이 든 칸에 멈춤(시연 녹화용)
        val idx = demoPick?.let { q -> slots.indexOfFirst { q in it.f.title } }?.takeIf { it >= 0 } ?: Random.nextInt(slots.size)
        val seg = 360f / slots.size
        // 바늘은 12시. idx번 칸의 가운데가 12시에 오도록 돌린다
        val to = angle.value - angle.value % 360f + 360f * 5 + (360f - (idx * seg + seg / 2))
        scope.launch {
            angle.animateTo(to, tween(4200, easing = CubicBezierEasing(0.1f, 0.7f, 0.1f, 1f)))
            val p = slots[idx]
            result = p; spinning = false
            val who = kid?.let { "${withRang(it)} 가기 좋은 거리예요. " } ?: ""
            val charge = if (p.tight(r)) "왕복은 빠듯해서 돌아올 때 충전이 필요해요. " else ""
            car.speak("${spoken(p.f.title)}, ${kmText(p.km, "킬로미터")}. $who$charge" + if (driving) "바로 안내할게요." else "안내 버튼을 눌러 주세요.")
            if (driving) go(p) // 주행 중엔 손 안 대고 바로 안내
        }
    }

    Row(Modifier.fillMaxSize().background(Bg).padding(horizontal = 40.dp, vertical = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        // 왼쪽(운전석 쪽): 돌림판. 가운데 버튼을 누르면 돈다
        Box(Modifier.weight(1.05f).aspectRatio(1f), contentAlignment = Alignment.Center) {
            Wheel(slots.map { it.f.title }, angle.value, showText = !driving, spinning = spinning,
                won = result?.let { slots.indexOf(it) } ?: -1)
            Box(Modifier.fillMaxSize(0.26f).clip(CircleShape)
                .background(if (slots.isEmpty()) Line else Accent)
                .clickable(enabled = !spinning && slots.isNotEmpty(), onClick = ::spin),
                contentAlignment = Alignment.Center) {
                Text(if (spinning) "…" else "돌려", fontSize = 44.sp, lineHeight = 64.sp, fontWeight = FontWeight.Black, color = OnAccent)
            }
        }
        Spacer(Modifier.width(48.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("드라이브 돌림판", fontSize = 26.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = Accent)
            Text("오늘 어디 갈까?", fontSize = 52.sp, lineHeight = 75.sp, fontWeight = FontWeight.Black, color = Ink)
            // 꼬리표가 많으면 다음 줄로(한 꼬리표가 세로로 꺾이지 않게)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("주행가능 ${r.toInt()}km" + if (range == null) " (예시)" else "")
                Chip(if (all == null) "축제 불러오는 중" else "다녀올 수 있는 축제 ${slots.size}곳")
                kid?.let { Chip("가족 모드 · ${it}", Ok, onClick = { askKid = true }) }
                memories.firstOrNull()?.let { Chip("${agoText(it.split('\t')[0])}에 왔던 곳", Accent, onClick = { memOpen = true }) }
            }
            // 주행 중엔 사진 금지(규제) → 추억 카드는 차가 섰을 때만
            if (askKid && !driving) KidCard(kids) { k -> kid = k; askKid = false }
            else if (askWho != null && !driving) WhoCard { who ->
                if (who != null) { stamps = setField(ctx, askWho!!, 5) { who }; told += stamps }
                askWho = null
            }
            else if (memOpen && memories.isNotEmpty() && !driving) MemoryCard(memories) { memOpen = false }
            else ResultCard(result, r, driving, arriveCharge, onGo = ::go, onCharge = { car.showChargers() })
            // 운전 중에도 한 번 누르고 말만 하면 된다(읽을 것 없음)
            if (footOn) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip(if (listening) "듣고 있어요… 말씀하세요" else "한마디 남기기" + (if (tripNote.isNotEmpty()) " · 가는 길 1개" else ""), Accent,
                    onClick = { record(null) })
                // 축제에서 찍은 사진을 오늘 발자취에: 차로 돌아와 앉았을 때
                if (!driving && todayStamp != null) Chip("폰 사진 넣기" + ownPhotos(todayStamp).size.let { if (it > 0) " · ${it}장" else "" }, Accent,
                    onClick = { photoFor = todayStamp })
            }
            if (!driving) Footprints(footOn, stamps, kids, onKids = { kidsOpen = true }, onList = { listOpen = true }, onMap = { mapOpen = true }, onRestore = { restoreOpen = true }) {
                footOn = !footOn; stampPrefs(ctx).edit().putBoolean("on", footOn).apply()
                // 처음 켜면 기록 열쇠를 만들고 한 번 보여 준다(폰으로 찍어 두게)
                if (footOn && stampPrefs(ctx).getString("key", null) == null) {
                    stampPrefs(ctx).edit().putString("key", newVaultKey()).apply(); backupLater(ctx, stamps); keyOpen = true
                }
            }
        }
    }
    // 다녀온 곳 목록: 남기기 싫은 곳만 골라 지운다(최근 것부터)
    if (listOpen) PanelDialog("다녀온 곳 ${stamps.size}곳", "닫기", { listOpen = false }) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            stamps.reversed().forEach { m ->
                val p = m.split('\t')
                if (note(m).isNotEmpty()) Text("“${note(m)}”", fontSize = 26.sp, color = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val n = ownPhotos(m).size
                    Text("${agoText(p[0])} ${p.getOrElse(5) { "" }} · ${p[1]}".replace("  ", " ") + if (n > 0) " · 사진 ${n}장" else "", fontSize = 26.sp, lineHeight = 38.sp,
                        color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = { listOpen = false; photoFor = m }) { Text("사진 넣기", color = Ink, fontSize = 26.sp, lineHeight = 38.sp) }
                    TextButton(onClick = { ownPhotos(m).forEach { java.io.File(it).delete() }; stamps = saveStamps(ctx, stamps - m) }) {
                        Text("지우기", color = Warn, fontSize = 26.sp, lineHeight = 38.sp)
                    }
                }
            }
        }
        Row {
            if (stampPrefs(ctx).getString("key", null) != null) TextButton(onClick = { listOpen = false; keyOpen = true }) { Text("기록 열쇠 보기", color = Accent, fontSize = 26.sp, lineHeight = 38.sp) }
            TextButton(onClick = { listOpen = false; restoreOpen = true }) { Text("예전 차 기록 가져오기", color = Accent, fontSize = 26.sp, lineHeight = 38.sp) }
        }
    }
    if (!driving) popFor?.let { PhotoPop(it) { popFor = null } }
    if (mapOpen && !driving) MapDialog(stamps) { mapOpen = false }
    if (kidsOpen && !driving) KidsDialog(kids, onChange = { kids = it; saveKids(ctx, it); if (kid !in it) kid = null }) { kidsOpen = false }
    if (keyOpen && !driving) stampPrefs(ctx).getString("key", null)?.let { KeyDialog(it, stampPrefs(ctx).getLong("savedAt", 0)) { keyOpen = false } }
    if (restoreOpen && !driving) RestoreDialog(onDone = { stamps = it; told += it; footOn = true }) { restoreOpen = false }
    photoFor?.let { line ->
        if (!driving) PhotoDialog(line, onGot = { b ->
            val f = java.io.File(java.io.File(ctx.filesDir, "photos").apply { mkdirs() }, "${System.nanoTime()}.jpg").apply { writeBytes(b) }
            stamps = setField(ctx, line, 7) { (it.split(',').filter(String::isNotEmpty) + f.path).joinToString(",") }
            told += stamps // 줄이 바뀌어도 "예전에 왔던 곳"을 다시 말하지 않게
        }) { photoFor = null }
    }
}

// 글씨 크기: Pleos 가이드 보통 글씨 3mm 이상 → 이 화면(14.6", 1sp≈0.126mm)에서 26sp, 핵심 정보 4mm → 34sp 이상
@Composable
private fun Chip(text: String, color: Color = Sub, onClick: (() -> Unit)? = null) {
    Text(text, fontSize = 26.sp, lineHeight = 38.sp, color = color, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun ResultCard(p: Pick?, rangeKm: Double, driving: Boolean, arriveCharge: Int?, onGo: (Pick) -> Unit, onCharge: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Panel).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (p == null) {
            Text("가운데 버튼을 눌러 보세요", fontSize = 34.sp, lineHeight = 49.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("남은 주행거리로 다녀올 수 있는 오늘 축제만 올렸어요.", fontSize = 26.sp, lineHeight = 39.sp, color = Sub)
            return@Column
        }
        var img by remember(p) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(p) { img = loadImage(p.f.image) }
        if (!driving) img?.let { // 주행 중엔 사진·긴 글 숨김(규제)
            // 관광공사 사진은 공공누리 3유형(변경 금지)일 수 있어 자르지 않고 통째로, 출처를 함께 적는다
            Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(18.dp)).background(SegB))
            Text(PHOTO_CREDIT, fontSize = 18.sp, color = Sub)
        }
        Text(p.f.title, fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.Black, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val tight = p.tight(rangeKm)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(kmText(p.km), Ink)
            Chip(if (tight) "왕복 빠듯 · 충전 권장" else "왕복 여유", if (tight) Warn else Ok)
        }
        arriveCharge?.let { Text("도착하면 배터리 ${it}%", fontSize = 34.sp, lineHeight = 49.sp, fontWeight = FontWeight.Bold, color = if (it < 50) Warn else Ok) }
        if (!driving) {
            Text(p.f.addr, fontSize = 26.sp, color = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onGo(p) }, modifier = Modifier.weight(1f).height(76.dp), shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Panel)) {
                    Text("여기로 안내", fontSize = 30.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
                }
                if (tight || (arriveCharge ?: 100) < 50) OutlinedButton(onClick = onCharge, modifier = Modifier.weight(1f).height(76.dp),
                    shape = RoundedCornerShape(20.dp)) {
                    Text("주변 충전소", fontSize = 28.sp, lineHeight = 41.sp, color = Warn)
                }
            }
        }
    }
}

// 발자취 켜기/끄기(기본 꺼짐, 꺼도 기록은 남음) + 다녀온 곳 목록
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Footprints(on: Boolean, stamps: List<String>, kids: List<String>, onKids: () -> Unit, onList: () -> Unit, onMap: () -> Unit, onRestore: () -> Unit, onToggle: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Chip(if (on) "발자취 남기기 · 켜짐" else "발자취 남기기 · 꺼짐", if (on) Accent else Sub, onClick = onToggle)
        Chip(if (kids.isEmpty()) "가족 등록" else "가족 · " + kids.joinToString("·"), Ink, onClick = onKids)
        if (stamps.isNotEmpty()) {
            Chip("다녀온 곳 ${stamps.size}곳 · 보기·지우기", Ink, onClick = onList)
            Chip("발자취 지도", Ink, onClick = onMap)
        } else Chip("예전 차 기록 가져오기", Ink, onClick = onRestore) // 새 차
    }
}

// 뒷좌석 탑승이 감지되면: 오늘 누구랑 가요? 아이를 고르면 가족 모드(가까운 곳 위주)
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun KidCard(kids: List<String>, onPick: (String?) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Panel).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("뒷자리에 누가 탔네요", fontSize = 34.sp, lineHeight = 49.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("아이랑 가면 가기 편한 가까운 곳 위주로 골라 드려요.", fontSize = 26.sp, lineHeight = 38.sp, color = Sub)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            kids.forEach { Chip(withRang(it), Accent, onClick = { onPick(it) }) }
            Chip("아이 없이", Ink, onClick = { onPick(null) })
        }
    }
}

// 가족 등록: 같이 다니는 아이 이름(부르는 이름)만 차 안에 둔다
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun KidsDialog(kids: List<String>, onChange: (List<String>) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    PanelDialog("가족 등록", "닫기", onClose) {
        Text("같이 다니는 아이 이름을 넣어 두면, 뒷자리에 누가 탔을 때 아이랑 가는지 물어보고 가까운 곳 위주로 골라 드려요. 이름은 이 차에만 저장돼요.",
            fontSize = 26.sp, lineHeight = 38.sp, color = Sub)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            kids.forEach { k -> Chip("$k  ✕", Ink, onClick = { onChange(kids - k) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it.take(10) }, Modifier.weight(1f), singleLine = true, placeholder = { Text("아이 이름 (예: 서준)", fontSize = 26.sp, lineHeight = 38.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 30.sp, color = Ink))
            Button(onClick = { name.trim().takeIf { it.isNotEmpty() && it !in kids }?.let { onChange(kids + it) }; name = "" },
                enabled = name.isNotBlank() && kids.size < 5, modifier = Modifier.height(72.dp)) { Text("추가", fontSize = 28.sp, lineHeight = 41.sp) }
        }
    }
}

// 발자취 지도: 해마다 어디를 다녀왔는지. 연도를 고르면 그해 다녀온 곳이 지도에 톡 떠오르고 옆에 목록이 나온다
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MapDialog(stamps: List<String>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val land = remember { ctx.resources.openRawResource(R.raw.kmap).bufferedReader().readLines().filter { it.isNotBlank() }
        .map { androidx.compose.ui.graphics.vector.PathParser().parsePathString(it).toPath() } }
    val years = remember(stamps) { stamps.map { it.take(4) }.distinct().sortedDescending() }
    var year by remember { mutableStateOf(years.firstOrNull()) } // null = 전체
    val shown = stamps.filter { s -> year.let { it == null || s.startsWith(it) } }
    val pop = remember(year) { Animatable(0f) }
    LaunchedEffect(year) { pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 300f)) }
    val landFill = Sub.copy(alpha = 0.12f); val landLine = Sub.copy(alpha = 0.5f); val dim = Sub.copy(alpha = 0.35f); val on = Accent; val ring = Panel
    PanelDialog("발자취 지도", "닫기", onClose, maxWidth = 1500.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Canvas(Modifier.size(482.dp, 640.dp)) {
                val k = size.height / MAP_H
                translate(-MAP_X * k, -MAP_Y * k) { scale(k, k, pivot = Offset.Zero) {
                    land.forEach { drawPath(it, landFill); drawPath(it, landLine, style = Stroke(1.2f / k)) }
                } }
                fun at(m: String): Offset? { val p = m.split('\t'); val la = p.getOrNull(2)?.toDoubleOrNull(); val ln = p.getOrNull(3)?.toDoubleOrNull()
                    return if (la == null || ln == null) null else mapXY(la, ln).let { (x, y) -> Offset((x - MAP_X) * k, (y - MAP_Y) * k) } }
                stamps.filter { it !in shown }.forEach { m -> at(m)?.let { drawCircle(dim, 5.dp.toPx(), it) } }
                shown.forEach { m -> at(m)?.let {
                    val r = (if (m.split('\t')[1] == ROAD) 6.dp else 10.dp).toPx() * pop.value
                    drawCircle(ring, r + 3.dp.toPx(), it); drawCircle(on, r, it)
                } }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    years.forEach { y -> Chip("${y}년", if (y == year) Accent else Sub, onClick = { year = y }) }
                    if (years.size > 1) Chip("전체", if (year == null) Accent else Sub, onClick = { year = null })
                }
                Text("${year?.let { "${it}년" } ?: "모든 해"} · ${shown.count { it.split('\t')[1] != ROAD }}곳 다녀왔어요",
                    fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Ink)
                shown.sortedByDescending { it.take(8) }.take(5).forEach { m -> // 최근 것부터(새 차로 옮긴 기록은 순서가 섞일 수 있음)
                    val p = m.split('\t'); val d = p[0]
                    Text("${if (year == null) d.take(4) + "년 " else ""}${d.substring(4, 6).toInt()}월 ${d.substring(6).toInt()}일 · ${p.getOrElse(5) { "" }} · ${p[1]}".replace(" ·  · ", " · "),
                        fontSize = 26.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (shown.size > 5) Text("그 밖에 ${shown.size - 5}곳", fontSize = 26.sp, lineHeight = 38.sp, color = Sub)
            }
        }
    }
}

// 예전에 왔던 곳에 차가 서면 그때 폰으로 찍은 사진이 화면 가운데로 툭 떠오른다. 여러 장이면 2.5초마다 넘어간다. 누르면 닫히고 추억 카드가 남는다
@Composable
private fun PhotoPop(m: String, onClose: () -> Unit) {
    val paths = remember(m) { ownPhotos(m) }
    var i by remember(m) { mutableIntStateOf(0) }
    var shown by remember(m) { mutableIntStateOf(0) } // 몇 번째 사진인지 글은 사진이 실제로 바뀔 때 함께 바꾼다
    var img by remember(m) { mutableStateOf<ImageBitmap?>(null) }
    val pop = remember { Animatable(0f) }
    LaunchedEffect(m, i) { // 지금 보이는 한 장만 읽는다(여러 장을 한꺼번에 들고 있지 않게)
        img = loadImage(paths[i]); shown = i
        if (pop.value < 1f) pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 260f))
        if (paths.size > 1) { kotlinx.coroutines.delay(2500); i = (i + 1) % paths.size }
    }
    val p = m.split('\t')
    if (img == null) return // 사진을 다 읽은 뒤에 창을 띄운다(그 전엔 빈 창이 비침)
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.graphicsLayer { scaleX = 0.6f + 0.4f * pop.value; scaleY = scaleX; alpha = pop.value.coerceIn(0f, 1f) }
            .clip(RoundedCornerShape(28.dp)).background(Panel).clickable(onClick = onClose).animateContentSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // 다음 장으로 넘어갈 때 겹쳐 바꾼다
            androidx.compose.animation.Crossfade(img, label = "photo") { b ->
                b?.let { Image(it, null, Modifier.height(720.dp).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Fit) }
            }
            Text("${agoText(p[0])} ${p.getOrElse(5) { "" }} · ${p[1]}".replace("  ", " ") + if (paths.size > 1) "  ${shown + 1} / ${paths.size}" else "",
                fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// 예전에 왔던 곳 근처: 그때 날짜·이름과 사진들
@Composable
private fun MemoryCard(memories: List<String>, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Panel).clickable(onClick = onClose).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("예전에 왔던 곳이에요", fontSize = 26.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = Accent)
        // 우리가 찍은 사진(폰에서 받은 것)을 먼저, 없으면 축제 홍보 사진. 둘 다 없으면(지나던 길) 빈 칸 대신 글만
        val pics = memories.take(3).flatMap { m -> ownPhotos(m).ifEmpty { listOf(m.split('\t').getOrElse(4) { "" }) } }.filter { it.isNotEmpty() }.take(3)
        if (pics.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pics.forEach { u ->
                var img by remember(u) { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(u) { img = loadImage(u) }
                Box(Modifier.weight(1f).height(240.dp).clip(RoundedCornerShape(18.dp)).background(SegB)) {
                    // 폰 사진은 세로가 많고 얼굴이 위쪽에 있다 → 가운데보다 조금 위를 기준으로 자른다. 관광공사 사진(주소)은 자르지 않는다
                    val ours = !u.startsWith("http")
                    img?.let { Image(it, null, contentScale = if (ours) ContentScale.Crop else ContentScale.Fit, alignment = androidx.compose.ui.BiasAlignment(0f, -0.3f), modifier = Modifier.fillMaxSize()) }
                }
            }
        }
        if (pics.any { it.startsWith("http") }) Text(PHOTO_CREDIT, fontSize = 18.sp, color = Sub)
        memories.take(3).forEach { m ->
            val p = m.split('\t')
            Text("${agoText(p[0])} ${p.getOrElse(5) { "" }} · ${p[1]}".replace("  ", " "), fontSize = 28.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note(m).isNotEmpty()) Text("“${note(m)}”", fontSize = 26.sp, lineHeight = 39.sp, color = Sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun Wheel(labels: List<String>, angle: Float, showText: Boolean, spinning: Boolean, won: Int) {
    Canvas(Modifier.fillMaxSize()) {
        val n = labels.size.coerceAtLeast(1)
        val seg = 360f / n
        val d = size.minDimension
        val rimW = d * 0.035f
        val wheelD = d - rimW * 2
        val topLeft = Offset((size.width - wheelD) / 2, (size.height - wheelD) / 2)
        // 바깥 테두리: 평소엔 옅은 선, 도는 동안만 강조색
        drawCircle(Panel, d / 2, center)
        drawCircle(if (spinning) Accent else Line, d / 2 - rimW / 4, center, style = Stroke(rimW / 2))
        rotate(angle) {
            for (i in 0 until n) {
                val c = if (i == won) Accent else if (i % 2 == 0) SegA else SegB
                drawArc(c, -90f + i * seg, seg, true, topLeft, Size(wheelD, wheelD))
                drawArc(Line, -90f + i * seg, seg, true, topLeft, Size(wheelD, wheelD), style = Stroke(2f))
            }
            if (showText && labels.isNotEmpty()) {
                val paint = android.graphics.Paint().apply { isAntiAlias = true; isFakeBoldText = true; textSize = d / 26f }
                val r0 = d * 0.15f // 가운데 버튼 바깥부터
                val maxL = wheelD / 2 - r0 - d * 0.05f
                labels.forEachIndexed { i, s ->
                    paint.textSize = d / 26f
                    val w = paint.measureText(s)
                    if (w > maxL) paint.textSize = (paint.textSize * maxL / w).coerceAtLeast(d / 36f)
                    var t = s
                    while (t.length > 1 && paint.measureText(t) > maxL) t = t.dropLast(2) + "…"
                    paint.color = (if (i == won) OnAccent else Ink).toArgb()
                    // 칸 가운데 선을 따라 쓴다. 왼쪽 절반은 뒤집어서 글자가 거꾸로 서지 않게
                    val a = i * seg + seg / 2 - 90f
                    val left = cos(Math.toRadians((a + angle).toDouble())) < -0.05 // 맨 아래 칸(0 근처)은 맨 위 칸과 같은 방향으로
                    rotate(if (left) a + 180f else a) {
                        val x = if (left) center.x - r0 - paint.measureText(t) else center.x + r0
                        drawContext.canvas.nativeCanvas.drawText(t, x, center.y + paint.textSize / 3, paint)
                    }
                }
            }
        }
        // 12시 바늘
        val path = Path().apply {
            moveTo(center.x - d * 0.045f, 0f); lineTo(center.x + d * 0.045f, 0f); lineTo(center.x, d * 0.09f); close()
        }
        drawPath(path, Ink)
    }
}

// 도착 뒤 차가 서 있을 때 한 번: 누구와 왔나(나중에 "3년 전 아이와 왔던 곳"으로 말해 준다)
@Composable
private fun WhoCard(onPick: (String?) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Panel).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("누구와 왔나요?", fontSize = 34.sp, lineHeight = 49.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("다음에 이 근처를 지나면 같이 왔던 사람과 함께 알려 드려요.", fontSize = 26.sp, lineHeight = 38.sp, color = Sub)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("아이와", "가족과", "부모님과", "친구와", "혼자").forEach { Chip(it, Ink, onClick = { onPick(it) }) }
        }
        Chip("건너뛰기", onClick = { onPick(null) })
    }
}

// 폰 사진 넣기: 차 화면 QR을 폰 카메라로 찍으면 사진 고르는 웹 화면이 열린다(폰에 앱 설치 없음).
// 올린 사진은 차가 2초마다 한 장씩 받아 이 발자취에 붙인다. 창을 닫거나 차가 출발하면 그만 받는다
@Composable
private fun PhotoDialog(line: String, onGot: (ByteArray) -> Unit, onClose: () -> Unit) {
    val code = remember { newBoxCode() }
    var opened by remember { mutableStateOf<Boolean?>(null) }
    var got by remember { mutableIntStateOf(0) }
    val shots = remember { mutableStateListOf<ImageBitmap>() } // 받은 사진을 바로 창에 띄운다(작게 줄여서, 새것이 앞)
    LaunchedEffect(code) {
        opened = withContext(Dispatchers.IO) { runCatching { openBox(code) }.getOrDefault(false) }
        while (opened == true) {
            val b = withContext(Dispatchers.IO) { runCatching { takePhoto(code) }.getOrNull() }
            if (b != null) {
                onGot(b); got++
                withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = 4 }) }
                    ?.let { shots.add(0, it.asImageBitmap()) }
            } else kotlinx.coroutines.delay(2000)
        }
    }
    val p = line.split('\t')
    PanelDialog("폰 사진 넣기 · ${agoText(p[0])} ${p[1]}", if (got > 0) "다 넣었어요" else "닫기", onClose) {
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                if (opened == true) Qr("$PHOTO_URL/$code", Modifier.size(300.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (shots.isEmpty()) Text("폰 카메라로 QR을 찍으면 사진 고르는 화면이 열려요. 고른 사진이 이 발자취에 붙고, 다음에 이 근처를 지나면 추억 카드에 나와요.",
                        fontSize = 28.sp, lineHeight = 42.sp, color = Ink)
                    else Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        // 한 장이면 사진 전체를, 여러 장이면 같은 칸에 맞춰 잘라 두 장 + 나머지 장수
                        if (shots.size == 1) Image(shots[0], null, Modifier.height(300.dp).widthIn(max = 280.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Fit)
                        else shots.take(2).forEach { Image(it, null, Modifier.size(200.dp, 300.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop) }
                        if (got > 2) Box(Modifier.size(110.dp, 300.dp).clip(RoundedCornerShape(16.dp)).background(SegB), contentAlignment = Alignment.Center) {
                            Text("+${got - 2}", fontSize = 40.sp, lineHeight = 58.sp, fontWeight = FontWeight.Bold, color = Ink)
                        }
                    }
                    Text(when {
                        opened == null -> "준비 중…"
                        opened == false -> "사진 서버에 연결하지 못했어요. 인터넷 연결을 확인해 주세요."
                        got > 0 -> "사진 ${got}장 받았어요"
                        else -> "폰에서 보내기를 기다리는 중…"
                    }, fontSize = 34.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, color = if (got > 0) Ok else if (opened == false) Warn else Accent)
                    Text("받은 사진은 기록 열쇠로 잠가 보관돼서 차를 바꿔도 남아요.", fontSize = 26.sp, lineHeight = 38.sp, color = Sub)
                }
            }
    }
}

// 기본 알림 창은 폭이 560dp로 묶여 QR 옆 글이 눌린다 → 창에 직접 그린다
@Composable
private fun PanelDialog(title: String, close: String, onClose: () -> Unit, maxWidth: androidx.compose.ui.unit.Dp = 1000.dp, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = maxWidth).clip(RoundedCornerShape(28.dp)).background(Panel).padding(36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            content()
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text(close, color = Ink, fontSize = 28.sp, lineHeight = 41.sp) }
        }
    }
}

// 기록 열쇠: 새 차에서 넣으면 발자취·한마디·사진이 돌아온다. 폰으로 찍어 두라고 QR(열쇠 글자)도 함께
@Composable
private fun KeyDialog(key: String, savedAt: Long, onClose: () -> Unit) {
    PanelDialog("기록 열쇠", "찍어 뒀어요", onClose) {
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Qr(keyText(key), Modifier.size(240.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(keyText(key), fontSize = 44.sp, lineHeight = 64.sp, fontWeight = FontWeight.Black, color = Ink, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Text("차를 바꾸면 새 차의 드라이브 돌림판에서 '예전 차 기록 가져오기'에 이 열쇠를 넣으세요. 발자취·한마디·사진이 그대로 돌아와요.",
                    fontSize = 28.sp, lineHeight = 42.sp, color = Ink)
                Text("지금 폰으로 이 화면을 찍어 두세요. 서버엔 이 열쇠로 잠근 채로만 있어서 열쇠를 잃으면 누구도 열 수 없어요.",
                    fontSize = 26.sp, lineHeight = 38.sp, color = Warn)
                Text(if (savedAt > 0) "마지막 보관 " + java.text.SimpleDateFormat("M월 d일 HH:mm", java.util.Locale.KOREA).format(java.util.Date(savedAt))
                    else "인터넷이 연결되면 자동으로 보관해요", fontSize = 26.sp, color = Sub)
            }
        }
    }
}

// 새 차에서: 예전 차의 기록 열쇠를 넣으면 금고에서 받아 와 이 차 기록과 합친다(이후로는 그 열쇠로 계속 보관)
@Composable
private fun RestoreDialog(onDone: (List<String>) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    val k = cleanKey(typed)
    PanelDialog("예전 차 기록 가져오기", if (state.startsWith("가져왔")) "확인" else "닫기", onClose) {
        // Pleos 자판은 영문 요청을 무시하고 한글로 뜬다(에뮬레이터에서 확인) → 바꾸는 법을 적어 둔다
        Text("예전 차에서 찍어 둔 기록 열쇠 20자리를 넣어 주세요. 자판이 한글이면 왼쪽 아래 지구본 단추로 영문으로 바꿔요.", fontSize = 28.sp, lineHeight = 42.sp, color = Ink)
        // 열쇠는 영문·숫자뿐 → 영문 자판·대문자를 청한다(자판에 따라 무시됨)
        OutlinedTextField(typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 36.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = Ink),
            placeholder = { Text("XXXXX-XXXXX-XXXXX-XXXXX", fontSize = 36.sp, lineHeight = 52.sp, color = Sub) })
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                state = "가져오는 중…"
                scope.launch {
                    val got = withContext(Dispatchers.IO) { runCatching { restore(k, photoDir(ctx)) }.getOrNull() }
                    if (got == null) { state = "열쇠가 맞지 않거나 보관된 기록이 없어요."; return@launch }
                    stampPrefs(ctx).edit().putString("key", k).putBoolean("on", true).apply()
                    val merged = saveStamps(ctx, mergeStamps(loadStamps(ctx), got.lines().filter { it.isNotBlank() }))
                    state = "가져왔어요. 발자취 ${merged.size}곳"
                    onDone(merged)
                }
            }, enabled = k.length == 20 && state != "가져오는 중…", modifier = Modifier.height(76.dp), shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Panel)) { Text("가져오기", fontSize = 30.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold) }
            Text(state, fontSize = 30.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, color = if (state.startsWith("가져왔")) Ok else if (state.startsWith("열쇠")) Warn else Ink)
        }
    }
}

// QR은 낮·밤 상관없이 흰 바탕 검은 점(폰 카메라가 읽기 쉽게). 바깥 흰 여백(4칸)은 zxing이 넣어 준다
@Composable
private fun Qr(text: String, modifier: Modifier) {
    val m = remember(text) { com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0) }
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White)) {
        val cell = size.minDimension / m.width
        for (y in 0 until m.height) for (x in 0 until m.width)
            if (m[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}
