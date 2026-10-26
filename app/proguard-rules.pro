# Keep PDFBox and Jsoup classes if minification is enabled in release builds
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-keep class org.jsoup.** { *; }
