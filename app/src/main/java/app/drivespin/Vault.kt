package app.drivespin

import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// 기록 금고: 발자취·한마디·폰 사진을 서버에 자동으로 맡겨 차를 바꿔도 남게 한다.
// 서버엔 차에서 암호로 잠근 덩어리(AES-GCM)만 간다. 열쇠는 '기록 열쇠' 20자리 — 이 차와, 그걸 폰으로 찍어 둔 사람만 안다.
// 금고 번호도 열쇠를 한 번 더 감춘 값(SHA-256)이라 서버는 열쇠도 내용도 모른다. 새 차에서 열쇠를 넣으면 그대로 돌아온다.
// ponytail: 열쇠를 잃으면 찾을 길이 없다. 현대차 계정 연동이 열리면 열쇠 대신 계정으로 바꿀 것
val VAULT_URL = FESTIVAL_URL.replace("/festivals", "/vault")
private const val ABC = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 헷갈리는 0·O·1·I를 뺀 32자 → 20자리 = 100비트
fun newVaultKey(): String = SecureRandom().let { r -> (1..20).map { ABC[r.nextInt(32)] }.joinToString("") }
/** 사람이 친 열쇠: 소문자·띄어쓰기·대시는 무시 */
fun cleanKey(s: String) = s.uppercase().filter { it in ABC }
fun keyText(k: String) = k.chunked(5).joinToString("-")

private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
fun vaultId(k: String) = sha("drivespin-vault:$k").joinToString("") { "%02x".format(it) }
private fun gcm(mode: Int, k: String, iv: ByteArray) =
    Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(sha("drivespin-key:$k"), "AES"), GCMParameterSpec(128, iv)) }
fun seal(k: String, plain: ByteArray): ByteArray = ByteArray(12).also { SecureRandom().nextBytes(it) }.let { iv -> iv + gcm(Cipher.ENCRYPT_MODE, k, iv).doFinal(plain) }
fun unseal(k: String, b: ByteArray): ByteArray = gcm(Cipher.DECRYPT_MODE, k, b.copyOf(12)).doFinal(b, 12, b.size - 12)

private fun http(method: String, url: String, body: ByteArray? = null): Pair<Int, ByteArray?> =
    (URL(url).openConnection() as HttpURLConnection).run {
        requestMethod = method; connectTimeout = 8000; readTimeout = 30000
        try {
            if (body != null) { doOutput = true; outputStream.use { it.write(body) } }
            responseCode to (if (responseCode == 200) inputStream.readBytes() else null)
        } finally { disconnect() }
    }

/** 차 안 기록을 금고와 맞춘다: 발자취 목록은 통째로, 사진은 금고에 없는 것만 올리고, 차에서 지운 사진은 금고에서도 지운다 */
fun backup(k: String, stamps: String, photoDir: File, base: String = VAULT_URL): Boolean {
    val id = "$base/${vaultId(k)}"
    val (c, list) = http("GET", id)
    if (c != 200) return false
    val there = JSONArray(String(list!!)).let { a -> (0 until a.length()).map(a::getString) }.toSet()
    if (http("PUT", "$id/stamps", seal(k, stamps.toByteArray())).first != 200) return false
    val here = photoDir.listFiles().orEmpty().filter { it.name.endsWith(".jpg") }.associateBy { "p_" + it.name }
    for (n in here.keys - there) if (http("PUT", "$id/$n", seal(k, here.getValue(n).readBytes())).first != 200) return false
    for (n in there - here.keys - "stamps") http("DELETE", "$id/$n")
    return true
}

/** 새 차: 열쇠로 금고를 열어 사진을 이 차 폴더에 받고, 발자취 목록(사진 경로는 이 차 폴더로 바꿔서)을 돌려준다. 열쇠가 틀리거나 금고가 비면 null */
fun restore(k: String, photoDir: File, base: String = VAULT_URL): String? {
    val id = "$base/${vaultId(k)}"
    val (c, b) = http("GET", "$id/stamps")
    if (c != 200) return null
    val stamps = String(runCatching { unseal(k, b!!) }.getOrNull() ?: return null)
    photoDir.mkdirs()
    val (_, list) = http("GET", id)
    JSONArray(String(list ?: "[]".toByteArray())).let { a -> (0 until a.length()).map(a::getString) }.filter { it.startsWith("p_") }.forEach { n ->
        val f = File(photoDir, n.removePrefix("p_"))
        if (!f.exists()) http("GET", "$id/$n").second?.let { f.writeBytes(unseal(k, it)) }
    }
    return movePhotos(stamps, photoDir)
}

/** 발자취 7번 칸의 사진 경로를 이 차의 사진 폴더로(차마다 앱 폴더 경로가 다르다) */
fun movePhotos(stamps: String, photoDir: File): String = stamps.lines().joinToString("\n") { line ->
    val p = line.split('\t').toMutableList()
    if (p.size > 7) p[7] = ownPhotos(line).joinToString(",") { File(photoDir, File(it).name).path }
    p.joinToString("\t")
}

/** 이 차에 이미 있던 발자취와 금고에서 온 것을 합친다(같은 날짜·이름이면 금고 것) */
fun mergeStamps(local: List<String>, restored: List<String>): List<String> =
    (restored + local.filter { l -> restored.none { it.split('\t').take(2) == l.split('\t').take(2) } }).sortedBy { it.take(8) }
