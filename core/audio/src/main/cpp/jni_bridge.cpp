#include <jni.h>

#include "audio_engine.h"

using songnotes::NativeAudioEngine;

namespace {

inline NativeAudioEngine *toEngine(jlong handle) {
    return reinterpret_cast<NativeAudioEngine *>(handle);
}

jstring toJString(JNIEnv *env, const std::string &s) {
    return env->NewStringUTF(s.c_str());
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeCreate(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new NativeAudioEngine());
}

JNIEXPORT jboolean JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeStartTestTone(JNIEnv *, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return engine != nullptr && engine->startTestTone();
}

JNIEXPORT void JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeStop(JNIEnv *, jobject, jlong handle) {
    if (auto *engine = toEngine(handle)) {
        engine->stop();
    }
}

JNIEXPORT void JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    delete toEngine(handle);
}

JNIEXPORT jstring JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetAudioApi(JNIEnv *env, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return toJString(env, engine ? engine->audioApi() : "-");
}

JNIEXPORT jint JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetSampleRate(JNIEnv *, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return engine ? engine->sampleRate() : 0;
}

JNIEXPORT jint JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetFramesPerBurst(JNIEnv *, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return engine ? engine->framesPerBurst() : 0;
}

JNIEXPORT jint JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetChannelCount(JNIEnv *, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return engine ? engine->channelCount() : 0;
}

JNIEXPORT jstring JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetFormat(JNIEnv *env, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return toJString(env, engine ? engine->format() : "-");
}

JNIEXPORT jstring JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetSharingMode(JNIEnv *env, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return toJString(env, engine ? engine->sharingMode() : "-");
}

JNIEXPORT jstring JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetPerformanceMode(JNIEnv *env, jobject,
                                                                     jlong handle) {
    auto *engine = toEngine(handle);
    return toJString(env, engine ? engine->performanceMode() : "-");
}

JNIEXPORT jboolean JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeIsMMapUsed(JNIEnv *, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return engine != nullptr && engine->isMMapUsed();
}

JNIEXPORT jint JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetXRunCount(JNIEnv *, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return engine ? engine->xRunCount() : 0;
}

JNIEXPORT jstring JNICALL
Java_com_songnotes_core_audio_AudioEngine_nativeGetLastError(JNIEnv *env, jobject, jlong handle) {
    auto *engine = toEngine(handle);
    return toJString(env, engine ? engine->lastError() : "");
}

} // extern "C"
