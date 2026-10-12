-keep class com.shuyu.gsyvideoplayer.** { *; }
-keep class androidx.media3.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn com.shuyu.gsyvideoplayer.**
