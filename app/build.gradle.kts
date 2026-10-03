import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The release key lives outside the repository. Its properties file (storeFile, storePassword, keyAlias,
// keyPassword) is ~/.android/outrider-release.properties unless OUTRIDER_SIGNING points elsewhere.
// Without it, release builds are left unsigned; debug builds always use the SDK's debug key.
val signingFile = file(System.getenv("OUTRIDER_SIGNING") ?: "${System.getProperty("user.home")}/.android/outrider-release.properties")
val signing = Properties().apply { if (signingFile.isFile) signingFile.inputStream().use { load(it) } }

// The wake word's engine and model: sherpa-onnx (Apache-2.0) and its English keyword-spotting model, downloaded once
// into app/voice/ (git-ignored: 40 MB of native code) and checked against pinned SHA-256s.
val sherpaVersion = "1.13.8"
val sherpaAar = file("voice/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar")
val kwsModel = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
val kwsDir = file("voice/assets/kws")
val fetchVoice by tasks.registering {
    description = "Downloads the sherpa-onnx library and the keyword-spotting model (once)"
    outputs.file(sherpaAar)
    outputs.dir(kwsDir)
    doLast {
        fun download(url: String, dest: File, sha256: String) {
            if (dest.isFile && sha(dest) == sha256) return
            dest.parentFile.mkdirs()
            val tmp = File(dest.path + ".part")
            logger.lifecycle("Downloading $url")
            URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val got = sha(tmp)
            if (got != sha256) {
                tmp.delete()
                throw GradleException("$url: SHA-256 $got, expected $sha256")
            }
            tmp.renameTo(dest)
        }
        download("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar",
            sherpaAar, "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471")
        if (!File(kwsDir, "tokens.txt").isFile) {
            val tar = file("voice/$kwsModel.tar.bz2")
            download("https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/$kwsModel.tar.bz2",
                tar, "f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a")
            // the int8 models (as accurate as the full ones on test clips, a third of the size), tokens and the BPE model
            copy {
                from(tarTree(resources.bzip2(tar)))
                include("**/encoder-*.int8.onnx", "**/decoder-*.int8.onnx", "**/joiner-*.int8.onnx", "**/tokens.txt", "**/bpe.model")
                eachFile { path = name.replace(Regex("-epoch-.*\\.int8"), "") }
                includeEmptyDirs = false
                into(kwsDir)
            }
            tar.delete()
        }
    }
}

fun sha(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

android {
    namespace = "io.github.weslocke.outrider"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.weslocke.outrider"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // phones and tablets (64- and 32-bit ARM): the wake word's native code is 16-24 MB per ABI
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    sourceSets["main"].assets.srcDir("voice/assets")

    signingConfigs {
        if (signing.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Installs beside the release build: the two are signed with different keys.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "ED-Outrider-${variant.versionName}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.12.4")
    // Document-start scripts and origin-restricted message listeners for the page <-> app bridge.
    implementation("androidx.webkit:webkit:1.15.0")
    // the wake word (see fetchVoice above)
    implementation(files(sherpaAar))

    testImplementation("junit:junit:4.13.2")
    // org.json is only stubbed in local unit tests; the real one parses the server's answers there.
    testImplementation("org.json:json:20250517")
}

tasks.named("preBuild") { dependsOn(fetchVoice) }
