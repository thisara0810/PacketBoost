#include <jni.h>
#include <string>
#include <cstdlib>
#include <android/log.h>

#define LOG_TAG "PacketBoostJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// C declarations for functions exported by libpacketboost_core.so (Go c-shared)
extern "C" {
    typedef int32_t GoInt32;
    typedef int64_t GoInt64;

    extern GoInt32 startNativeCore(GoInt32 tunFd, char* serverAddr, char* secretKey, GoInt32 mtu);
    extern GoInt32 stopNativeCore();
    extern char* getNativeCoreStats();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_packetboost_app_NativeCoreBridge_startNativeCore(
        JNIEnv* env,
        jobject thiz,
        jint tunFd,
        jstring serverAddr,
        jstring secretKey,
        jint mtu) {

    const char* c_serverAddr = env->GetStringUTFChars(serverAddr, nullptr);
    const char* c_secretKey = env->GetStringUTFChars(secretKey, nullptr);

    LOGI("JNI: Invoking startNativeCore (tunFd=%d, Server=%s, MTU=%d)", tunFd, c_serverAddr, mtu);

    jint result = startNativeCore(
            static_cast<GoInt32>(tunFd),
            const_cast<char*>(c_serverAddr),
            const_cast<char*>(c_secretKey),
            static_cast<GoInt32>(mtu)
    );

    env->ReleaseStringUTFChars(serverAddr, c_serverAddr);
    env->ReleaseStringUTFChars(secretKey, c_secretKey);

    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_packetboost_app_NativeCoreBridge_stopNativeCore(
        JNIEnv* env,
        jobject thiz) {

    LOGI("JNI: Invoking stopNativeCore()");
    jint result = stopNativeCore();
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_packetboost_app_NativeCoreBridge_getNativeCoreStats(
        JNIEnv* env,
        jobject thiz) {

    char* c_stats = getNativeCoreStats();
    jstring result = env->NewStringUTF(c_stats);
    std::free(c_stats);
    return result;
}
