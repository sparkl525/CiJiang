plugins {
    id("com.android.application")
}

dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // 拉丁文字专用识别器（v3.3）：纯英文/菜单/路牌比中文模型准，和中文模型双跑取置信度高的一份
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // 纯逻辑单元测试（Leitner / Quiz / Lrc）：这些类不碰 Android 类型，直接跑在 JVM 上，
    // 不需要手机也不需要模拟器。
    testImplementation("junit:junit:4.13.2")
}

android {
    namespace = "com.jianyi.dict"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jianyi.dict"
        minSdk = 24
        targetSdk = 34
        versionCode = 66
        versionName = "3.16.1"
        // 只保留现代安卓手机的 64 位 ARM 架构，砍掉 x86 等无用架构（省 ~50MB）
        ndk { abiFilters.add("arm64-v8a") }
    }

    signingConfigs {
        // 与调试版同签名：老用户可以直接覆盖安装，不用卸载
        create("shared") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // 个人项目无需发布前 lint 阻塞，缩短构建时间
        checkReleaseBuilds = false
        abortOnError = false
        // 这应用只做中文界面，字符串按设计直接写在布局/代码里，不打算做多语言。
        // HardcodedText + SetTextI18n 两条一共 73 条告警，会把真正的技术问题埋掉，
        // 所以显式关掉并把理由写在这儿：哪天真要国际化，删掉这一行剩下的就全回来了。
        disable += setOf("HardcodedText", "SetTextI18n")
    }

    androidResources {
        // 关键：APK 内大于 1MB 的资源一旦被压缩，运行时将无法打开。
        // 词典 gzip 包（约 21MB）必须以"存储"方式进 APK。
        // 注意扩展名不能用 .gz —— aapt2 会自动解压 .gz 资源导致死局。
        noCompress.add("dbz")
        // 听力音频同理：MediaPlayer 需要能直接 seek 的文件描述符，压缩了就打不开
        noCompress.add("mp3")
        // 消除音效：SoundPool 走 AssetFileDescriptor，压缩了同样打不开
        noCompress.add("wav")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
