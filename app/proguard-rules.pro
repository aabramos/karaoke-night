# The YouTube player talks to its WebView through @JavascriptInterface methods.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.pierfrancescosoffritti.androidyoutubeplayer.core.player.** { *; }

# Custom views referenced from XML are kept automatically by AAPT rules.
-repackageclasses
-allowaccessmodification
