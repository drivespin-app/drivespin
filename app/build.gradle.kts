import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.drivespin"
    compileSdk = 35
    useLibrary("android.car") // 주행 규제(CarUxRestrictionsManager)

    defaultConfig {
        applicationId = "app.drivespin"
        minSdk = 28 // Pleos 필수(차량 보안)
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    // release 서명: 키는 저장소 밖(~/.android-keys/drivespin.properties). 없으면 서명 없이 만든다
    val keyFile = file(System.getProperty("user.home") + "/.android-keys/drivespin.properties")
    if (keyFile.exists()) {
        val k = Properties().apply { keyFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(k.getProperty("storeFile")); storePassword = k.getProperty("storePassword")
            keyAlias = k.getProperty("keyAlias"); keyPassword = k.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("com.google.zxing:core:3.5.3") // 폰 사진 넣기 QR

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // 단위 시험에선 안드로이드 org.json이 없어서
}
