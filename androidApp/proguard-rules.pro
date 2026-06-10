# R8 keep rules for the day isMinifyEnabled flips on. Shrinking is currently OFF
# (androidApp/build.gradle.kts) — these rules are staged so enabling it is a config
# change plus on-device validation, not an archaeology project. Each block answers
# a known breakage in this dependency set; revisit when deps change.

# ── Ktor (network engine + raw sockets) ──────────────────────────────────────
# Ktor resolves engines and selector internals reflectively.
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Ktor/kotlinx-coroutines debug agent + Atomicfu references that don't exist on Android.
-dontwarn kotlinx.atomicfu.**
-dontwarn kotlinx.coroutines.debug.**

# ── Koin (service locator used by the shared module) ────────────────────────
-keep class org.koin.** { *; }
-dontwarn org.koin.**

# ── Compose resources (jetzy.shared.generated.resources read via reflection) ─
-keep class jetzy.shared.generated.resources.** { *; }

# ── ZXing (QR decode on the desktop path is JVM-only, but the Android QR render
#    pulls qrose; keep ZXing core if it lands on the classpath transitively) ──
-dontwarn com.google.zxing.**

# ── Kotlin metadata used by kotlinx.serialization-style reflection (defensive;
#    no @Serializable usage today, but FileKit/Coil read metadata) ─────────────
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Coil (image loading in the photo picker) — OkHttp/Okio warnings on Android.
-dontwarn okhttp3.**
-dontwarn okio.**
