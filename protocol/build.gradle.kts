import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")  // built-in Kotlin sejak AGP 9 (KGP dibundel)
}

android {
    namespace = "com.tasirin.cast.protocol"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        disable += setOf("OldTargetApi", "GradleDependency")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
