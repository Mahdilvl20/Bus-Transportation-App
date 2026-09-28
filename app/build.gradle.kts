import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

// Release signing comes only from the environment, so local builds are untouched:
// with no KEYSTORE_PATH, assembleRelease keeps producing an unsigned APK exactly as
// it does today. The release workflow sets all four values from GitHub Secrets.
val releaseKeystorePath: String? = System.getenv("KEYSTORE_PATH")

// ABI splits are opt-in (-PabiSplits) so the default assembleRelease still yields the
// single normal APK. The module ships 12 native libraries across 4 ABIs in a 66MB
// universal APK, so per-architecture builds are worth offering — just not by default.
val abiSplitsEnabled: Boolean = project.hasProperty("abiSplits")


android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.isfahanbus.kqzmwt"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    if (releaseKeystorePath != null) {
      create("release") {
        storeFile = project.file(releaseKeystorePath)
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS")
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }
  }

  if (abiSplitsEnabled) {
    splits {
      abi {
        isEnable = true
        isUniversalApk = true
        reset()
        include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (releaseKeystorePath != null) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Predictable release APK names. AGP 9's new DSL (android.newDsl=true, the default)
// no longer exposes applicationVariants or outputFileName — VariantOutput only offers
// version overrides — so the APKs are renamed by a task instead of by the DSL.
// Debug output is untouched, so local development builds are unaffected.
// Every value the action needs is a local of the configuration block: holding a
// reference to the build script itself breaks the configuration cache.
val renameReleaseApks = tasks.register("renameReleaseApks") {
  group = "build"
  description = "Renames release APKs to IsfahanBus-<abi>-release.apk"
  val outputDir = layout.buildDirectory.dir("outputs/apk/release")
  val splitsOn = project.hasProperty("abiSplits")
  val base = "IsfahanBus"
  doLast {
    val dir = outputDir.get().asFile
    val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    dir.listFiles { f -> f.isFile && f.extension == "apk" }?.forEach { apk ->
      // Skip files this task already renamed: otherwise a leftover plain release APK
      // from an earlier non-split build would overwrite the universal one below.
      if (apk.name.startsWith("$base-")) return@forEach
      val abi = abis.firstOrNull { apk.name.contains(it) }
      val target = when {
        abi != null -> "$base-$abi-release.apk"
        splitsOn -> "$base-universal-release.apk"
        else -> "$base-release.apk"
      }
      if (apk.name != target) {
        apk.copyTo(dir.resolve(target), overwrite = true)
        apk.delete()
      }
    }
  }
}

tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(renameReleaseApks) }

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.play.services.location)
  implementation(libs.maplibre.android.sdk)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
