# --- JNI bridge (hev-socks5-tunnel) -----------------------------------------
# The natives are registered against these exact class/method names in
# JNI_OnLoad, so R8 must not rename, remove, or optimize them away. This rule is
# required once minifyEnabled is on, otherwise System.loadLibrary() succeeds but
# every TProxy* call resolves to a missing method and the VPN silently falls
# back to system-proxy mode.
-keep class hev.htproxy.** { *; }
-keepclasseswithmembers,allowoptimization,includedescriptorclasses class hev.htproxy.** {
    native <methods>;
}
-dontwarn hev.htproxy.**

-keep class com.hotshare.** { *; }
-dontwarn com.google.zxing.**
