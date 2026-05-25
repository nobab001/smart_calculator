##──────────────────────────────────────────────
##  Aggressive obfuscation & shrinking rules
##──────────────────────────────────────────────

# Rename all source file attributes so stack traces reveal nothing
-renamesourcefileattribute x
-keepattributes *Annotation*

# Keep Android entry points that the OS must find by name
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.accessibilityservice.AccessibilityService

# Keep View constructors used by XML inflation
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep Parcelable CREATOR fields
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep enum methods required by the runtime
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep ViewBinding generated classes
-keep class com.example.smartcalculator.databinding.** { *; }

# exp4j – expression evaluator (uses reflection internally)
-keep class net.objecthunter.exp4j.** { *; }
-dontwarn net.objecthunter.exp4j.**

# Keep R class (resource IDs used at runtime)
-keep class com.example.smartcalculator.R$* { *; }

# Aggressive: obfuscate all remaining app classes
-repackageclasses ''
-allowaccessmodification
-overloadaggressively

# Remove all logging in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
