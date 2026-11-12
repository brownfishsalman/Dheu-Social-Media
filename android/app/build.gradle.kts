import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Supabase settings live in android/local.properties (not committed):
//   dheu.supabaseUrl=https://<project>.supabase.co
//   dheu.supabaseKey=<publishable key>
//   dheu.siteUrl=https://dheu-beige.vercel.app
val local = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
fun setting(key: String): String = local.getProperty(key) ?: ""

// Push notifications: android/app/google-services.json from the Firebase console
// (not committed). Read here rather than through the google-services plugin, so
// the app still builds without one; push is then simply off.
val firebase: Map<String, String> = file("google-services.json").takeIf { it.exists() }?.let { f ->
    @Suppress("UNCHECKED_CAST")
    val json = groovy.json.JsonSlurper().parse(f) as Map<String, Any?>
    val project = json["project_info"] as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val clients = json["client"] as List<Map<String, Any?>>
    fun info(c: Map<String, Any?>) = c["client_info"] as Map<String, Any?>
    val client = clients.firstOrNull { (info(it)["android_client_info"] as Map<*, *>)["package_name"] == "com.salmansaadiq.dheu" }
        ?: error("google-services.json has no Android app for com.salmansaadiq.dheu")
    @Suppress("UNCHECKED_CAST")
    val keys = client["api_key"] as List<Map<String, Any?>>
    mapOf(
        "appId" to info(client)["mobilesdk_app_id"].toString(),
        "apiKey" to keys.first()["current_key"].toString(),
        "projectId" to project["project_id"].toString(),
        "senderId" to project["project_number"].toString(),
    )
} ?: emptyMap()

android {
    namespace = "com.salmansaadiq.dheu"
    // The current AndroidX releases (navigation 2.10, lifecycle 2.11...) require compiling against API 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.salmansaadiq.dheu"
        minSdk = 28
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"

        buildConfigField("String", "SUPABASE_URL", "\"${setting("dheu.supabaseUrl")}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${setting("dheu.supabaseKey")}\"")
        buildConfigField("String", "SITE_URL", "\"${setting("dheu.siteUrl").ifEmpty { "https://dheu-beige.vercel.app" }}\"")
        // Where releases are published: the "new version" banner asks its latest release.
        buildConfigField("String", "RELEASES_REPO", "\"${setting("dheu.releasesRepo").ifEmpty { "brownfishsalman/Dheu-Social-Media" }}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${firebase["appId"].orEmpty()}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${firebase["apiKey"].orEmpty()}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${firebase["projectId"].orEmpty()}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${firebase["senderId"].orEmpty()}\"")
    }

    buildTypes {
        release {
            // R8 strips unused code and resources from the libraries.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/DEPENDENCIES")
        }
    }
}

kotlin {
    compilerOptions {
        // Top app bars, bottom sheets and pull-to-refresh are still marked experimental in Material 3.
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.storage)
    implementation(libs.supabase.realtime)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}
