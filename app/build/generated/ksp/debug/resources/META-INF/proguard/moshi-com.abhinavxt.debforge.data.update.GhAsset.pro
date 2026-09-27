-if class com.abhinavxt.debforge.data.update.GhAsset
-keepnames class com.abhinavxt.debforge.data.update.GhAsset
-if class com.abhinavxt.debforge.data.update.GhAsset
-keep class com.abhinavxt.debforge.data.update.GhAssetJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class com.abhinavxt.debforge.data.update.GhAsset
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-if class com.abhinavxt.debforge.data.update.GhAsset
-keepclassmembers class com.abhinavxt.debforge.data.update.GhAsset {
    public synthetic <init>(java.lang.String,java.lang.String,long,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
