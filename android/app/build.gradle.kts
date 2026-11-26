import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.jagabocil.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.jagabocil.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.2.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

abstract class BundleQuran : DefaultTask() {
    @get:InputFile abstract val snapshot: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun generate() {
        val source = JsonSlurper().parse(snapshot.get().asFile, "UTF-8") as Map<*, *>
        val chapters = (source["chapters"] as List<*>).map { it as Map<*, *> }
        val juz30Start = source["juz30Start"] as Int
        val sha256 = MessageDigest.getInstance("SHA-256")
        val entries = chapters.map { chapter ->
            val id = chapter["id"] as String
            val ayahs = chapter["ayahs"] as List<*>
            val entry = mutableMapOf("id" to id, "name" to chapter["name"], "verses" to ayahs.size)
            if (id.toInt() >= juz30Start) {
                entry["ayahs"] = ayahs
                entry["maxRecordingSeconds"] = chapter["maxRecordingSeconds"]
            } else {
                entry["sha256"] = HexFormat.of().formatHex(sha256.digest(ayahs.joinToString("\n").toByteArray()))
            }
            entry
        }
        val output = outputDirectory.get().asFile
        output.mkdirs()
        output.resolve("quran-catalog.json").writeText(JsonOutput.toJson(mapOf(
            "chapters" to entries,
            "notice" to source["notice"],
        )))
    }
}

val bundleQuran = tasks.register<BundleQuran>("bundleQuran") {
    snapshot.set(rootProject.layout.projectDirectory.file("../server/src/quran.json"))
    outputDirectory.set(layout.buildDirectory.dir("generated/quran-assets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(bundleQuran, BundleQuran::outputDirectory)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
