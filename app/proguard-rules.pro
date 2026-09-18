# Keep bridge entry points called from evaluateJavascript strings
-keepclassmembers class dev.calibre.viewer.bridge.** { *; }

# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.calibre.**$$serializer { *; }
-keepclasseswithmembers class dev.calibre.** {
    kotlinx.serialization.KSerializer serializer(...);
}
