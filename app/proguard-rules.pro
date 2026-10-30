# ---- Google API Client / Drive ----
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.api.client.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.api.client.**
-dontwarn com.google.android.gms.**
-dontwarn org.apache.http.**

# ---- Gson DTOs (reflection) ----
-keep class com.securevault.pro.data.backup.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
# Real file names and line numbers in a stack trace. There is no adb here, so the report the
# owner reads is the only instrument; without these every frame says "SourceFile" and the
# trail cannot be tied back to the line that threw.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute StackTrace
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ---- Hilt ----
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# ---- ML Kit ----
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }

# ---- Coroutines ----
-dontwarn kotlinx.coroutines.**

# ---- Tink (transitive from play services auth) ----
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
