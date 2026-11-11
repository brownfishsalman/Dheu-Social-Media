# R8 keep rules for the release build.
# kotlinx.serialization, Ktor, OkHttp, Coil and supabase-kt ship their own
# consumer rules. The HTTP engine and Coil's network fetcher are wired up by
# hand (data/Backend.kt, DheuApp.kt), so nothing relies on ServiceLoader.

# Readable crash reports (data/CrashReports.kt, Admin → Crashes): keep class
# and method names and line numbers. R8 still strips and optimises; it just
# doesn't rename, so a stack trace reads as is, with no mapping file.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
