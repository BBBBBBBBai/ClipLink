import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 正式版签名材料。keystore.properties 与 .jks 都在 .gitignore/.zcodeignore 里排除，
// 仓库只留代码。文件缺失时 release 退化成未签名包，而不是让构建直接失败——
// 这样别人克隆下来仍然能编译，只是打不出可安装的正式包。
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.cliplink"
    // 36：焦点通知引用 Android 16 的 Live Updates API（BAKLAVA、canPostPromotedNotifications）
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cliplink"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // storeFile 的路径相对项目根目录解析（keystore.properties 里也写了这条约定）
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 刻意不开混淆：Shizuku 的 UserService 跑在独立进程里，靠类名反射拉起，
            // R8 一旦改名就会在真机上才暴露，而本机没有设备可验证。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // 统一源码编码。项目路径与注释含中文，Windows 默认字符集（GBK）下读取会出错。
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}

// Kotlin 1.9 插件在本机 JDK 22 + AGP 8.7 的组合下，会把单元测试类输出到
// build/tmp/kotlin-classes/<variant>，但不会把该目录注册进测试运行器的
// classpath，导致运行时报 ClassNotFoundException。这里显式补上。
tasks.withType<Test>().configureEach {
    val variant = name.removePrefix("test").replaceFirstChar { it.lowercase() }
    classpath += files(project.layout.buildDirectory.dir("tmp/kotlin-classes/$variant"))
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Shizuku
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
