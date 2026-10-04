import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The app's version: the build number is derived from it (1.1.0 -> 10100), so the two can't drift apart.
val appVersion = "1.1.0"
val appVersionCode = appVersion.split('.').map { it.toInt() }.let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

// The release key lives outside the repository. Its properties file (storeFile, storePassword, keyAlias,
// keyPassword) is ~/.android/outrider-release.properties unless OUTRIDER_SIGNING points elsewhere.
// A release build without it stops with an error (-PallowUnsigned builds an "-unsigned" APK on purpose);
// debug builds always use the SDK's debug key.
val home: String = System.getProperty("user.home")
val signingFile = file(System.getenv("OUTRIDER_SIGNING")?.takeIf { it.isNotBlank() } ?: "$home/.android/outrider-release.properties")
val signing = Properties().apply { if (signingFile.isFile) signingFile.inputStream().use { load(it) } }
/** storeFile as written: "~" is the home folder, and a relative path is next to the properties file. */
fun signingStore(path: String): File {
    val expanded = if (path.startsWith("~/")) home + path.substring(1) else path
    val f = File(expanded)
    return if (f.isAbsolute) f else File(signingFile.parentFile, expanded)
}
val allowUnsigned = project.hasProperty("allowUnsigned")

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
            dest.delete()   // a wrong one in the way (renameTo won't replace it on Windows)
            dest.parentFile.mkdirs()
            val tmp = File(dest.path + ".part")
            var problem: Exception? = null
            for (attempt in 1..3) {
                try {
                    logger.lifecycle("Downloading $url" + if (attempt > 1) " (try $attempt)" else "")
                    val conn = URI(url).toURL().openConnection().apply {
                        connectTimeout = 30_000   // a stalled transfer must not hang the build forever
                        readTimeout = 60_000
                    }
                    conn.getInputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    problem = null
                    break
                } catch (e: java.io.IOException) {
                    problem = e
                    Thread.sleep(2000L * attempt)
                }
            }
            problem?.let { throw GradleException("$url: ${it.message}", it) }
            val got = sha(tmp)
            if (got != sha256) {
                tmp.delete()
                throw GradleException("$url: SHA-256 $got, expected $sha256")
            }
            if (!tmp.renameTo(dest)) throw GradleException("Couldn't move $tmp to $dest")
        }
        download("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar",
            sherpaAar, "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471")
        // the model files are checked against the manifest written when they were extracted (a stale or partial
        // model is replaced, not just a missing tokens.txt)
        val manifest = File(kwsDir, ".manifest")
        val modelOk = manifest.isFile && manifest.readLines().filter { it.isNotBlank() }.let { lines ->
            lines.size == 5 && lines.all { line ->
                val (name, hash) = line.split(' ')
                File(kwsDir, name).isFile && sha(File(kwsDir, name)) == hash
            }
        }
        if (!modelOk) {
            kwsDir.deleteRecursively()
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
            manifest.writeText(listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "bpe.model")
                .joinToString("\n") { "$it ${sha(File(kwsDir, it))}" } + "\n")
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
        versionCode = appVersionCode
        versionName = appVersion
        // ARM phones and tablets (64- and 32-bit): the wake word's native code is 16-24 MB per ABI. Intel (x86_64:
        // emulators, some Chromebooks) is left out on purpose, to keep the APK small.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    sourceSets["main"].assets.srcDir("voice/assets")

    signingConfigs {
        if (signing.getProperty("storeFile") != null) {
            create("release") {
                storeFile = signingStore(signing.getProperty("storeFile"))
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // release-vital checks block a release build again; the baseline holds what was there before (all harmless)
        abortOnError = true
        checkReleaseBuilds = true
        baseline = file("lint-baseline.xml")
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

// A release without the signing key is an error, not a silently unsigned APK under the signed one's name.
val checkReleaseSigning by tasks.registering {
    doLast {
        if (signing.getProperty("storeFile") == null && !allowUnsigned) throw GradleException(
            "No release signing key: $signingFile is missing or has no storeFile. Add it (see README, Release signing), " +
                "or build with -PallowUnsigned for an unsigned APK named ED-Outrider-$appVersion-unsigned.apk."
        )
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseSigning) }

// The APKs under their release names in build/dist/ (a plain copy: nothing of the build tools' internal API).
val distDebug by tasks.registering(Copy::class) {
    from(layout.buildDirectory.dir("outputs/apk/debug")) { include("*.apk") }
    into(layout.buildDirectory.dir("dist"))
    rename { "ED-Outrider-$appVersion-debug.apk" }
}
val distRelease by tasks.registering(Copy::class) {
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*.apk") }
    into(layout.buildDirectory.dir("dist"))
    rename { if (it.contains("unsigned")) "ED-Outrider-$appVersion-unsigned.apk" else "ED-Outrider-$appVersion.apk" }
}
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(distDebug) }
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(distRelease) }
