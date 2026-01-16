/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
  id("com.google.gms.google-services")
}

// Load Gemini API key from local.properties
val localPropertiesFile = rootProject.file("local.properties")
val geminiApiKey: String = if (localPropertiesFile.exists()) {
  val props = Properties()
  props.load(localPropertiesFile.inputStream())
  props.getProperty("gemini_api_key", "")
} else {
  ""
}

// Load Google Maps API key from local.properties
val mapsApiKey: String = if (localPropertiesFile.exists()) {
  val props = Properties()
  props.load(localPropertiesFile.inputStream())
  props.getProperty("google_maps_api_key", "")
} else {
  ""
}

// Load Picovoice API key from local.properties
val picovoiceApiKey: String = if (localPropertiesFile.exists()) {
  val props = Properties()
  props.load(localPropertiesFile.inputStream())
  props.getProperty("picovoice_api_key", "")
} else {
  ""
}

// Load Google Cloud API key from local.properties
val googleCloudApiKey: String = if (localPropertiesFile.exists()) {
  val props = Properties()
  props.load(localPropertiesFile.inputStream())
  props.getProperty("GOOGLE_CLOUD_API_KEY", "")
} else {
  ""
}

android {
  namespace = "com.meta.wearable.dat.externalsampleapps.landmarkguide"
  compileSdk = 35

  buildFeatures { buildConfig = true }

  defaultConfig {
    applicationId = "com.meta.wearable.dat.externalsampleapps.landmarkguide"
    minSdk = 31
    targetSdk = 34
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables { useSupportLibrary = true }
    
    // Gemini API Key
    buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
    
    // Google Maps API Key
    buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
    manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
    
    // Picovoice API Key
    buildConfigField("String", "PICOVOICE_API_KEY", "\"$picovoiceApiKey\"")
    
    // Google Cloud API Key (for Speech-to-Text, Translation, TTS)
    buildConfigField("String", "GOOGLE_CLOUD_API_KEY", "\"$googleCloudApiKey\"")
    
    // Deepgram API Key (for Streaming STT)
    val deepgramApiKey: String = if (localPropertiesFile.exists()) {
      val props = Properties()
      props.load(localPropertiesFile.inputStream())
      props.getProperty("DEEPGRAM_API_KEY", "")
    } else ""
    buildConfigField("String", "DEEPGRAM_API_KEY", "\"$deepgramApiKey\"")
    
    // OpenAI API Key (for GPT Translation + TTS)
    val openaiApiKey: String = if (localPropertiesFile.exists()) {
      val props = Properties()
      props.load(localPropertiesFile.inputStream())
      props.getProperty("OPENAI_API_KEY", "")
    } else ""
    buildConfigField("String", "OPENAI_API_KEY", "\"$openaiApiKey\"")

    // Soniox API Key (for Streaming STT)
    val sonioxApiKey: String = if (localPropertiesFile.exists()) {
      val props = Properties()
      props.load(localPropertiesFile.inputStream())
      props.getProperty("SONIOX_API_KEY", "")
    } else ""
    buildConfigField("String", "SONIOX_API_KEY", "\"$sonioxApiKey\"")
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }
  kotlinOptions { jvmTarget = "1.8" }
  buildFeatures { compose = true }
  composeOptions { kotlinCompilerExtensionVersion = "1.5.1" }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
  signingConfigs {
    getByName("debug") {
      storeFile = file("sample.keystore")
      storePassword = "sample"
      keyAlias = "sample"
      keyPassword = "sample"
    }
  }
}

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.androidx.material3)
  implementation(libs.kotlinx.collections.immutable)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.mockdevice)
  
  // OkHttp for API calls
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  
  // Location services
  implementation("com.google.android.gms:play-services-location:21.0.1")
  
  // JSON parsing
  implementation("com.google.code.gson:gson:2.10.1")
  
  // Google Maps for Compose
  implementation("com.google.maps.android:maps-compose:4.3.0")
  implementation("com.google.android.gms:play-services-maps:18.2.0")
  
  // Google Nearby Connections - for Multi-user Majlis P2P (backup/offline)
  implementation("com.google.android.gms:play-services-nearby:19.3.0")
  
  // Firebase - for instant real-time connection
  implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
  implementation("com.google.firebase:firebase-database-ktx")
  
  // QR Code generation (ZXing) and scanning (ML Kit)
  implementation("com.google.zxing:core:3.5.3")
  implementation("com.google.mlkit:barcode-scanning:17.2.0")
  
  // Vosk - Offline Speech Recognition (Wake Word Detection)
  // Free, open source, no device limits
  implementation("com.alphacephei:vosk-android:0.3.47")
  
  // ONNX Runtime - for OpenWakeWord models
  implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.3")
  
  // Picovoice Porcupine - Wake Word Detection (accurate, offline)
  implementation("ai.picovoice:porcupine-android:3.0.2")
  
  // Google Cloud SDKs (commented out - protobuf conflict, will add later)
  // implementation("com.google.cloud:google-cloud-speech:4.28.0")
  // implementation("io.grpc:grpc-okhttp:1.60.0")
  // implementation("io.grpc:grpc-stub:1.60.0")
  // implementation("com.google.cloud:google-cloud-translate:2.33.0")
  
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.test.rules)
}
