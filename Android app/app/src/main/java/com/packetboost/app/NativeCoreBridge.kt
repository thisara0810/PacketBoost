package com.packetboost.app

import android.util.Log

object NativeCoreBridge {

    private const val TAG = "NativeCoreBridge"

    init {
        try {
            System.loadLibrary("packetboost_jni")
            Log.i(TAG, "Native library 'packetboost_jni' loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library 'packetboost_jni'", e)
        }
    }

    /**
     * Starts the native Go acceleration core with the given TUN file descriptor and server settings.
     * @param tunFd File descriptor returned by Android VpnService Builder establish()
     * @param serverAddr Target KCP VPS server address in format "IP:PORT"
     * @param secretKey Encryption key for AES-128 tunnel payloads
     * @param mtu TUN MTU size (default 1350)
     * @return 0 on success, non-zero on error code
     */
    external fun startNativeCore(tunFd: Int, serverAddr: String, secretKey: String, mtu: Int): Int

    /**
     * Gracefully stops the native acceleration engine and releases network resources.
     * @return 0 on success
     */
    external fun stopNativeCore(): Int

    /**
     * Retrieves JSON formatted real-time network statistics from the native core engine.
     */
    external fun getNativeCoreStats(): String
}
