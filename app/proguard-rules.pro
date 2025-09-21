# GREP: PROGUARD_VIRTUALCAM
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**
-keep class com.example.virtualcam.xposed.** { *; }

-keepclassmembers class android.hardware.camera2.params.SessionConfiguration {
    public *** getOutputConfigurations(...);
    public *** getSessionType(...);
    public *** getExecutor(...);
    public *** getStateCallback(...);
}
-keepclassmembers class android.hardware.camera2.params.OutputConfiguration {
    public <init>(android.view.Surface);
    public android.view.Surface getSurface(...);
}

-keep class androidx.room.** { *; }
-keep class kotlin.Metadata { *; }
