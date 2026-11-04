package app.drivespin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FestivalsTest {
    @Test fun 이름에_랑_붙이기() {
        assertEquals("서준이랑", withRang("서준")); assertEquals("지우랑", withRang("지우")); assertEquals("Amy하고", withRang("Amy"))
    }

    @Test fun 지도_좌표는_그림_안으로() {
        val (sx, sy) = mapXY(37.566, 126.978)  // 서울: 본토 그림(112~281, -3~205) 안
        assertTrue(sx in 112f..281f && sy in -3f..205f)
        val (jx, jy) = mapXY(33.38, 126.53)    // 제주: 제주 그림(129~187, 210~237) 안
        assertTrue(jx in 129f..187f && jy in 210f..237f)
        assertTrue(mapXY(35.18, 129.075).first > sx) // 부산은 서울보다 동쪽
    }

    @Test fun 순서는_첫_번째로_읽음() {
        assertEquals("첫 번째", nthText(1)); assertEquals("열 번째", nthText(10)); assertEquals("11번째", nthText(11))
    }

    private val json = """{"items":[
      {"title":"가까운축제","addr1":"서울","mapx":"126.98","mapy":"37.57","eventstartdate":"20261001","eventenddate":"20261130"},
      {"title":"끝난축제","addr1":"서울","mapx":"126.98","mapy":"37.57","eventstartdate":"20260901","eventenddate":"20261001"},
      {"title":"먼축제","addr1":"부산","mapx":"129.07","mapy":"35.18","eventstartdate":"20261001","eventenddate":"20261130"},
      {"title":"좌표없음","addr1":"?","mapx":"","mapy":"","eventstartdate":"20261001","eventenddate":"20261130"}]}"""

    @Test fun 오늘_진행중이고_왕복가능한_축제만() {
        val all = parseFestivals(json)
        assertEquals(3, all.size) // 좌표 없는 건 버림
        val slots = pickSlots(all, 37.5663, 126.9779, rangeKm = 120.0, today = "20261029")
        assertEquals(listOf("가까운축제"), slots.map { it.f.title })
        // 부산(직선 ~325km)은 1,000km면 왕복 가능
        assertEquals(2, pickSlots(all, 37.5663, 126.9779, rangeKm = 1000.0, today = "20261029").size)
        // 가족 모드: 가까운 곳이 4곳 안 되면 전체에서 고른다
        assertEquals(2, pickSlots(all, 37.5663, 126.9779, rangeKm = 1000.0, today = "20261029", family = true).size)
        // 빠듯: 왕복이 남은 거리의 70% 초과
        val busan = pickSlots(all, 37.5663, 126.9779, rangeKm = 1000.0, today = "20261029").first { it.f.title == "먼축제" }
        assertEquals(true, busan.tight(1000.0 * 0.9))
        assertEquals(false, busan.tight(2000.0))
    }

    @Test fun 발자취_근처만_찾기() {
        val stamps = listOf("20261012\t옛도장", "20261020\t시청앞\t37.5663\t126.9779", "20261030\t부산\t35.18\t129.07")
        val again = "20261029\t시청앞2\t37.5665\t126.9780\thttp://x/a.jpg"
        // 약 0.4km 안 두 번 — 최근 것부터
        assertEquals(listOf("시청앞2", "시청앞"), nearStamps(stamps + again, 37.5700, 126.9779).map { it.split('\t')[1] })
        assertEquals(emptyList<String>(), nearStamps(stamps, 37.60, 126.9779)) // 약 3.7km
    }

    @Test fun 한마디는_오늘_같은_자리에만_이어_붙임() {
        val morning = "20261102\t지나던 길\t37.5663\t126.9779"
        assertEquals(morning, todayStampHere(listOf(morning), 37.5670, 126.9779, today = "20261102")) // 약 0.1km
        assertEquals(null, todayStampHere(listOf(morning), 37.4979, 127.0276, today = "20261102"))   // 강남, 다른 자리 → 새 발자취
        assertEquals(null, todayStampHere(listOf(morning), 37.5663, 126.9779, today = "20261103"))   // 다음 날
        assertEquals(null, todayStampHere(listOf("20261102\t옛도장"), 37.5663, 126.9779, today = "20261102")) // 좌표 없음
    }

    @Test fun 오래된_추억은_몇년전() {
        assertEquals("10년 전", agoText("20161030", today = "20261030"))
        assertEquals("9년 전", agoText("20161031", today = "20261030")) // 아직 10년 안 됨
        assertEquals("3월 5일", agoText("20260305", today = "20261030"))
    }

    @Test fun 폰_사진은_7번_칸() {
        assertEquals(emptyList<String>(), ownPhotos("20261102\t축제\t37.5\t127.0\thttp://x/a.jpg\t가족과\t좋았다"))
        assertEquals(listOf("/d/1.jpg", "/d/2.jpg"), ownPhotos("20261102\t축제\t37.5\t127.0\t\t\t\t/d/1.jpg,/d/2.jpg"))
        assertTrue(newBoxCode().matches(Regex("[0-9a-f]{32}"))) // 서버가 받는 우편함 번호 꼴
    }

    @Test fun 기록_금고_잠그기() {
        val k = newVaultKey()
        assertEquals(20, cleanKey(keyText(k).lowercase().replace("-", " - ")).length) // 사람이 대충 쳐도
        assertEquals("안녕 발자취", String(unseal(k, seal(k, "안녕 발자취".toByteArray()))))
        assertTrue(runCatching { unseal(newVaultKey(), seal(k, byteArrayOf(1))) }.isFailure) // 다른 열쇠론 못 연다
        assertTrue(vaultId(k).matches(Regex("[0-9a-f]{64}")) && vaultId(k) != vaultId(newVaultKey()))
    }

    @Test fun 새_차로_옮기면_사진_경로를_바꾸고_합침() {
        val old = "20161030\t축제\t37.5\t127.0\t\t아이와\t솜사탕\t/data/user/0/a/files/photos/1.jpg,/data/user/0/a/files/photos/2.jpg"
        assertEquals("20161030\t축제\t37.5\t127.0\t\t아이와\t솜사탕\t/new/photos/1.jpg,/new/photos/2.jpg", movePhotos(old, java.io.File("/new/photos")))
        assertEquals("20161030\t옛도장", movePhotos("20161030\t옛도장", java.io.File("/new/photos")))
        val here = listOf("20261103\t지나던 길\t1\t2", "20161030\t축제\t37.5\t127.0")
        assertEquals(listOf(old, "20261103\t지나던 길\t1\t2"), mergeStamps(here, listOf(old)))
    }
}
