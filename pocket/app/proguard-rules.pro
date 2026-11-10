# Methods annotated with @JavascriptInterface are kept by the default rules,
# but pin the bridge class so a future refactor cannot silently break it.
-keepclassmembers class dev.rustbook.pocket.book.BookBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class dev.rustbook.pocket.book.BookBridge { *; }

# Keep annotations used by Kotlin reflection-free code paths.
-dontwarn org.jetbrains.annotations.**
