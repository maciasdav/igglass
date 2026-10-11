plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.maciasdxv.igglass"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.maciasdxv.igglass"
        // RuntimeShader / RenderEffect.createRuntimeShaderEffect requieren API 33.
        minSdk = 33
        targetSdk = 36
        versionCode = 21
        versionName = "0.21-tema-claro"
    }

    buildTypes {
        release {
            // Sin minify: LSPosed instancia HookEntry por nombre desde xposed_init.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Lo aporta LSPosed en tiempo de ejecución: nunca empaquetarlo.
    compileOnly("de.robv.android.xposed:api:82")
}
