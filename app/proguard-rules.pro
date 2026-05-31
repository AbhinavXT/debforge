# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# --- DebForge release rules -------------------------------------------------
# Keep file + line attributes so release stack traces remain debuggable.
-keepattributes SourceFile,LineNumberTable

# Belt-and-braces: keep DTO field names. Moshi's @JsonClass codegen already
# generates JsonAdapters referencing these and ships consumer keep rules, but
# making this explicit costs nothing and protects against a future shrinker
# config change that misses the indirect references.
-keep class com.abhinavxt.debforge.data.remote.dto.** { *; }
