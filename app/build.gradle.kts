import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

val personalSigningPropertiesFile = providers.environmentVariable("MEMEVAULT_SIGNING_PROPERTIES")
    .orNull
    ?.let(::file)
    ?: file("${System.getProperty("user.home")}/.config/memevault/signing.properties")
val personalSigningProperties = Properties().apply {
    if (personalSigningPropertiesFile.isFile) {
        personalSigningPropertiesFile.inputStream().use(::load)
    }
}

fun requiredSigningProperty(name: String): String =
    personalSigningProperties.getProperty(name)?.takeIf(String::isNotBlank)
        ?: throw GradleException("Thiếu '$name' trong ${personalSigningPropertiesFile.absolutePath}")

android {
    namespace = "com.memevault.app"
    compileSdk = 36

    sourceSets {
        getByName("androidTest").assets.srcDirs(
            "$projectDir/src/androidTest/assets",
            "$projectDir/schemas",
        )
    }

    defaultConfig {
        applicationId = "com.memevault.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "0.1.20"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val personalReleaseSigning = if (personalSigningPropertiesFile.isFile) {
        signingConfigs.create("personalRelease") {
            storeFile = file(requiredSigningProperty("storeFile"))
            storePassword = requiredSigningProperty("storePassword")
            keyAlias = requiredSigningProperty("keyAlias")
            keyPassword = requiredSigningProperty("keyPassword")
        }
    } else {
        null
    }

    buildTypes {
        release {
            signingConfig = personalReleaseSigning
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }
}

tasks.matching { it.name == "packageRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        check(personalSigningPropertiesFile.isFile) {
            "Chưa cấu hình personal signing. Chạy tools/setup_personal_signing.sh trước khi build release."
        }
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.room:room-runtime:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.7.2")
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
}
