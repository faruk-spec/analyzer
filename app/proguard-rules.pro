# ProGuard rules for AviatorSignalLab

# Keep Javascript interfaces so WebView reflection doesn't strip them
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Data Models for Gson / Room
-keep class com.example.aviatorsignallab.model.** { *; }
-keep class com.example.aviatorsignallab.update.ReleaseMetadata { *; }

# Keep Room generated files
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**
