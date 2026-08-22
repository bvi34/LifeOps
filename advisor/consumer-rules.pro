# Rules that travel with :advisor into whatever app consumes it.
#
# The on-device model backend crosses into C++ (libadvisor-llm.so), and a shrinker only sees the
# Kotlin half of that boundary: to R8, these members look unreachable. Renaming or removing them does
# not fail the build or throw at startup — the native side simply cannot find what it is looking for,
# and the failure surfaces much later as "the model answers but never streams", or as an
# UnsatisfiedLinkError on first use.

# The JNI entry points themselves (nativeLoad / nativeGenerate / nativeEmbed / …). AGP's default
# rules already keep native method names; this also keeps the classes that declare them.
-keepclasseswithmembernames class com.advisor.app.llm.** {
    native <methods>;
}

# The reverse direction: the callback the native generate loop invokes for each piece of the answer as
# it is produced, resolved with GetMethodID("onToken", "([B)V"). Its name and signature are a contract
# with advisor_llm.cpp — change one and you must change the other.
-keepclassmembers class com.advisor.app.llm.LlamaCppBackend$TokenSink {
    void onToken(byte[]);
}
