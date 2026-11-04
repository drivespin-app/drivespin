package app.drivespin

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.*

// 축제 서버(한국관광공사 TourAPI 축제를 모아 둔 곳)에서 오늘 열리는 축제를 받는다.
const val FESTIVAL_URL = "https://drivespin.pages.dev/festivals"
const val PHOTO_CREDIT = "사진 출처: 한국관광공사" // 공공누리 1·3유형 모두 출처 표시가 조건

data class Festival(val title: String, val addr: String, val lat: Double, val lng: Double, val start: String, val end: String, val image: String = "", val id: String = "")

data class Pick(val f: Festival, val km: Double) {
    /** 왕복이 남은 주행거리의 70%를 넘으면 빠듯 — 돌아올 때 충전을 권한다 */
    fun tight(rangeKm: Double) = km * 2 > rangeKm * 0.7
}

fun parseFestivals(json: String): List<Festival> {
    val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
    return (0 until items.length()).mapNotNull { i ->
        val o = items.getJSONObject(i)
        val lat = o.optString("mapy").toDoubleOrNull() ?: return@mapNotNull null
        val lng = o.optString("mapx").toDoubleOrNull() ?: return@mapNotNull null
        Festival(o.optString("title"), o.optString("addr1"), lat, lng, o.optString("eventstartdate"), o.optString("eventenddate"),
            o.optString("image"), o.optString("contentid"))
    }
}

fun fetchFestivals(): List<Festival> = parseFestivals(URL(FESTIVAL_URL).readText())

// 폰 사진 우편함(같은 서버의 /photo): 차가 우편함을 열고 QR로 주소를 띄우면 폰이 그 주소에서 사진을 올리고, 차가 한 장씩 가져온다.
// 가져간 사진은 서버에서 바로 지워지고, 안 가져가도 1시간 뒤 지워진다. 주소의 32자리 무작위 번호가 열쇠.
val PHOTO_URL = FESTIVAL_URL.replace("/festivals", "/photo")
fun newBoxCode(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
private fun <T> post(url: String, read: HttpURLConnection.() -> T): T = (URL(url).openConnection() as HttpURLConnection).run {
    requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 20000
    try { outputStream.close(); read() } finally { disconnect() }
}
fun openBox(code: String, base: String = PHOTO_URL): Boolean = post("$base/$code/open") { responseCode == 200 }
/** 우편함에서 사진 한 장(JPEG). 비었으면 null */
fun takePhoto(code: String, base: String = PHOTO_URL): ByteArray? = post("$base/$code/take") { if (responseCode == 200) inputStream.readBytes() else null }
/** 발자취 7번 칸: 폰에서 받은 사진 파일 경로들(쉼표로 이어 씀) */
fun ownPhotos(line: String): List<String> = line.split('\t').getOrElse(7) { "" }.split(',').filter { it.isNotEmpty() }

fun kstToday(): String = LocalDate.now(ZoneId.of("Asia/Seoul")).toString().replace("-", "")

// 직선거리(km). 실제 도로는 더 길어서 roadFactor로 보정한다.
fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}

/**
 * 돌림판 칸: 오늘 진행 중이고, 남은 주행거리로 "갔다가 돌아올 수 있는" 축제만.
 * family(뒷좌석 탑승)면 아이와 가기 편한 가까운 곳(편도 40km) 위주 — 4곳이 안 되면 그냥 전체에서.
 * ponytail: 도로거리 = 직선 × 1.3 어림. 정확히 하려면 NaviHelper 경로 거리로 바꿀 것.
 */
fun pickSlots(all: List<Festival>, lat: Double, lng: Double, rangeKm: Double, today: String = kstToday(), max: Int = 8, family: Boolean = false): List<Pick> {
    val roadFactor = 1.3
    val ok = all.asSequence()
        .filter { it.start <= today && today <= it.end }
        .map { Pick(it, haversineKm(lat, lng, it.lat, it.lng) * roadFactor) }
        .filter { it.km * 2 <= rangeKm } // 왕복
        .toList()
    val near = ok.filter { it.km <= 40 }
    return (if (family && near.size >= 4) near else ok).asSequence()
        .sortedBy { it.km }
        .take(max * 2)
        .shuffled()
        .take(max)
        .toList()
}

/** 발자취("날짜\t이름\t위도\t경도\t사진\t누구와") 중 지금 위치에서 km 안쪽인 줄, 최근 것부터. 좌표 없는 옛 도장은 건너뛴다. */
fun nearStamps(stamps: List<String>, lat: Double, lng: Double, km: Double = 1.0): List<String> = stamps.filter { s ->
    val p = s.split('\t')
    val la = p.getOrNull(2)?.toDoubleOrNull() ?: return@filter false
    val ln = p.getOrNull(3)?.toDoubleOrNull() ?: return@filter false
    haversineKm(lat, lng, la, ln) <= km
}.reversed()

/** 한마디를 이어 붙일 오늘 발자취: 맨 끝이 오늘 것이고 지금 자리 1km 안일 때만(다른 곳에서 남긴 말이 아침 자리에 붙지 않게) */
fun todayStampHere(stamps: List<String>, lat: Double, lng: Double, today: String = kstToday()): String? =
    stamps.lastOrNull()?.takeIf { it.startsWith(today) && nearStamps(listOf(it), lat, lng).isNotEmpty() }

// "20261030" → "10월 30일"
fun dayText(d: String) = if (d.length == 8) "${d.substring(4, 6).toInt()}월 ${d.substring(6).toInt()}일" else d

/** 1년이 넘으면 "3년 전", 아니면 "10월 30일" — 오래된 추억일수록 세월을 말해 주는 게 와닿는다 */
// 발자취 지도: 한국 윤곽(res/raw/kmap.txt, viewBox 99 -11 195 259 — 본토·제주·울릉)에 실제 좌표를 찍는다.
// 본토는 위경도 1도 ≈ 48단위로 맞췄고(해안 도시로 확인), 그림에서 크게 그린 제주·울릉은 그 섬 그림 안으로 옮긴다
const val MAP_X = 99f; const val MAP_Y = -11f; const val MAP_W = 195f; const val MAP_H = 259f
fun mapXY(lat: Double, lng: Double): Pair<Float, Float> = when {
    lat < 33.7 -> (128.8 + (lng - 126.16) / 0.81 * 57.9).coerceIn(130.0, 185.0).toFloat() to (209.7 + (33.57 - lat) / 0.38 * 27.4).coerceIn(211.0, 236.0).toFloat()
    lng > 130.5 -> 285f to 55f
    else -> (112.3 + (lng - 126.1) * 48.3).toFloat() to (-3.3 + (38.61 - lat) * 48.1).toFloat()
}

/** 이름 + '랑': 받침 있으면 '이랑'(서준이랑), 없으면 '랑'(지우랑). 한글이 아니면 '하고' */
fun withRang(name: String): String = name.lastOrNull()?.takeIf { it in '가'..'힣' }
    ?.let { if ((it - '가') % 28 != 0) "${name}이랑" else "${name}랑" } ?: "${name}하고"

/** 소리 내어 읽는 순서 말: 첫 번째·두 번째…열 번째, 그 위는 숫자(음성이 '1번째'를 '한번째'로 읽음) */
fun nthText(n: Int): String = listOf("첫", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열").getOrNull(n - 1)?.let { "$it 번째" } ?: "${n}번째"

fun agoText(d: String, today: String = kstToday()): String {
    if (d.length != 8 || today.length != 8) return d
    val years = today.take(4).toInt() - d.take(4).toInt() - if (today.drop(4) < d.drop(4)) 1 else 0
    return if (years >= 1) "${years}년 전" else dayText(d)
}
