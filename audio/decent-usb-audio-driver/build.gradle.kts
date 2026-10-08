plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.decent.usbaudio"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 24
        externalNativeBuild { cmake { cppFlags("-fno-fast-math") } }
    }

    buildTypes {
        getByName("debug")
        getByName("release")
        create("rawRelease") {
            initWith(getByName("release"))
            isMinifyEnabled = false
        }
    }

    // Builds libdecent_usb_audio.so, which UsbAudioStream's companion-object
    // init loads via System.loadLibrary(). While this block was commented out
    // the loadLibrary call had no .so to find and threw UnsatisfiedLinkError on
    // first use of the class.
    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin { jvmToolchain(17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
}
