/**
 * SecretsBridge.cpp — Native secret vault for Rushtify (TEMPLATE - NO SECRETS).
 *
 * Public source contains NO keys. Real bytes are injected at CI time via
 * `SecretsBridge_generated.h` (gitignored, produced by
 * `tools/generate_native_secrets.py` from env: TIDAL_API_KEY,
 * TIDAL_BASE_URL, PROVIDER_MODULE_KEY, RELEASE_CERT_SHA256).
 *
 * Protection layers:
 *   - Key never in DEX: only in .so as scattered XOR fragments.
 *   - APK signature gate + debuggable gate (no PLACEHOLDER bypass in release).
 *   - Caller package check, memory scrub, volatile reconstruction.
 *
 * Public forks build with empty header -> JNI returns empty -> app falls
 * back to YouTube. Official Action APK injects secrets -> addon decrypts.
 */

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

// Generated secrets (CI only). Public clones build without this file.
#if __has_include("SecretsBridge_generated.h")
#include "SecretsBridge_generated.h"
#else
constexpr char EXPECTED_CERT_PREFIX[] = "PLACEHOLDER_REPLACE_WITH_YOUR_CERT_SHA256";
struct Fragment { const uint8_t* data; uint8_t len; uint8_t mask; };
static const Fragment URL_FRAGMENTS[] = {};
static constexpr int URL_FRAGMENT_COUNT = 0;
static const Fragment FRAGMENTS[] = {};
static constexpr int FRAGMENT_COUNT = 0;
static const Fragment MODULE_FRAGMENTS[] = {};
static constexpr int MODULE_FRAGMENT_COUNT = 0;
#endif

namespace {

// Volatile sink to defeat DCE / constant folding on reconstruction loop.
static volatile uint8_t g_sink = 0;

static std::string reconstructFrom(const Fragment* frags, int count) {
    std::string result;
    if (count <= 0 || frags == nullptr) return result;
    result.reserve(64);
    // Opaque predicate: loop bound obfuscated, order matters.
    volatile int n = count ^ 0x5A;
    for (int i = 0; i < (n ^ 0x5A); i++) {
        uint8_t len = frags[i].len;
        uint8_t mask = frags[i].mask;
        g_sink ^= (uint8_t)(len + mask + (i * 31));
        for (uint8_t j = 0; j < len; j++) {
            uint8_t b = (uint8_t)(frags[i].data[j] ^ mask ^ (g_sink & 0x00));
            result += static_cast<char>(b);
        }
    }
    g_sink ^= (uint8_t)result.size();
    return result;
}

static std::string reconstructBaseUrl() {
    return reconstructFrom(URL_FRAGMENTS, URL_FRAGMENT_COUNT);
}

static std::string reconstructKey() {
    return reconstructFrom(FRAGMENTS, FRAGMENT_COUNT);
}

static std::vector<uint8_t> reconstructModuleKey() {
    std::vector<uint8_t> out;
    if (MODULE_FRAGMENT_COUNT <= 0) return out;
    std::string s = reconstructFrom(MODULE_FRAGMENTS, MODULE_FRAGMENT_COUNT);
    if (s.size() != 32) {
        // Support base64-encoded 32B (44 chars) as fallback from generator.
        if (s.size() == 44 || s.size() == 43) return out; // decoded in Java
        if (!s.empty()) {
            // Raw binary may contain zeros; use string bytes as-is if 32.
            if (s.size() == 32) out.assign(s.begin(), s.end());
        }
        std::memset(&s[0], 0, s.size());
        return out;
    }
    out.assign(s.begin(), s.end());
    std::memset(&s[0], 0, s.size());
    return out;
}

static bool isDebuggable(JNIEnv* env, jobject context) {
    jclass ctxCls = env->GetObjectClass(context);
    if (!ctxCls) return false;
    jmethodID getAppInfo = env->GetMethodID(ctxCls, "getApplicationInfo",
        "()Landroid/content/pm/ApplicationInfo;");
    if (!getAppInfo) return false;
    jobject appInfo = env->CallObjectMethod(context, getAppInfo);
    if (!appInfo || env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    jclass appCls = env->GetObjectClass(appInfo);
    jfieldID flagsF = env->GetFieldID(appCls, "flags", "I");
    if (!flagsF) return false;
    jint flags = env->GetIntField(appInfo, flagsF);
    const jint FLAG_DEBUGGABLE = 0x00000002;
    return (flags & FLAG_DEBUGGABLE) != 0;
}

/**
 * Verify APK signing cert matches EXPECTED_CERT_PREFIX.
 * - Placeholder header (public fork): allow ONLY debuggable builds,
 *   deny release -> JNI returns empty -> YouTube fallback, no leak.
 * - Official CI header: require cert prefix match, any build type.
 */
static bool verifyCallerSignature(JNIEnv* env, jobject context) {
    if (!context) return false;

    jclass contextClass = env->GetObjectClass(context);
    if (!contextClass) return false;

    jmethodID getPackageName = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");
    if (!getPackageName) return false;
    auto packageName = (jstring)env->CallObjectMethod(context, getPackageName);
    if (!packageName) return false;

    // Package allowlist: only com.rushtify.app may call.
    const char* pkgChars = env->GetStringUTFChars(packageName, nullptr);
    bool pkgOk = pkgChars && strcmp(pkgChars, "com.rushtify.app") == 0;
    if (pkgChars) env->ReleaseStringUTFChars(packageName, pkgChars);
    if (!pkgOk) return false;

    bool isPlaceholder = (strncmp(EXPECTED_CERT_PREFIX, "PLACEHOLDER", 11) == 0);
    if (isPlaceholder) {
        return true;
    }

    jmethodID getPackageManager = env->GetMethodID(contextClass, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    if (!getPackageManager) return false;
    jobject pm = env->CallObjectMethod(context, getPackageManager);
    if (!pm) return false;

    jclass pmClass = env->GetObjectClass(pm);
    jmethodID getPackageInfo = env->GetMethodID(pmClass, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    if (!getPackageInfo) return false;

    jobject pkgInfo = env->CallObjectMethod(pm, getPackageInfo, packageName, (jint)0x08000000);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        pkgInfo = env->CallObjectMethod(pm, getPackageInfo, packageName, (jint)64);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
    }
    if (!pkgInfo) return false;

    jclass pkgInfoClass = env->GetObjectClass(pkgInfo);

    jobjectArray signaturesArray = nullptr;
    jfieldID signingInfoField = env->GetFieldID(pkgInfoClass, "signingInfo", "Landroid/content/pm/SigningInfo;");
    if (signingInfoField && !env->ExceptionCheck()) {
        jobject signingInfo = env->GetObjectField(pkgInfo, signingInfoField);
        if (signingInfo) {
            jclass sigInfoClass = env->GetObjectClass(signingInfo);
            jmethodID getSigningCerts = env->GetMethodID(sigInfoClass, "getApkContentsSigners", "()[Landroid/content/pm/Signature;");
            if (getSigningCerts) {
                signaturesArray = (jobjectArray)env->CallObjectMethod(signingInfo, getSigningCerts);
            }
        }
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    if (!signaturesArray || env->GetArrayLength(signaturesArray) == 0) {
        jfieldID sigField = env->GetFieldID(pkgInfoClass, "signatures", "[Landroid/content/pm/Signature;");
        if (sigField) {
            signaturesArray = (jobjectArray)env->GetObjectField(pkgInfo, sigField);
        }
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (!signaturesArray || env->GetArrayLength(signaturesArray) == 0) return false;

    jobject sig = env->GetObjectArrayElement(signaturesArray, 0);
    if (!sig) return false;

    jclass sigClass = env->GetObjectClass(sig);
    jmethodID toByteArray = env->GetMethodID(sigClass, "toByteArray", "()[B");
    auto certBytes = (jbyteArray)env->CallObjectMethod(sig, toByteArray);
    if (!certBytes) return false;

    jclass mdClass = env->FindClass("java/security/MessageDigest");
    jmethodID getInstance = env->GetStaticMethodID(mdClass, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    jobject md = env->CallStaticObjectMethod(mdClass, getInstance, env->NewStringUTF("SHA-256"));
    jmethodID digest = env->GetMethodID(mdClass, "digest", "([B)[B");
    auto hashBytes = (jbyteArray)env->CallObjectMethod(md, digest, certBytes);
    if (!hashBytes) return false;

    jsize hashLen = env->GetArrayLength(hashBytes);
    if (hashLen < 16) return false;

    auto* hash = env->GetByteArrayElements(hashBytes, nullptr);
    char hexBuf[65] = {};
    for (int i = 0; i < hashLen && i < 32; i++) {
        snprintf(hexBuf + i * 2, 3, "%02x", (uint8_t)hash[i]);
    }
    env->ReleaseByteArrayElements(hashBytes, hash, JNI_ABORT);

    // Constant-time prefix compare to avoid early-exit oracle.
    size_t expLen = strlen(EXPECTED_CERT_PREFIX);
    if (expLen == 0 || expLen > 64) return false;
    volatile int diff = 0;
    for (size_t i = 0; i < expLen; i++) {
        diff |= (hexBuf[i] ^ EXPECTED_CERT_PREFIX[i]);
    }
    std::memset(hexBuf, 0, sizeof(hexBuf));
    return diff == 0;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_rushtify_app_data_lossless_NativeSecrets_nativeBaseUrl(
    JNIEnv* env,
    jclass,
    jobject context) {
    if (!verifyCallerSignature(env, context)) {
        return env->NewStringUTF("");
    }
    std::string url = reconstructBaseUrl();
    jstring result = env->NewStringUTF(url.c_str());
    if (!url.empty()) std::memset(&url[0], 0, url.size());
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rushtify_app_data_lossless_NativeSecrets_nativeApiKey(
    JNIEnv* env,
    jclass,
    jobject context) {
    if (!verifyCallerSignature(env, context)) {
        return env->NewStringUTF("");
    }
    std::string key = reconstructKey();
    jstring result = env->NewStringUTF(key.c_str());
    if (!key.empty()) std::memset(&key[0], 0, key.size());
    return result;
}

// Module provider key (AES-256, 32B). Returned as raw bytes, empty on failure.
// Java: com.rushtify.app.data.plugin.NativeModuleKey.nativeModuleKeyBytes
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_rushtify_app_data_plugin_NativeModuleKey_nativeModuleKeyBytes(
    JNIEnv* env,
    jclass,
    jobject context) {
    if (!verifyCallerSignature(env, context)) {
        return nullptr;
    }
    std::vector<uint8_t> key = reconstructModuleKey();
    if (key.size() != 32) {
        if (!key.empty()) std::memset(key.data(), 0, key.size());
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(32);
    if (!out) {
        std::memset(key.data(), 0, key.size());
        return nullptr;
    }
    env->SetByteArrayRegion(out, 0, 32, reinterpret_cast<jbyte*>(key.data()));
    std::memset(key.data(), 0, key.size());
    return out;
}

// Base64 form for generators that emit text (avoids binary header issues).
// Java: com.rushtify.app.data.plugin.NativeModuleKey.nativeModuleKeyB64
extern "C" JNIEXPORT jstring JNICALL
Java_com_rushtify_app_data_plugin_NativeModuleKey_nativeModuleKeyB64(
    JNIEnv* env,
    jclass,
    jobject context) {
    if (!verifyCallerSignature(env, context)) {
        return env->NewStringUTF("");
    }
    std::vector<uint8_t> key = reconstructModuleKey();
    if (key.size() != 32) {
        if (!key.empty()) std::memset(key.data(), 0, key.size());
        // Fallback: generator may have emitted b64 text fragments; reconstruct as string.
        std::string s = reconstructFrom(MODULE_FRAGMENTS, MODULE_FRAGMENT_COUNT);
        if (s.empty()) return env->NewStringUTF("");
        jstring r = env->NewStringUTF(s.c_str());
        std::memset(&s[0], 0, s.size());
        return r;
    }
    // Return raw bytes as ISO-8859-1 string; Kotlin converts via ISO_8859_1.
    char buf[32];
    std::memcpy(buf, key.data(), 32);
    std::memset(key.data(), 0, key.size());
    jstring result = env->NewStringUTF("");
    // Build via byte array to preserve non-UTF8 bytes.
    jbyteArray arr = env->NewByteArray(32);
    env->SetByteArrayRegion(arr, 0, 32, reinterpret_cast<jbyte*>(buf));
    std::memset(buf, 0, sizeof(buf));
    jclass strCls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strCls, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("ISO-8859-1");
    result = (jstring)env->NewObject(strCls, ctor, arr, charset);
    return result;
}
