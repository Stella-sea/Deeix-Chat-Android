# Deeix-Chat uses only Android platform APIs.

# Keep WebView JavascriptInterface bridge methods: they are invoked by name from
# injected JavaScript, so R8 must neither rename nor strip them in release builds.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
