# Keep JNI entry points
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.keiranhaas.auralcam.NativeBridge { *; }