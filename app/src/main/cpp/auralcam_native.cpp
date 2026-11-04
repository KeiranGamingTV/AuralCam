#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_keiranhaas_auralcam_NativeBridge_nativeVersion(JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF("AuralCam native 0.1.0");
}.