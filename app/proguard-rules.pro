# PDFBox-Android reflection entry points (stream filters, PDFTextStripper operators, fonts, encryption)
-keep class com.tom_roush.pdfbox.filter.** { *; }
-keep class com.tom_roush.pdfbox.contentstream.operator.** { *; }
-keep class com.tom_roush.pdfbox.pdmodel.font.** { *; }
-keep class com.tom_roush.pdfbox.pdmodel.encryption.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn org.bouncycastle.**

# Jsoup uses direct calls (no reflection); let R8 strip unused classes
-dontwarn org.jsoup.**
