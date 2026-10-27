# Chaquopy proguard rules
-keep class com.chaquo.python.** { *; }

# WebKit / JavaScript interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# AndroidX and Material components
-keep class androidx.webkit.** { *; }
-keep class com.google.android.material.** { *; }
