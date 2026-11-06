#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <cstring>

#include "frame_analysis.h"
#include "raw_merge.h"

#define LOG_TAG "AuralCam-native"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_keiranhaas_auralcam_NativeBridge_nativeVersion(JNIEnv *env, jclass) {
    return env->NewStringUTF("AuralCam native 0.3.0");
}

JNIEXPORT jboolean JNICALL
Java_com_keiranhaas_auralcam_NativeBridge_analyzeYuv(
        JNIEnv *env, jclass,
        jobject yBuf, jobject uBuf, jobject vBuf,
        jint width, jint height,
        jint yRowStride, jint uvRowStride, jint uvPixelStride,
        jint rotationDeg,
        jboolean peaking, jint peakThreshold, jint peakColor,
        jboolean zebra, jint zebraThreshold, jint zebraPhase,
        jfloatArray meterRect,
        jobject overlayBitmap,
        jintArray outHist,
        jfloatArray outStats) {
    if (!yBuf || !uBuf || !vBuf || !outHist || !outStats || !meterRect) return JNI_FALSE;
    if (env->GetArrayLength(outHist) < 1024 || env->GetArrayLength(outStats) < 8 ||
        env->GetArrayLength(meterRect) < 4) {
        return JNI_FALSE;
    }

    auralcam::AnalysisInput in{};
    in.y = static_cast<const uint8_t *>(env->GetDirectBufferAddress(yBuf));
    in.u = static_cast<const uint8_t *>(env->GetDirectBufferAddress(uBuf));
    in.v = static_cast<const uint8_t *>(env->GetDirectBufferAddress(vBuf));
    in.yCap = static_cast<size_t>(env->GetDirectBufferCapacity(yBuf));
    in.uCap = static_cast<size_t>(env->GetDirectBufferCapacity(uBuf));
    in.vCap = static_cast<size_t>(env->GetDirectBufferCapacity(vBuf));
    if (!in.y || !in.u || !in.v) return JNI_FALSE;

    in.width = width;
    in.height = height;
    in.yRowStride = yRowStride;
    in.uvRowStride = uvRowStride;
    in.uvPixelStride = uvPixelStride;
    in.rotationDeg = rotationDeg;
    in.peaking = peaking == JNI_TRUE;
    in.peakThreshold = peakThreshold;
    in.peakColorArgb = static_cast<uint32_t>(peakColor);
    in.zebra = zebra == JNI_TRUE;
    in.zebraThreshold = zebraThreshold;
    in.zebraPhase = zebraPhase;
    env->GetFloatArrayRegion(meterRect, 0, 4, in.meter);

    auralcam::AnalysisOutput out{};

    // Lock the overlay bitmap only if it matches the rotated frame exactly.
    bool locked = false;
    if (overlayBitmap) {
        AndroidBitmapInfo info;
        int ow = 0, oh = 0;
        auralcam::outputDims(width, height, rotationDeg, ow, oh);
        if (AndroidBitmap_getInfo(env, overlayBitmap, &info) == ANDROID_BITMAP_RESULT_SUCCESS &&
            info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
            static_cast<int>(info.width) == ow && static_cast<int>(info.height) == oh) {
            void *pixels = nullptr;
            if (AndroidBitmap_lockPixels(env, overlayBitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS &&
                pixels != nullptr) {
                locked = true;
                out.overlay = static_cast<uint8_t *>(pixels);
                out.overlayStride = static_cast<int>(info.stride);
                out.overlayWidth = ow;
                out.overlayHeight = oh;
            }
        } else {
            LOGW("overlay bitmap does not match frame (%dx%d rot %d)", width, height, rotationDeg);
        }
    }

    const bool ok = auralcam::analyzeYuv(in, out);

    if (locked) AndroidBitmap_unlockPixels(env, overlayBitmap);
    if (!ok) return JNI_FALSE;

    env->SetIntArrayRegion(outHist, 0, 1024, reinterpret_cast<const jint *>(out.hist));
    env->SetFloatArrayRegion(outStats, 0, 8, out.stats);
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_keiranhaas_auralcam_NativeBridge_mergeBegin(JNIEnv *, jclass, jint width, jint height, jint frames) {
    return reinterpret_cast<jlong>(auralcam::mergerCreate(width, height, frames));
}

JNIEXPORT jboolean JNICALL
Java_com_keiranhaas_auralcam_NativeBridge_mergeAdd(JNIEnv *env, jclass, jlong handle, jobject buf, jint rowStrideBytes) {
    if (!handle || !buf) return JNI_FALSE;
    auto *data = static_cast<const uint8_t *>(env->GetDirectBufferAddress(buf));
    if (!data) return JNI_FALSE;
    const size_t cap = static_cast<size_t>(env->GetDirectBufferCapacity(buf));
    return auralcam::mergerAdd(reinterpret_cast<auralcam::RawMerger *>(handle), data, cap, rowStrideBytes)
           ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_keiranhaas_auralcam_NativeBridge_mergeFinish(JNIEnv *env, jclass, jlong handle, jobject out) {
    if (!handle || !out) return JNI_FALSE;
    auto *dst = static_cast<uint8_t *>(env->GetDirectBufferAddress(out));
    if (!dst) return JNI_FALSE;
    const size_t cap = static_cast<size_t>(env->GetDirectBufferCapacity(out));
    return auralcam::mergerFinish(reinterpret_cast<auralcam::RawMerger *>(handle), dst, cap)
           ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_keiranhaas_auralcam_NativeBridge_mergeDestroy(JNIEnv *, jclass, jlong handle) {
    auralcam::mergerDestroy(reinterpret_cast<auralcam::RawMerger *>(handle));
}

}  // extern "C"