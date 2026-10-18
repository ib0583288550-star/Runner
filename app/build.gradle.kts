import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ybapps.heyassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ybapps.heyassistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
    }


    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/voskAssets"))

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.03.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.alphacephei:vosk-android:0.3.47")
    debugImplementation("androidx.compose.ui:ui-tooling")
}


val voskAssetsDir = layout.buildDirectory.dir("generated/voskAssets")
val voskModelZip = layout.buildDirectory.file("downloads/vosk-model-small-en-us-0.15.zip")

val prepareBundledVoskModel by tasks.registering {
    outputs.dir(voskAssetsDir)
    outputs.file(voskModelZip)
    doLast {
        val zipFile = voskModelZip.get().asFile
        zipFile.parentFile.mkdirs()
        if (!zipFile.exists() || zipFile.length() < 1_000_000L) {
            URL("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").openConnection().apply {
                connectTimeout = 20000
                readTimeout = 120000
            }.getInputStream().use { input ->
                zipFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val outDir = voskAssetsDir.get().asFile
        val modelDir = outDir.resolve("vosk-model-small-en-us-0.15")
        if (!File(modelDir, "am").exists()) {
            outDir.deleteRecursively()
            outDir.mkdirs()
            project.copy {
                from(project.zipTree(zipFile))
                into(outDir)
            }
        }
        check(File(modelDir, "am").exists()) { "Bundled Vosk English model extraction failed" }
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareBundledVoskModel)
}
