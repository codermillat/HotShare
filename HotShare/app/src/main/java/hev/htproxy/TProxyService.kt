package hev.htproxy

/**
 * Shim matching hev-socks5-tunnel's default JNI contract (PKGNAME=hev/htproxy).
 * The natives are registered to these exact static methods in JNI_OnLoad.
 */
class TProxyService {
    companion object {
        /** False when the native jniLibs are missing — caller falls back to system-proxy mode. */
        @Volatile var isAvailable: Boolean = false
            private set

        init {
            isAvailable = try {
                System.loadLibrary("hev-socks5-tunnel")
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            } catch (_: Exception) {
                false
            }
        }

        @JvmStatic private external fun TProxyStartService(config_path: String, fd: Int): Boolean
        @JvmStatic private external fun TProxyStopService(): Boolean
        @JvmStatic private external fun TProxyIsRunning(): Boolean
        @JvmStatic private external fun TProxyGetStats(): LongArray

        // Public, callable wrappers (external funs stay private like the reference impl).
        @JvmStatic fun startService(configPath: String, fd: Int): Boolean =
            if (!isAvailable) false else try { TProxyStartService(configPath, fd) } catch (_: Throwable) { false }
        @JvmStatic fun stopService(): Boolean =
            if (!isAvailable) false else try { TProxyStopService() } catch (_: Throwable) { false }
        @JvmStatic fun isRunning(): Boolean =
            if (!isAvailable) false else try { TProxyIsRunning() } catch (_: Throwable) { false }
        @JvmStatic fun getStats(): LongArray =
            if (!isAvailable) longArrayOf(0, 0) else try { TProxyGetStats() } catch (_: Throwable) { longArrayOf(0, 0) }
    }
}