// Thin JNI over llama.cpp for the Phase 0c translation spike.
// Exposes load, countTokens, translate, cancel and unload.
// One model and one context; calls are expected from one worker thread,
// except cancel(), which may come from any thread.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "TLSJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Finish reasons, mirrored in Kotlin.
constexpr int FINISH_STOP = 0;
constexpr int FINISH_MAX_TOKENS = 1;
constexpr int FINISH_CANCELLED = 2;
constexpr int FINISH_ERROR = 3;

// Stop tokens from the GGUF vocab (confirmed on PC, TRANSLATE_LOG step 1):
// <|plamo:tag|> = 16, <|plamo:bos|> = 1 (also EOS). EOG is checked too.
constexpr llama_token TOK_TAG = 16;
constexpr llama_token TOK_BOS = 1;

std::mutex g_mu;
llama_model *g_model = nullptr;
llama_context *g_ctx = nullptr;
llama_sampler *g_smpl = nullptr;
const llama_vocab *g_vocab = nullptr;
int g_n_ctx = 0;
std::atomic<bool> g_cancel{false};
bool g_backend_init = false;

bool abort_cb(void *) { return g_cancel.load(); }

void log_cb(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, "llama", "%s", text);
}

std::vector<llama_token> tokenize(const std::string &text) {
    // add_special=true adds the single BOS; parse_special=true turns the
    // <|plamo:...|> markers into their special tokens. Both are needed.
    int n = -llama_tokenize(g_vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, true);
    if (n <= 0) return {};
    std::vector<llama_token> toks(n);
    int got = llama_tokenize(g_vocab, text.c_str(), (int32_t) text.size(), toks.data(), n, true, true);
    if (got < 0) return {};
    toks.resize(got);
    return toks;
}

// Length of the longest prefix of s that is complete UTF-8.
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

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_io_github_katsuhidem_tlspike_Translator_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
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
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }
    llama_set_abort_callback(g_ctx, abort_cb, nullptr);
    g_vocab = llama_model_get_vocab(g_model);
    g_n_ctx = n_ctx;
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    g_smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(g_smpl, llama_sampler_init_greedy());
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_io_github_katsuhidem_tlspike_Translator_nativeCountTokens(JNIEnv *env, jobject, jstring jprompt) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_vocab) return -1;
    const char *p = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(p);
    env->ReleaseStringUTFChars(jprompt, p);
    return (jint) tokenize(prompt).size();
}

// Callback (Kotlin): onPartial(nTokens: Int, utf8: ByteArray)
//                    onFinish(reason: Int, nPrompt: Int, tokens: IntArray, utf8: ByteArray, error: String?)
JNIEXPORT void JNICALL
Java_io_github_katsuhidem_tlspike_Translator_nativeTranslate(JNIEnv *env, jobject, jstring jprompt, jint max_tokens, jobject cb) {
    std::lock_guard<std::mutex> lk(g_mu);
    jclass cls = env->GetObjectClass(cb);
    jmethodID on_partial = env->GetMethodID(cls, "onPartial", "(I[B)V");
    jmethodID on_finish = env->GetMethodID(cls, "onFinish", "(II[I[BLjava/lang/String;)V");

    std::string out;
    std::vector<llama_token> gen;
    int n_prompt = 0;
    auto finish = [&](int reason, const char *err) {
        jintArray toks = env->NewIntArray((jsize) gen.size());
        if (!gen.empty()) env->SetIntArrayRegion(toks, 0, (jsize) gen.size(), reinterpret_cast<const jint *>(gen.data()));
        jstring jerr = err ? env->NewStringUTF(err) : nullptr;
        env->CallVoidMethod(cb, on_finish, reason, n_prompt, toks, to_bytes(env, out, valid_utf8_prefix(out)), jerr);
    };

    if (!g_ctx) return finish(FINISH_ERROR, "not loaded");
    // Each call starts from an empty KV cache.
    llama_memory_clear(llama_get_memory(g_ctx), true);
    llama_sampler_reset(g_smpl);

    const char *p = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(p);
    env->ReleaseStringUTFChars(jprompt, p);
    std::vector<llama_token> toks = tokenize(prompt);
    n_prompt = (int) toks.size();
    if (toks.empty()) return finish(FINISH_ERROR, "tokenize failed");
    if (n_prompt + max_tokens > g_n_ctx) return finish(FINISH_ERROR, "prompt + max_tokens exceeds n_ctx");

    int rc = llama_decode(g_ctx, llama_batch_get_one(toks.data(), (int32_t) toks.size()));
    if (rc == 2 || g_cancel.load()) return finish(FINISH_CANCELLED, nullptr);
    if (rc != 0) return finish(FINISH_ERROR, "decode prompt failed");

    char buf[256];
    for (int i = 0; i < max_tokens; i++) {
        if (g_cancel.load()) return finish(FINISH_CANCELLED, nullptr);
        llama_token t = llama_sampler_sample(g_smpl, g_ctx, -1);
        if (t == TOK_TAG || t == TOK_BOS || llama_vocab_is_eog(g_vocab, t)) return finish(FINISH_STOP, nullptr);
        gen.push_back(t);
        int n = llama_token_to_piece(g_vocab, t, buf, sizeof(buf), 0, false);
        if (n > 0) out.append(buf, n);
        env->CallVoidMethod(cb, on_partial, (jint) gen.size(), to_bytes(env, out, valid_utf8_prefix(out)));
        rc = llama_decode(g_ctx, llama_batch_get_one(&t, 1));
        if (rc == 2 || g_cancel.load()) return finish(FINISH_CANCELLED, nullptr);
        if (rc != 0) return finish(FINISH_ERROR, "decode failed");
    }
    finish(FINISH_MAX_TOKENS, nullptr);
}

JNIEXPORT void JNICALL
Java_io_github_katsuhidem_tlspike_Translator_nativeCancel(JNIEnv *, jobject) {
    g_cancel.store(true);
}

JNIEXPORT void JNICALL
Java_io_github_katsuhidem_tlspike_Translator_nativeResetCancel(JNIEnv *, jobject) {
    g_cancel.store(false);
}

JNIEXPORT void JNICALL
Java_io_github_katsuhidem_tlspike_Translator_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_smpl) llama_sampler_free(g_smpl);
    if (g_ctx) llama_free(g_ctx);
    if (g_model) llama_model_free(g_model);
    g_smpl = nullptr;
    g_ctx = nullptr;
    g_model = nullptr;
    g_vocab = nullptr;
}

}  // extern "C"
