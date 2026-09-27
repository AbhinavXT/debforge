-if class com.abhinavxt.debforge.data.update.GhRelease
-keepnames class com.abhinavxt.debforge.data.update.GhRelease
-if class com.abhinavxt.debforge.data.update.GhRelease
-keep class com.abhinavxt.debforge.data.update.GhReleaseJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class com.abhinavxt.debforge.data.update.GhRelease
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-if class com.abhinavxt.debforge.data.update.GhRelease
-keepclassmembers class com.abhinavxt.debforge.data.update.GhRelease {
    public synthetic <init>(java.lang.String,java.lang.String,java.lang.String,java.lang.String,boolean,boolean,java.util.List,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
