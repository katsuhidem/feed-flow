// Thin JNI layer over llama.cpp for translation with PLaMo 3 Translate.
// One model and one context. load, countTokens, translate and unload take
// the same lock; cancel() does not and may be called from any thread.
//
// Decisions confirmed in the Phase 0c spike (docs/spike/TRANSLATE_LOG.md):
// - tokenize with add_special (one BOS) and parse_special (control markers)
// - greedy sampling
// - stop at <|plamo:tag|> (16), <|plamo:bos|> (1) or any end-of-generation token
// - clear the KV cache before every call

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "LlamaBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Finish reasons, mirrored in LlamaBridge.kt.
constexpr int FINISH_STOP = 0;
constexpr int FINISH_MAX_TOKENS = 1;
constexpr int FINISH_CANCELLED = 2;
constexpr int FINISH_ERROR = 3;

constexpr llama_token TOK_TAG = 16;
constexpr llama_token TOK_BOS = 1;

std::mutex g_mu;
llama_model *g_model = nullptr;
llama_context *g_ctx = nullptr;
llama_sampler *g_smpl = nullptr;
const llama_vocab *g_vocab = nullptr;
int g_n_ctx = 0;
bool g_backend_init = false;

// Cancellation is per call: cancel(id) marks one call, so a late cancel of a
// finished call can never stop the next one, and a cancel that arrives
// before the call starts is not lost.
std::atomic<jlong> g_cancelled_id{-1};
std::atomic<jlong> g_current_id{-2};

bool cancelled() { return g_cancelled_id.load() == g_current_id.load(); }
bool abort_cb(void *) { return cancelled(); }

void log_cb(ggml_log_level level, const char *text, void *) {
    // Errors only. GGML_LOG_LEVEL_CONT (progress dots) is numerically above ERROR, so compare for equality.
    if (level == GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_ERROR, "llama", "%s", text);
}

std::vector<llama_token> tokenize(const std::string &text) {
    int n = -llama_tokenize(g_vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, true);
    if (n <= 0) return {};
    std::vector<llama_token> toks(n);
    int got = llama_tokenize(g_vocab, text.c_str(), (int32_t) text.size(), toks.data(), n, true, true);
    if (got < 0) return {};
    toks.resize(got);
    return toks;
}

// Length of the longest prefix of s that is complete UTF-8, so a multi-byte
// character split across tokens is never handed to Java half-finished.
size_t valid_utf8_prefix(const std::string &s) {
    size_t i = 0, ok = 0;
    while (i < s.size()) {
        unsigned char c = (unsigned char) s[i];
        size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        if (i + len > s.size()) break;
        i += len;
        ok = i;
    }
    return ok;
}

jbyteArray to_bytes(JNIEnv *env, const std::string &s, size_t n) {
    jbyteArray a = env->NewByteArray((jsize) n);
    env->SetByteArrayRegion(a, 0, (jsize) n, reinterpret_cast<const jbyte *>(s.data()));
    return a;
}

std::string to_string(JNIEnv *env, jbyteArray bytes) {
    jsize n = env->GetArrayLength(bytes);
    std::string s((size_t) n, '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

void free_locked() {
    if (g_smpl) llama_sampler_free(g_smpl);
    if (g_ctx) llama_free(g_ctx);
    if (g_model) llama_model_free(g_model);
    g_smpl = nullptr;
    g_ctx = nullptr;
    g_model = nullptr;
    g_vocab = nullptr;
    g_n_ctx = 0;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_io_github_katsuhidem_llama_LlamaBridge_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_model) return JNI_TRUE;
    if (!g_backend_init) {
        llama_log_set(log_cb, nullptr);
        llama_backend_init();
        g_backend_init = true;
    }
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!g_model) {
        LOGE("model load failed");
        return JNI_FALSE;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = (uint32_t) n_ctx;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf = true;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        LOGE("context init failed");
        free_locked();
        return JNI_FALSE;
    }
    llama_set_abort_callback(g_ctx, abort_cb, nullptr);
    g_vocab = llama_model_get_vocab(g_model);
    g_n_ctx = n_ctx;
    g_smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_smpl, llama_sampler_init_greedy());
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_katsuhidem_llama_LlamaBridge_nativeIsLoaded(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lk(g_mu);
    return g_model != nullptr ? JNI_TRUE : JNI_FALSE;
}

// Text crosses JNI as UTF-8 bytes: JNI's "modified UTF-8" strings break
// characters outside the BMP (emoji) and embedded NULs.
JNIEXPORT jint JNICALL
Java_io_github_katsuhidem_llama_LlamaBridge_nativeCountTokens(JNIEnv *env, jobject, jbyteArray jtext) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_vocab) return -1;
    return (jint) tokenize(to_string(env, jtext)).size();
}

// Callback: onPartial(utf8: ByteArray), onFinish(reason: Int, utf8: ByteArray, error: String?)
JNIEXPORT void JNICALL
Java_io_github_katsuhidem_llama_LlamaBridge_nativeTranslate(
        JNIEnv *env, jobject, jlong call_id, jbyteArray jprompt, jint max_tokens, jobject cb) {
    std::lock_guard<std::mutex> lk(g_mu);
    g_current_id.store(call_id);

    jclass cls = env->GetObjectClass(cb);
    jmethodID on_partial = env->GetMethodID(cls, "onPartial", "([B)V");
    jmethodID on_finish = env->GetMethodID(cls, "onFinish", "(I[BLjava/lang/String;)V");

    std::string out;
    auto finish = [&](int reason, const char *err) {
        jstring jerr = err ? env->NewStringUTF(err) : nullptr;
        env->CallVoidMethod(cb, on_finish, reason, to_bytes(env, out, valid_utf8_prefix(out)), jerr);
        g_current_id.store(-2);
    };

    if (!g_ctx) return finish(FINISH_ERROR, "model is not loaded");
    if (cancelled()) return finish(FINISH_CANCELLED, nullptr);
    // Each call starts from an empty KV cache.
    llama_memory_clear(llama_get_memory(g_ctx), true);
    llama_sampler_reset(g_smpl);

    std::vector<llama_token> toks = tokenize(to_string(env, jprompt));
    if (toks.empty()) return finish(FINISH_ERROR, "tokenize failed");
    if ((int) toks.size() + max_tokens > g_n_ctx) return finish(FINISH_ERROR, "prompt + max_tokens exceeds n_ctx");

    int rc = llama_decode(g_ctx, llama_batch_get_one(toks.data(), (int32_t) toks.size()));
    if (rc == 2 || cancelled()) return finish(FINISH_CANCELLED, nullptr);
    if (rc != 0) return finish(FINISH_ERROR, "decode of the prompt failed");

    char buf[256];
    size_t sent = 0;
    for (int i = 0; i < max_tokens; i++) {
        if (cancelled()) return finish(FINISH_CANCELLED, nullptr);
        llama_token t = llama_sampler_sample(g_smpl, g_ctx, -1);
        if (t == TOK_TAG || t == TOK_BOS || llama_vocab_is_eog(g_vocab, t)) return finish(FINISH_STOP, nullptr);
        int n = llama_token_to_piece(g_vocab, t, buf, sizeof(buf), 0, false);
        if (n > 0) out.append(buf, n);
        size_t valid = valid_utf8_prefix(out);
        if (valid > sent) {
            sent = valid;
            jbyteArray part = to_bytes(env, out, valid);
            env->CallVoidMethod(cb, on_partial, part);
            env->DeleteLocalRef(part);
        }
        rc = llama_decode(g_ctx, llama_batch_get_one(&t, 1));
        if (rc == 2 || cancelled()) return finish(FINISH_CANCELLED, nullptr);
        if (rc != 0) return finish(FINISH_ERROR, "decode failed");
    }
    finish(FINISH_MAX_TOKENS, nullptr);
}

JNIEXPORT void JNICALL
Java_io_github_katsuhidem_llama_LlamaBridge_nativeCancel(JNIEnv *, jobject, jlong call_id) {
    g_cancelled_id.store(call_id);
}

JNIEXPORT void JNICALL
Java_io_github_katsuhidem_llama_LlamaBridge_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lk(g_mu);
    free_locked();
}

}  // extern "C"
