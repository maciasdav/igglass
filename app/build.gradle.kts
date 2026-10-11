import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Firma de release: en local sale de keystore.properties (ignorado por git);
 * en GitHub Actions, de las variables SIGNING_*. Sin ninguna de las dos, se
 * firma con la clave de depuración.
 */
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)

val releaseStoreFile = signingValue("SIGNING_STORE_FILE", "storeFile")?.let { rootProject.file(it) }

android {
    namespace = "com.maciasdxv.igglass"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.maciasdxv.igglass"
        // RuntimeShader / RenderEffect.createRuntimeShaderEffect requieren API 33.
        minSdk = 33
        targetSdk = 36
        // En CI salen del tag (v1.2.3 → 1.2.3) y del número de ejecución.
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 21
        versionName = providers.gradleProperty("versionName").orNull ?: "0.21-dev"
    }

    signingConfigs {
        if (releaseStoreFile?.exists() == true) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("SIGNING_STORE_PASSWORD", "storePassword")
                keyAlias = signingValue("SIGNING_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("SIGNING_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Sin minify: LSPosed instancia HookEntry por nombre desde xposed_init.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
