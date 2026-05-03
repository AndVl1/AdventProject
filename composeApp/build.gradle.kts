import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
}

// ── Генерация BuildSecrets.kt из local.properties ───────────────────────────
// Добавьте в корневой local.properties строку:
//   openrouter.api.key=sk-or-v1-...
// Файл local.properties НЕ коммитится (он в .gitignore).
// При отсутствии ключа OPENROUTER_API_KEY = "", сборка проходит,
// чат вернёт ошибку авторизации при отправке.
val generatedSecretsDir = layout.buildDirectory.dir("generated/secrets/commonMain/kotlin")

abstract class GenerateBuildSecretsTask : DefaultTask() {
    @get:Input
    abstract val openRouterApiKey: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val apiKey = openRouterApiKey.get()
        val outDir = outputDir.get().asFile.resolve("ru/andvl/advent/advenced/secrets")
        outDir.mkdirs()
        outDir.resolve("BuildSecrets.kt").writeText(
            "// AUTO-GENERATED — do not edit manually.\n" +
            "// Source: local.properties -> openrouter.api.key\n" +
            "package ru.andvl.advent.advenced.secrets\n\n" +
            "internal const val OPENROUTER_API_KEY = \"$apiKey\"\n"
        )
    }
}

// Читаем ключ на configuration-time (до сериализации кэша) — только примитивные типы.
val resolvedApiKey: String = run {
    val f = rootProject.file("local.properties")
    if (f.exists()) Properties().apply { f.inputStream().use(::load) }.getProperty("openrouter.api.key", "")
    else ""
}

val generateBuildSecrets by tasks.registering(GenerateBuildSecretsTask::class) {
    openRouterApiKey.set(resolvedApiKey)
    outputDir.set(generatedSecretsDir)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    jvm()

    sourceSets {
        commonMain {
            kotlin.srcDir(generatedSecretsDir)
        }

        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.kotlinxJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.ktor.client.cio)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

// Привязываем генерацию секретов ко всем KotlinCompile-задачам commonMain
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateBuildSecrets)
}
// Для iOS/native компиляторов (KotlinNativeCompile и KotlinNativeLink)
tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(generateBuildSecrets)
}

android {
    namespace = "ru.andvl.advent.advenced"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "ru.andvl.advent.advenced"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "ru.andvl.advent.advenced.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "ru.andvl.advent.advenced"
            packageVersion = "1.0.0"
        }
    }
}

// Day 12 — indirect prompt injection CLI runner.
// Запуск: ./gradlew :composeApp:runDay12
//   опционально: -PmodelId=qwen/qwen3-235b-a22b-2507
tasks.register<JavaExec>("runDay12") {
    group = "application"
    description = "Run Day 12 indirect prompt injection matrix"
    val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")
    classpath = files(jvmMain.runtimeDependencyFiles, jvmMain.output.allOutputs)
    dependsOn("jvmMainClasses")
    mainClass.set("ru.andvl.advent.advenced.day12.Day12CliKt")
    standardInput = System.`in`
    val modelId = (project.findProperty("modelId") as String?) ?: ""
    if (modelId.isNotBlank()) args(modelId)
}

// Day 12 — agent demo (ИДЁТ К САЙТУ, читает HTML с injection, отправляет email).
// Видно tool-call'ы в логе → пригодно для записи видео.
// Запуск: ./gradlew :composeApp:agentDemoDay12 --console=plain -q
//   опционально: -PmodelId=qwen/qwen-2.5-7b-instruct
tasks.register<JavaExec>("agentDemoDay12") {
    group = "application"
    description = "Day 12 indirect injection — agent demo (3 scenarios)"
    val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")
    classpath = files(jvmMain.runtimeDependencyFiles, jvmMain.output.allOutputs)
    dependsOn("jvmMainClasses")
    mainClass.set("ru.andvl.advent.advenced.day12.agentdemo.Day12AgentDemoKt")
    standardInput = System.`in`
    val modelId = (project.findProperty("modelId") as String?) ?: ""
    if (modelId.isNotBlank()) args(modelId)
}

// Day 12 — interactive REPL (выбор атаки/защит/модели вручную).
// Запуск: ./gradlew :composeApp:replDay12 --console=plain -q
tasks.register<JavaExec>("replDay12") {
    group = "application"
    description = "Day 12 interactive REPL — pick attack/defense/model and run"
    val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")
    classpath = files(jvmMain.runtimeDependencyFiles, jvmMain.output.allOutputs)
    dependsOn("jvmMainClasses")
    mainClass.set("ru.andvl.advent.advenced.day12.Day12ReplKt")
    standardInput = System.`in`
}
