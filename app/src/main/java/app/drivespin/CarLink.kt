package app.drivespin

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import java.util.Locale

private const val PLEOS_NAVI = "ai.umos.maps.android.navigation.app"

/**
 * 차와 연결되는 곳 전부. 차가 아닌 곳(일반 에뮬레이터)에서는 각 기능이 조용히 실패하고 기본값을 쓴다.
 * - 주행가능거리·주행 규제: 안드로이드 차량 표준(CarPropertyManager·CarUxRestrictionsManager) — Pleos 문서 권장
 * - 탑승 인원: Pleos Vehicle SDK(Seat)
 * - 차 위치·길안내·도착 예상 배터리·도착 알림·충전소 화면: Pleos NaviHelper
 * - 음성 안내: Pleos TextToSpeech(ON_DEVICE — 앱 등록 없이, 인터넷 없이)
 * - 음성 일기: Pleos SpeechToText(ON_DEVICE — 같은 조건). 글자로만 받고 소리 파일은 남기지 않는다
 */
class CarLink(private val context: Context) {
    private val tag = "CarLink"
    private val car: Car? = runCatching { Car.createCar(context) }.onFailure { Log.w(tag, "car", it) }.getOrNull()
    private val vehicle = Vehicle(context)
    private val navi = NaviHelper(context)
    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    private val stt = SpeechToText(context.applicationContext, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)

    var location: Pair<Double, Double>? = null
        private set
    var onLocation: (Pair<Double, Double>) -> Unit = {}

    /** 안내가 시작되면 내비가 계산한 "도착했을 때 남는 배터리(연료)" */
    var onArriveCharge: (Int) -> Unit = {}
    /** 목적지 도착 */
    var onArrived: (String) -> Unit = {}

    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            location = (currentLocationInfo.latitude to currentLocationInfo.longitude).also(onLocation)
        }
        // 경로 계산이 끝나야 도착 예상 배터리가 나온다
        override fun onRoutingCompleted() {
            runCatching { navi.getRouteStateInfo() }
        }
        // 에뮬레이터에선 isRouting이 늘 false라 값만 본다. 값은 %가 아니라 Wh(74385 = 77.4kWh 배터리의 96%)
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            val raw = routeStateInfo.remainDestinationChargingInt
            val cap = batteryCapacityWh()
            Log.i(tag, "route routing=${routeStateInfo.isRouting} arrive=$raw cap=$cap")
            if (raw <= 0) return // 경로 계산 전엔 0이 먼저 온다 — 진짜 값은 몇 초 뒤
            val pct = when { raw in 0..100 -> raw; cap != null && cap > 0 -> (raw / cap * 100).toInt(); else -> return }
            onArriveCharge(pct.coerceIn(0, 100))
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            onArrived(destinationArrivedInfo.name)
        }
    }

    // 말이 끝나야 마이크를 연다(안 그러면 차 목소리를 받아 적는다)
    private var afterSpeak: (() -> Unit)? = null
    private fun spoke() { afterSpeak?.also { afterSpeak = null }?.invoke() }
    private val ttsListener = object : EventListener {
        override fun onDone() = spoke()
        override fun onError(errorType: ai.pleos.playground.tts.constant.ErrorType) = spoke()
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onStop() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var heard: ((String?) -> Unit)? = null
    private var lastHeard = ""
    private fun hear(t: String?) { heard?.also { heard = null }?.invoke(t?.trim()?.ifEmpty { null }) }
    private val sttListener = object : ResultListener {
        override fun onUpdated(stt: String, completed: Boolean) { lastHeard = stt; if (completed) hear(stt) }
        // 끝 신호가 마지막 결과보다 먼저 온다(에뮬레이터 확인) → 잠깐 기다렸다가, 그래도 없으면 들은 데까지 남긴다
        override fun onEndedRecognition() { main.postDelayed({ hear(lastHeard) }, 800) }
        override fun onError(errorType: ai.pleos.playground.stt.constant.ErrorType) { Log.w(tag, "stt $errorType"); hear(null) }
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }

    fun start(onDriving: (Boolean) -> Unit) {
        runCatching { vehicle.initialize() }.onFailure { Log.w(tag, "vehicle", it) }
        runCatching { navi.initialize(); navi.addListener(naviListener); navi.getCurrentLocationInfo() }.onFailure { Log.w(tag, "navi", it) }
        runCatching { tts.initialize(); tts.setLocale(Locale.KOREA); tts.addEventListener(ttsListener) }.onFailure { Log.w(tag, "tts", it) }
        runCatching { stt.initialize(); stt.addListener(sttListener); stt.setLocale(Locale.KOREA) }.onFailure { Log.w(tag, "stt", it) }
        runCatching {
            val ux = car!!.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            onDriving(ux.currentCarUxRestrictions.isRequiresDistractionOptimization)
            ux.registerListener { onDriving(it.isRequiresDistractionOptimization) }
        }.onFailure { Log.w(tag, "ux", it) }
    }

    /** 남은 주행거리(km). 못 읽으면 null. */
    fun rangeKm(): Double? = runCatching {
        val props = car!!.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        props.getFloatProperty(VehiclePropertyIds.RANGE_REMAINING, 0) / 1000.0
    }.onFailure { Log.w(tag, "range", it) }.getOrNull()

    /** 남은 주행거리가 바뀌면(충전·주행) km로 알려 준다 */
    fun onRange(cb: (Double) -> Unit) = runCatching {
        val props = car!!.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        props.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: android.car.hardware.CarPropertyValue<*>) { (v.value as? Float)?.let { cb(it / 1000.0) } }
            override fun onErrorEvent(propId: Int, zone: Int) {}
        }, VehiclePropertyIds.RANGE_REMAINING, CarPropertyManager.SENSOR_RATE_ONCHANGE)
    }.onFailure { Log.w(tag, "range cb", it) }

    /** 전기차 배터리 전체 용량(Wh). 못 읽으면 null. */
    fun batteryCapacityWh(): Float? = runCatching {
        val props = car!!.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        props.getFloatProperty(VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY, 0)
    }.onFailure { Log.w(tag, "capacity", it) }.getOrNull()

    /** 뒷좌석에 사람이 있나. 결과는 콜백으로(SDK가 비동기). 두 자리를 한 번에 물으면 차가 거절해서 한 자리씩 묻는다.
     *  (표준 SEAT_OCCUPANCY는 제조사용 권한 CONTROL_CAR_SEATS가 있어야 해서 일반 앱은 못 읽는다) */
    var rearChanged: (Boolean?) -> Unit = {} // 시험 신호(디버그 빌드)로 뒷좌석이 바뀐 척할 때도 이 길로
    fun rearOccupied(done: (Boolean?) -> Unit) {
        rearChanged = done
        listOf(SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT).forEach { seat ->
            runCatching {
                vehicle.getSeat().getCurrentSeatOccupancy(SeatArea(seat),
                    onComplete = { if (it == true) done(true) }, onFailed = { e -> Log.w(tag, "rear $seat: $e") })
            }
        }
    }

    fun speak(text: String) = runCatching { Log.i(tag, "speak $text"); tts.speak(text) }.onFailure { Log.w(tag, "speak", it) }  // 문장을 기록에도(시연 영상 음성 재현용)

    /** prompt를 말한 뒤 차 마이크로 한마디 듣고 글자로 돌려준다(못 들으면 null). 말이 끊기면 엔진이 알아서 멈춘다.
     *  pcm(16kHz·16bit·모노)을 주면 마이크 대신 그 소리를 넣는다 — 마이크 없는 에뮬레이터 시험용 */
    fun listen(prompt: String?, pcm: ByteArray? = null, done: (String?) -> Unit) {
        val go = {
            heard = done; lastHeard = ""
            runCatching { if (pcm != null) { stt.sendAudio(pcm); stt.completeAudioSend() } else stt.request() }
                .onFailure { Log.w(tag, "listen", it); hear(null) }
            Unit
        }
        if (prompt == null) go() else { afterSpeak = go; speak(prompt) }
    }

    /** 길안내 시작 후 내비 화면을 앞으로. 경로가 잡히면 도착 예상 배터리를 물어본다. */
    fun navigate(p: Pick) = runCatching {
        navi.requestRoute(RouteInfo(latitude = p.f.lat, longitude = p.f.lng, poiName = p.f.title, poiId = "", address = p.f.addr, poiSubId = ""))
        navi.requestNavigationScreen(NavigationScreenRequest.Foreground)
        // 에뮬레이터 내비는 위 요청을 받고도 뒤에 머문다(안드로이드 14 백그라운드 실행 제한) → 앞에 있는 우리가 직접 띄운다
        context.packageManager.getLaunchIntentForPackage(PLEOS_NAVI)?.let { context.startActivity(it) }
    }.onFailure { Log.w(tag, "navigate", it) }.isSuccess

    /** 차 내비의 "주변 충전소" 화면 */
    fun showChargers() = runCatching { navi.requestNavigationScreen(NavigationScreenRequest.NearbyChargingStations) }
        .onFailure { Log.w(tag, "chargers", it) }.isSuccess

    fun stop() {
        runCatching { navi.removeListener(naviListener); navi.release() }
        runCatching { tts.removeEventListener(ttsListener); tts.release() }
        runCatching { stt.removeListener(sttListener); stt.release() }
        runCatching { vehicle.release() }
        runCatching { car?.disconnect() }
    }
}
