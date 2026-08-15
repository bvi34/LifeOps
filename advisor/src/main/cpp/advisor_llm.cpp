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
//   * cache reset is llama_kv_self_clear; embedding size is llama_model_n_embd
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

// Route llama.cpp / ggml's own diagnostics into logcat. Without this the library logs to stderr, which
// Android drops — so a load failure ("unknown model architecture", "unsupported quantization", "unable
// to allocate", …) would be invisible and all we'd see is our own "returned null". With it, the real
// reason shows under the `advisor-llm` tag.
void advisor_log_cb(ggml_log_level level, const char* text, void* /*user*/) {
    int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
             : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
             : ANDROID_LOG_INFO;
    __android_log_print(prio, LOG_TAG, "%s", text ? text : "");
}

// Install the log bridge and initialize the backend exactly once, whichever entry point runs first.
void ensure_backend_ready() {
    static bool ready = false;
    if (ready) return;
    llama_log_set(advisor_log_cb, nullptr);
    llama_backend_init();
    ready = true;
}

// One loaded model + its inference context (and the vocab handle the tag exposes for tokenization).
// The opaque `handle` the Kotlin side holds is a pointer to this, cast to jlong.
struct AdvisorLlm {
    llama_model*       model = nullptr;
    llama_context*     ctx   = nullptr;
    const llama_vocab* vocab = nullptr;
};

// Decode a single token id back to its text piece.
std::string token_to_piece(const llama_vocab* vocab, llama_token id) {
    char buf[256];
    int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, /*special=*/false);
    if (n < 0) return {};
    return std::string(buf, n);
}

// True once `text` ends with, or contains, any of the stop strings — returns the cut length.
// Returns std::string::npos when no stop is present.
size_t first_stop(const std::string& text, const std::vector<std::string>& stops) {
    size_t best = std::string::npos;
    for (const auto& s : stops) {
        if (s.empty()) continue;
        size_t at = text.find(s);
        if (at != std::string::npos && at < best) best = at;
    }
    return best;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_advisor_app_llm_LlamaCppBackend_nativeLoad(JNIEnv* env, jobject /*thiz*/, jstring jpath) {
    ensure_backend_ready();

    const char* path = env->GetStringUTFChars(jpath, nullptr);

    llama_model_params mp = llama_model_default_params();
#ifdef ADVISOR_GPU_OFFLOAD
    mp.n_gpu_layers = 99; // offload all transformer layers to the GPU (Vulkan backend compiled in)
#else
    mp.n_gpu_layers = 0;  // pure CPU inference on-device (no GPU backend in this build)
#endif

    llama_model* model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (model == nullptr) {
        LOGW("llama_model_load_from_file returned null");
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 4096;
    // The whole assembled prompt is submitted as one batch, so n_batch must be able to hold a full
    // context — the default (2048) is smaller than n_ctx, and a prompt between the two would trip
    // llama_decode's `n_tokens <= n_batch` assert and abort (SIGABRT). Match it to n_ctx.
    cp.n_batch = 4096;
    unsigned hw = std::thread::hardware_concurrency();
    int threads = hw > 1 ? static_cast<int>(hw / 2) : 1; // leave headroom for the UI
    cp.n_threads       = threads;
    cp.n_threads_batch = threads;

    llama_context* ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        LOGW("llama_init_from_model returned null");
        llama_model_free(model);
        return 0;
    }

    auto* h = new AdvisorLlm{model, ctx, llama_model_get_vocab(model)};
    LOGI("Loaded Qwen3-4B GGUF; ctx=%d threads=%d n_gpu_layers=%d", (int) cp.n_ctx, threads, mp.n_gpu_layers);
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

    // Each ask re-sends the full assembled prompt, so start from a clean slate.
    llama_kv_self_clear(h->ctx);

    // Collect stop strings.
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

    // Tokenize the prompt (add BOS, parse special tokens so ChatML control tokens are honored).
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

    const int n_ctx = (int) llama_n_ctx(h->ctx);
    if (n_prompt >= n_ctx) {
        LOGW("prompt (%d tokens) exceeds context (%d); truncating head", n_prompt, n_ctx);
        tokens.erase(tokens.begin(), tokens.begin() + (n_prompt - (n_ctx - 1)));
    }

    // Sampler chain: top-k → top-p → temperature → sample. Matches GenerationParams defaults.
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // The first decode processes the whole prompt (prefill) — the slow, silent phase on CPU — then each
    // later decode is a single token. Log prefill completion and periodic progress so the minutes-long
    // gap is visible instead of looking hung.
    LOGI("nativeGenerate: prompt=%d tokens; prefill…", (int) tokens.size());
    const auto t_start = std::chrono::steady_clock::now();
    auto elapsed_ms = [&]() {
        return (long long) std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t_start).count();
    };

    std::string out;
    // Use llama_batch_init for the prompt batch (modern API, works with latest llama.cpp).
    // llama_batch_get_one was deprecated after b5600 and removed in newer versions.
    llama_batch batch = llama_batch_init((int) tokens.size(), 0, 1);
    for (int i = 0; i < (int) tokens.size(); i++) {
        batch.token[i]     = tokens[i];
        batch.pos[i]       = i;
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]    = 0; // no logits needed during prefill
    }
    batch.n_tokens = (int) tokens.size();

    const int budget = maxTokens > 0 ? maxTokens : 512;
    int produced = 0;
    for (int generated = 0; generated < budget; generated++) {
        if (llama_decode(h->ctx, batch) != 0) {
            LOGW("llama_decode failed");
            break;
        }
        if (generated == 0) LOGI("nativeGenerate: prefill done in %lld ms; streaming…", elapsed_ms());

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

        // For single-token decode, reuse the batch with one token.
        batch.n_tokens = 1;
        batch.token[0]  = id;
        batch.pos[0]    = batch.pos[0] + 1; // advance position
        batch.logits[0] = 1; // request logits for the last token
    }

    llama_batch_free(batch);
    llama_sampler_free(smpl);
    const long long ms = elapsed_ms();
    const double tps = produced > 0 && ms > 0 ? produced * 1000.0 / ms : 0.0;
    LOGI("nativeGenerate: produced %d tokens (%zu chars) in %lld ms, %.1f tok/s",
         produced, out.size(), ms, tps);
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

} // extern "C" (generation)

// ---------------------------------------------------------------------------
// Embedding backend — the native side of com.advisor.app.llm.LlamaCppEmbedder.
//
// A second, small model loaded in *embedding* mode (mean-pooled) that turns a piece of text into one
// vector, so EmbeddingRetriever can rank the corpus by meaning. It shares this library and the b6490
// API pin with the generation backend above.
//
// NOTE: unlike nativeGenerate, this path has not been compiled/verified on-device in this repo yet
// (it needs an embedding GGUF, which is provisioned separately). It is written against the same b6490
// API and the stock `examples/embedding` pooling pattern; validate it when you first import an
// embedding model. The Kotlin side is fully guarded — any load/encode failure falls back to lexical
// retrieval — so a mismatch degrades gracefully rather than crashing.
// ---------------------------------------------------------------------------

namespace {

struct AdvisorEmbed {
    llama_model*       model  = nullptr;
    llama_context*     ctx    = nullptr;
    const llama_vocab* vocab  = nullptr;
    int                n_embd = 0;
};

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_advisor_app_llm_LlamaCppEmbedder_nativeLoad(JNIEnv* env, jobject /*thiz*/, jstring jpath) {
    ensure_backend_ready();

    const char* path = env->GetStringUTFChars(jpath, nullptr);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // pure CPU inference on-device

    llama_model* model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (model == nullptr) {
        LOGW("embedding: llama_model_load_from_file returned null");
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.embeddings   = true;                       // produce embeddings, not logits
    cp.pooling_type = LLAMA_POOLING_TYPE_MEAN;    // one vector per input, mean over tokens
    cp.n_ctx        = 2048;                        // embedding inputs are short (title + body excerpt)
    cp.n_batch      = 2048;                        // decode the whole sequence in one batch (pooling)
    cp.n_ubatch     = 2048;
    unsigned hw = std::thread::hardware_concurrency();
    int threads = hw > 1 ? static_cast<int>(hw / 2) : 1;
    cp.n_threads       = threads;
    cp.n_threads_batch = threads;

    llama_context* ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        LOGW("embedding: llama_init_from_model returned null");
        llama_model_free(model);
        return 0;
    }

    auto* h = new AdvisorEmbed{model, ctx, llama_model_get_vocab(model), llama_model_n_embd(model)};
    LOGI("Loaded embedding GGUF; n_embd=%d threads=%d", h->n_embd, threads);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jint JNICALL
Java_com_advisor_app_llm_LlamaCppEmbedder_nativeDim(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* h = reinterpret_cast<AdvisorEmbed*>(handle);
    return h == nullptr ? 0 : h->n_embd;
}

JNIEXPORT jfloatArray JNICALL
Java_com_advisor_app_llm_LlamaCppEmbedder_nativeEmbed(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jtext) {

    auto* h = reinterpret_cast<AdvisorEmbed*>(handle);
    if (h == nullptr || h->ctx == nullptr) return env->NewFloatArray(0);

    const char* text = env->GetStringUTFChars(jtext, nullptr);
    std::string input(text ? text : "");
    env->ReleaseStringUTFChars(jtext, text);

    // Tokenize (add BOS/EOS per the model; embedding models don't use ChatML control tokens).
    int n_max = (int) input.size() + 8;
    std::vector<llama_token> tokens(n_max);
    int n_tok = llama_tokenize(
            h->vocab, input.c_str(), (int) input.size(),
            tokens.data(), n_max, /*add_special=*/true, /*parse_special=*/false);
    if (n_tok <= 0) {
        LOGW("embedding: tokenization failed (%d)", n_tok);
        return env->NewFloatArray(0);
    }
    const int n_ctx = (int) llama_n_ctx(h->ctx);
    if (n_tok > n_ctx) n_tok = n_ctx; // truncate over-long inputs rather than fail
    tokens.resize(n_tok);

    // One sequence; all positions marked as outputs so mean-pooling sees every token.
    llama_kv_self_clear(h->ctx);
    llama_batch batch = llama_batch_init(n_tok, 0, 1);
    for (int i = 0; i < n_tok; i++) {
        batch.token[i]     = tokens[i];
        batch.pos[i]       = i;
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]    = 1;
    }
    batch.n_tokens = n_tok;

    jfloatArray result = env->NewFloatArray(0);
    if (llama_decode(h->ctx, batch) == 0) {
        const float* emb = llama_get_embeddings_seq(h->ctx, 0);
        if (emb == nullptr) emb = llama_get_embeddings(h->ctx); // non-pooled fallback
        if (emb != nullptr && h->n_embd > 0) {
            result = env->NewFloatArray(h->n_embd);
            env->SetFloatArrayRegion(result, 0, h->n_embd, emb);
        } else {
            LOGW("embedding: no embeddings returned");
        }
    } else {
        LOGW("embedding: llama_decode failed");
    }

    llama_batch_free(batch);
    return result;
}

JNIEXPORT void JNICALL
Java_com_advisor_app_llm_LlamaCppEmbedder_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* h = reinterpret_cast<AdvisorEmbed*>(handle);
    if (h == nullptr) return;
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

} // extern "C"
