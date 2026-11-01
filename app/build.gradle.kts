import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "io.github.casimirkwon.smsforwarder"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.casimirkwon.smsforwarder"
        // SDK 기준: minSdk 32 / targetSdk 36
        minSdk = 32
        targetSdk = 36
        versionCode = 5
        versionName = "1.1.0"
    }

    // 릴리스 서명: 키스토어와 비밀번호는 저장소 밖(~/.config/sms-forwarder/)에 둔다. 서명 설정 파일은 기본 keystore-2026.properties 이고,
    // 환경 변수 SMSFWD_KEYSTORE_PROPS 나 gradle 속성 -PkeystoreProps=<파일> 로 다른 파일을 고를 수 있다. 파일이 있을 때만 서명 설정을 읽는다
    // (없으면 서명 없는 release APK 가 만들어진다). 키 파일·비밀번호는 저장소에 넣지 않는다(.gitignore 에도 제외).
    val keystoreProps = Properties().apply {
        val chosen = (project.findProperty("keystoreProps") as String?) ?: System.getenv("SMSFWD_KEYSTORE_PROPS")
        val f = if (chosen != null) File(chosen) else File(System.getProperty("user.home"), ".config/sms-forwarder/keystore-2026.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }
    val hasReleaseKey = keystoreProps.getProperty("storeFile")?.let { File(it).isFile } == true
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = File(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: 코드·리소스 축소. keep 규칙은 proguard-rules.pro (Room·WorkManager·Compose 는 라이브러리 consumer 규칙이 처리)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    // assembleRelease 가 돌리는 lintVital 은 lint 실행 모듈(lint-gradle)을 받아야 해서 오프라인 빌드가 안 된다. 릴리스 빌드 시 자동 lint 는 끈다
    // (lint 가 필요하면 ./gradlew lint 를 따로 실행).
    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")

    val room = "2.7.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    // 내보내기/가져오기 시험이 실제 JSON 을 읽고 쓰도록(안드로이드 스텁 대신)
    testImplementation("org.json:json:20240303")
}
