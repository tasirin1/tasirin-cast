import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")  // built-in Kotlin sejak AGP 9 (KGP dibundel)
}

android {
    namespace = "com.tasirin.castsender"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tasirin.castsender"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    // UI memakai Inggris (default values/); locale library lain dibuang — hemat ukuran.
    androidResources {
        localeFilters += listOf("en")
    }

    signingConfigs {
        create("release") {
            val storeFileProp = project.findProperty("storeFile") as String?
            val storePasswordProp = project.findProperty("storePassword") as String?
            val keyAliasProp = project.findProperty("keyAlias") as String?
            val keyPasswordProp = project.findProperty("keyPassword") as String?
            if (!storeFileProp.isNullOrBlank() && !storePasswordProp.isNullOrBlank() &&
                !keyAliasProp.isNullOrBlank() && !keyPasswordProp.isNullOrBlank()
            ) {
                storeFile = rootProject.file(storeFileProp)  // workflow menaruh keystore.jks di root repo
                storePassword = storePasswordProp
                keyAlias = keyAliasProp
                keyPassword = keyPasswordProp
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val signing = signingConfigs.getByName("release")
            if (signing.storeFile != null && signing.storeFile!!.exists()) {
                signingConfig = signing
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources.excludes += "META-INF/**"
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation(project(":protocol"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
}
