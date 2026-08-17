// advisor_llm.cpp — the native side of com.advisor.app.llm.LlamaCppBackend.
//
// A thin JNI bridge over llama.cpp that loads a Qwen3-4B GGUF and runs a completion for a
// fully-formatted (ChatML) prompt, entirely on-device. It exposes exactly the three methods the
// Kotlin `external fun`s declare: nativeLoad / nativeGenerate / nativeFree.
//
// API pin: written against **llama.cpp tag b6490** — the modern API with llama_batch_init support.
// Notable shape vs. older tags:
//   * lifecycle: llama_model_load_from_file / llama_init_from_model / llama_model_free
//   * a `const llama_vocab*` handle (llama_model_get_vocab) owns tokenize / detokenize / eog
//   * cache reset is llama_memory_clear(llama_get_memory(ctx), true); embedding size is llama_model_n_embd
//   * batch API: llama_batch_init / llama_batch_free (replaced deprecated llama_batch_get_one)
// llama.cpp renames these fairly often, so if you bump the tag again, reconcile the symbols below and
// update this pin in the same change.

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>
#include <thread>
#include <chrono>

#include "llama.h"

#define LOG_TAG "advisor-llm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

void advisor_log_cb(ggml_log_level level, const char* text, void* /*user*/) {
    int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
             : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
             : ANDROID_LOG_INFO;
    __android_log_print(prio, LOG_TAG, "%s", text ? text : "");
}

void ensure_backend_ready() {
    static bool ready = false;
    if (ready) return;
    llama_log_set(advisor_log_cb, nullptr);
    llama_backend_init();
    ready = true;
}

struct AdvisorLlm {
    llama_model*       model = nullptr;
    llama_context*     ctx   = nullptr;
    const llama_vocab* vocab = nullptr;
};

std::string token_to_piece(const llama_vocab* vocab, llama_token id) {
    char buf[256];
    int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, /*special=*/false);
    if (n < 0) return {};
    return std::string(buf, n);
}

size_t first_stop(const std::string& text, const std::vector<std::string>& stops) {
    size_t best = std::string::npos;
    for (const auto& s : stops) {
        if (s.empty()) continue;
        size_t at = text.find(s);
        if (at != std::string::npos && at < best) best = at;
    }
    return best;
}

std::string prompt_head(const std::string& text) {
    std::string head = text.substr(0, 120);
    for (char& c : head) {
        if (c == '\n' || c == '\r') c = ' ';
    }
    return head;
}

uint64_t prompt_hash_value(const std::string& text) {
    uint64_t h = 1469598103934665603ULL;
    for (unsigned char c : text) {
        h ^= c;
        h *= 1099511628211ULL;
    }
    return h;
}

std::string prompt_hash(const std::string& text) {
    char buf[17];
    snprintf(buf, sizeof(buf), "%016llx", (unsigned long long) prompt_hash_value(text));
    return std::string(buf);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_advisor_app_llm_LlamaCppBackend_nativeLoad(JNIEnv* env, jobject /*thiz*/, jstring jpath) {
    ensure_backend_ready();

    const char* path = env->GetStringUTFChars(jpath, nullptr);

    llama_model_params mp = llama_model_default_params();
#ifdef ADVISOR_GPU_OFFLOAD
    mp.n_gpu_layers = 99;
#else
    mp.n_gpu_layers = 0;
#endif

    llama_model* model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (model == nullptr) {
        LOGW("llama_model_load_from_file returned null");
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 4096;
    cp.n_batch = 4096;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    unsigned hw = std::thread::hardware_concurrency();
    int threads = hw > 1 ? static_cast<int>(hw / 2) : 1;
    cp.n_threads       = threads;
    cp.n_threads_batch = threads;

    llama_context* ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        LOGW("llama_init_from_model returned null");
        llama_model_free(model);
        return 0;
    }

    auto* h = new AdvisorLlm{model, ctx, llama_model_get_vocab(model)};
    LOGI("Loaded Qwen3-4B GGUF; ctx=%d threads=%d n_gpu_layers=%d flash_attn=disabled", (int) cp.n_ctx, threads, mp.n_gpu_layers);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jstring JNICALL
Java_com_advisor_app_llm_LlamaCppBackend_nativeGenerate(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jprompt,
        jint maxTokens, jfloat temperature, jfloat topP, jint topK, jobjectArray jstops) {

    auto* h = reinterpret_cast<AdvisorLlm*>(handle);
    if (h == nullptr || h->ctx == nullptr) {
        LOGW("nativeGenerate called with no loaded model (handle/ctx null)");
        return env->NewStringUTF("");
    }
    LOGI("nativeGenerate: start (budget=%d tokens)", maxTokens);

    llama_memory_clear(llama_get_memory(h->ctx), /*data=*/true);

    std::vector<std::string> stops;
    jsize nstops = jstops ? env->GetArrayLength(jstops) : 0;
    for (jsize i = 0; i < nstops; i++) {
        auto js = (jstring) env->GetObjectArrayElement(jstops, i);
        const char* s = env->GetStringUTFChars(js, nullptr);
        stops.emplace_back(s);
        env->ReleaseStringUTFChars(js, s);
        env->DeleteLocalRef(js);
    }

    const char* prompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string text(prompt);
    env->ReleaseStringUTFChars(jprompt, prompt);
    LOGI("nativeGenerate: received prompt chars=%zu hash=%s head=%s",
         text.size(), prompt_hash(text).c_str(), prompt_head(text).c_str());

    int n_max = (int) text.size() + 8;
    std::vector<llama_token> tokens(n_max);
    int n_prompt = llama_tokenize(
            h->vocab, text.c_str(), (int) text.size(),
            tokens.data(), n_max, /*add_special=*/true, /*parse_special=*/true);
    if (n_prompt < 0) {
        LOGW("tokenization failed (%d)", n_prompt);
        return env->NewStringUTF("");
    }
    tokens.resize(n_prompt);
    LOGI("nativeGenerate: tokenized prompt=%d tokens", n_prompt);

    const int n_ctx = (int) llama_n_ctx(h->ctx);
    if (n_prompt >= n_ctx) {
        LOGW("prompt (%d tokens) exceeds context (%d); truncating head", n_prompt, n_ctx);
        tokens.erase(tokens.begin(), tokens.begin() + (n_prompt - (n_ctx - 1)));
    }

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    LOGI("nativeGenerate: prompt=%d tokens; prefill…", (int) tokens.size());
    const auto t_start = std::chrono::steady_clock::now();
    auto elapsed_ms = [&]() {
        return (long long) std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t_start).count();
    };

    std::string out;
    llama_batch batch = llama_batch_init((int) tokens.size(), 0, 1);
    for (int i = 0; i < (int) tokens.size(); i++) {
        batch.token[i]     = tokens[i];
        batch.pos[i]       = i;
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]    = (i == (int) tokens.size() - 1) ? 1 : 0;
    }
    batch.n_tokens = (int) tokens.size();

    const int budget = maxTokens > 0 ? maxTokens : 512;
    int produced = 0;
    int next_pos = (int) tokens.size();
    for (int generated = 0; generated < budget; generated++) {
        if (llama_decode(h->ctx, batch) != 0) {
            LOGW("nativeGenerate: prefill llama_decode failed at offset=%d count=%d", offset, count);
            prefill_ok = false;
            break;
        }
    }

    if (prefill_ok) {
        LOGI("nativeGenerate: prefill done in %lld ms; streaming…", elapsed_ms());
        const int budget = maxTokens > 0 ? maxTokens : 512;
        int produced = 0;
        int next_pos = (int) tokens.size();

        // Sample the first token from logits requested on the final prefill chunk, then decode
        // generated tokens one at a time.
        for (int generated = 0; generated < budget; generated++) {
            llama_token id = llama_sampler_sample(smpl, h->ctx, -1);
            if (llama_vocab_is_eog(h->vocab, id)) break;

            out += token_to_piece(h->vocab, id);
            produced++;
            if (produced % 32 == 0) LOGI("nativeGenerate: %d tokens so far (%lld ms)…", produced, elapsed_ms());

            size_t cut = first_stop(out, stops);
            if (cut != std::string::npos) {
                out.resize(cut);
                break;
            }

            batch.n_tokens = 1;
            batch.token[0]  = id;
            batch.pos[0]    = next_pos++;
            batch.n_seq_id[0]  = 1;
            batch.seq_id[0][0] = 0;
            batch.logits[0] = 1;

            if (llama_decode(h->ctx, batch) != 0) {
                LOGW("nativeGenerate: decode failed after %d generated tokens", produced);
                break;
            }
        }

        batch.n_tokens = 1;
        batch.token[0]  = id;
        batch.pos[0]    = next_pos++;
        batch.logits[0] = 1;
    }

    llama_batch_free(batch);
    llama_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_advisor_app_llm_LlamaCppBackend_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* h = reinterpret_cast<AdvisorLlm*>(handle);
    if (h == nullptr) return;
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

} // extern "C"
