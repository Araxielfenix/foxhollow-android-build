# ProGuard Rules for Foxhollow
# Keep native method names and JNI interfaces

# Keep SDLActivity and related classes
-keep class org.libsdl.app.** { *; }

# Keep Aurora and Borealis classes
-keep class dev.encounter.aurora.** { *; }
-keep class dev.encounter.borealis.** { *; }

# Keep Foxhollow classes
-keep class dev.encounter.foxhollow.** { *; }

# Keep native method names
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep JNI bridge methods
-keepclassmembers class * {
    native <methods>;
}

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Don't optimize native library loading
-keep class java.lang.System { *; }
-keep class java.lang.Runtime { *; }

# Keep annotations
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes EnclosingMethod

# For debugging (remove in production)
# -keepattributes SourceFile,LineNumberTable
