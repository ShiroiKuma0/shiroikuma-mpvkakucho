import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose.compiler)
  alias(libs.plugins.kotlinx.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.room)
}

// shiroikuma fork: the INSTALLED package id. The code namespace stays "app.marlboroadvance.mpvex"
// (see android.namespace below) — renaming it would turn every upstream rebase into a mass-conflict.
// Only the applicationId differs, which is what makes this installable side-by-side with stock mpvEx.
val packageName = "shiroikuma.mpvkakucho"

// shiroikuma fork: our per-build increment, bumped by the buildApk task, reset to 1 on each new
// upstream version. Applied to upstream's own versionCode/versionName below the defaultConfig
// block, so upstream's two literals stay byte-identical and never conflict on rebase.
val shiroikumaBuild = (providers.gradleProperty("BUILD_NUMBER").orNull ?: "1").toInt()

android {
  namespace = "app.marlboroadvance.mpvex"
  compileSdk = 36

  defaultConfig {
    applicationId = packageName
    minSdk = 26
    targetSdk = 36
    versionCode = 129
    versionName = "1.2.9"

    // shiroikuma fork: single-ABI build — we ship one arm64-v8a APK
    // (matches the shiroikuma-mpvkakucho_*_arm64-v8a.apk name).
    ndk {
      abiFilters += "arm64-v8a"
    }

    vectorDrawables {
      useSupportLibrary = true
    }

    buildConfigField("String", "GIT_SHA", "\"${getCommitSha()}\"")
    buildConfigField("int", "GIT_COUNT", getCommitCount())
  }

  flavorDimensions += "distribution"

  productFlavors {
    create("standard") {
      dimension = "distribution"
      buildConfigField("boolean", "ENABLE_UPDATE_FEATURE", "true")
      buildConfigField("boolean", "SCOPED_STORAGE_ONLY", "false")
    }

    create("playstore") {
      dimension = "distribution"
      versionNameSuffix = "-playstore"
      buildConfigField("boolean", "ENABLE_UPDATE_FEATURE", "false")
      buildConfigField("boolean", "SCOPED_STORAGE_ONLY", "true")
    }

    create("fdroid") {
      dimension = "distribution"
      versionNameSuffix = "-fdroid"
      buildConfigField("boolean", "ENABLE_UPDATE_FEATURE", "false")
      buildConfigField("boolean", "SCOPED_STORAGE_ONLY", "false")

      ndk {
        abiFilters += "arm64-v8a"
      }
    }
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = false
  }

  // shiroikuma fork: no ABI splits — the fork ships a single arm64-v8a APK, so there is exactly one
  // output per variant and the versionCode stays exactly <upstream> * 10000 + <BUILD_NUMBER>
  // (upstream's androidComponents block, which multiplied the code by 10 and added a per-ABI digit,
  // is removed for the same reason).
  splits {
    abi {
      isEnable = false
    }
  }

  // shiroikuma fork versioning. Upstream's versionCode/versionName literals above are left exactly
  // as upstream writes them and read back here, so an upstream bump flows through untouched:
  //   versionName = "<upstream>+<BUILD_NUMBER>"
  //   versionCode = <upstream> * 10000 + <BUILD_NUMBER>
  val upstreamVersionCode = defaultConfig.versionCode!!
  val upstreamVersionName = defaultConfig.versionName!!
  val forkVersionName = "$upstreamVersionName+$shiroikumaBuild"
  val forkVersionCode = upstreamVersionCode * 10000 + shiroikumaBuild
  defaultConfig.versionCode = forkVersionCode
  defaultConfig.versionName = forkVersionName
  println("shiroikuma fork version: $forkVersionName (versionCode $forkVersionCode)")

  // shiroikuma fork: release signing. Upstream has no local signing config at all (its CI signs
  // with apksigner), so this block is entirely ours. keystore.properties is gitignored because it
  // carries the signing password — tolerate its absence instead of failing configuration, see
  // keystore.properties_sample.
  val keystorePropertiesFile = rootProject.file("keystore.properties")
  val keystoreProperties = Properties()
  if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
  } else {
    println("keystore.properties not found — release builds will be unsigned")
  }

  signingConfigs {
    create("release") {
      val keyStorePath = keystoreProperties["storeFile"] as String? ?: ""
      val keyStore = project.file(keyStorePath.ifEmpty { "keystore-not-configured" })
      if (keyStore.exists()) {
        storeFile = keyStore
        storePassword = keystoreProperties["storePassword"] as String
        keyAlias = keystoreProperties["keyAlias"] as String
        keyPassword = keystoreProperties["keyPassword"] as String
        println("Signing config release is using keystore [$storeFile]")
      } else {
        println("Keystore [$keyStorePath] doesn't exist — release builds will be unsigned!")
      }
    }
  }

  buildTypes {
    named("release") {
      // shiroikuma fork: sign the release build from keystore.properties (see signingConfigs above).
      signingConfig = signingConfigs.getByName("release")
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
      ndk {
        debugSymbolLevel = "none"
      }
    }

    create("preview") {
      initWith(getByName("release"))
      signingConfig = null
      applicationIdSuffix = ".preview"
      versionNameSuffix = "-${getCommitCount()}"
    }

    named("debug") {
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-${getCommitCount()}"
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    viewBinding = true
    buildConfig = true
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "META-INF/DEPENDENCIES"
      excludes += "META-INF/LICENSE*"
      excludes += "META-INF/NOTICE*"
      excludes += "META-INF/*.kotlin_module"
      excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
    jniLibs {
      useLegacyPackaging = true
    }
  }

  @Suppress("UnstableApiUsage")
  androidResources {
    generateLocaleConfig = true
  }
}

// shiroikuma fork: upstream's androidComponents block lived here. It multiplied every output's
// versionCode by 10 and added a per-ABI digit, which only makes sense with ABI splits enabled.
// We ship a single arm64-v8a APK (splits disabled above), so it is removed and the versionCode
// stays exactly <upstream> * 10000 + <BUILD_NUMBER>.

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll(
      "-Xwhen-guards",
      "-Xcontext-parameters",
      "-Xannotation-default-target=param-property",
      "-opt-in=com.google.accompanist.permissions.ExperimentalPermissionsApi",
      "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
    )
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

composeCompiler {
  includeSourceInformation = true
}

room {
  schemaDirectory("$projectDir/schemas")
}

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.material3.android)
  implementation("com.google.android.material:material:1.13.0")
  implementation(libs.androidx.compose.material)
  implementation(libs.androidx.ui.tooling.preview)
  debugImplementation(libs.androidx.ui.tooling)
  implementation(libs.bundles.compose.navigation3)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.compose.constraintlayout)
  implementation("androidx.preference:preference-ktx:1.2.1")
  implementation("androidx.constraintlayout:constraintlayout:2.2.0")
  implementation(libs.androidx.material3.icons.extended)
  implementation(libs.androidx.compose.animation.graphics)
  implementation(libs.mediasession)
  implementation(libs.androidx.documentfile)
  implementation(libs.saveable)

  implementation(platform(libs.koin.bom))
  implementation(libs.bundles.koin)

  implementation(libs.seeker)
  implementation(libs.compose.prefs)

  implementation(libs.accompanist.permissions)

  implementation(libs.room.runtime)
  ksp(libs.room.compiler)
  implementation(libs.room.ktx)

  implementation(libs.kotlinx.immutable.collections)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okhttp)

  implementation(libs.truetype.parser)
  implementation(libs.fsaf)
  implementation(libs.mediainfo.lib)
  implementation(files("libs/mpv-android-lib-v0.0.1.aar"))

  // Network protocol libraries
  implementation(libs.smbj)
  implementation(libs.commons.net)
  implementation(libs.sardine.android) {
    exclude(group = "xpp3", module = "xpp3")
  }
  implementation(libs.nanohttpd)
  implementation(libs.lazycolumnscrollbar)
  implementation(libs.reorderable)
}

/* ---------------- Git helpers ---------------- */

fun getCommitCount(): String =
  runCommand("git rev-list --count HEAD") ?: "0"

fun getCommitSha(): String =
  runCommand("git rev-parse --short HEAD") ?: "unknown"

fun runCommand(command: String): String? =
  try {
    val parts = command.split(' ')
    val process = ProcessBuilder(parts)
      .redirectErrorStream(true)
      .start()

    val output = process.inputStream
      .bufferedReader()
      .readText()
      .trim()

    process.waitFor()
    output.ifEmpty { null }
  } catch (e: Exception) {
    null
  }

/* ---------------- shiroikuma fork: build the signed release APK, copy it to ~/tmp, bump BUILD_NUMBER ---------------- */

// The APK is given its house name here rather than by renaming the build output: AGP 9 removed the
// legacy `applicationVariants` / `BaseVariantOutputImpl` API the sister forks use for that, and the
// delivered artifact in ~/tmp is the one that has to carry the name anyway.
tasks.register("buildApk") {
  description = "Build the signed release APK, copy it to ~/tmp, and bump BUILD_NUMBER for next time."
  group = "build"
  dependsOn("assembleStandardRelease")
  // Capture project state at configuration time so the action is configuration-cache compatible.
  val fvName = android.defaultConfig.versionName
  val fvCode = android.defaultConfig.versionCode
  val releaseApkDir = layout.buildDirectory.dir("outputs/apk/standard/release")
  val userHome = providers.systemProperty("user.home")
  val propsFile = rootProject.file("gradle.properties")
  val currentBuildNumber = shiroikumaBuild
  doLast {
    val apkName = "shiroikuma-mpvkakucho_${fvName}_arm64-v8a.apk"
    val outputDir = releaseApkDir.get().asFile
    val targetDir = File(userHome.get(), "tmp")
    targetDir.mkdirs()
    outputDir.listFiles { _, name -> name.endsWith(".apk") }?.firstOrNull()?.let { apk ->
      val targetFile = File(targetDir, apkName)
      apk.copyTo(targetFile, overwrite = true)
      println("\u001B[1;36m>>> ${targetFile.absolutePath}\u001B[0m")
      println("\u001B[1;36m>>> versionCode $fvCode\u001B[0m")
    } ?: throw GradleException("No APK found in $outputDir")

    // Auto-increment BUILD_NUMBER for the next build.
    val nextBuildNumber = currentBuildNumber + 1
    propsFile.writeText(
      propsFile.readText().replace(
        "BUILD_NUMBER=$currentBuildNumber",
        "BUILD_NUMBER=$nextBuildNumber"
      )
    )
    println("\u001B[1;36m>>> BUILD_NUMBER bumped to $nextBuildNumber\u001B[0m")
  }
}
