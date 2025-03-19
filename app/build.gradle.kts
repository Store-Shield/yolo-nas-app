plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.quantiztest"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.quantiztest"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 최신 문법으로 변경된 부분
    packaging {
        resources {
            excludes.add("META-INF/DEPENDENCIES")
            excludes.add("META-INF/LICENSE")
            excludes.add("META-INF/LICENSE.txt")
            excludes.add("META-INF/license.txt")
            excludes.add("META-INF/NOTICE")
            excludes.add("META-INF/NOTICE.txt")
            excludes.add("META-INF/notice.txt")
            excludes.add("META-INF/ASL2.0")
            excludes.add("META-INF/*.kotlin_module")
        }
        resources.pickFirsts.add("**/*.so")
        jniLibs.useLegacyPackaging = true
    }

    // 최신 문법으로 변경된 부분
    androidResources {
        noCompress += "tflite"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    implementation ("com.squareup.okhttp3:okhttp:4.9.3")
    implementation ("io.socket:socket.io-client:2.0.0")

    // TensorFlow Lite 의존성 추가
    implementation("org.tensorflow:tensorflow-lite:2.12.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.3")
    implementation("org.tensorflow:tensorflow-lite-metadata:0.4.3")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.12.0") // GPU 가속 사용시
}