// advisor_llm.cpp — the native side of com.advisor.app.llm.LlamaCppBackend.
//
// A thin JNI bridge over llama.cpp that loads a Qwen3-4B GGUF and runs a completion for a
// fully-formatted (ChatML) prompt, entirely on-device. It exposes exactly the three methods the
// Kotlin `external fun`s declare: nativeLoad / nativeGenerate / nativeFree.
//
// API pin: written against **llama.cpp tag b4000** (set as LLAMA_CPP_TAG in CMakeLists.txt). Newer
// tags rename several symbols — e.g. `llama_load_model_from_file` → `llama_model_load_from_file`,
// `llama_new_context_with_model` → `llama_init_from_model`, and tokenize/detokenize move to a
// `llama_vocab` handle. If you bump the tag, update these calls and the pin together.

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>
#include <thread>

#include "llama.h"

#define LOG_TAG "advisor-llm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// One loaded model + its inference context. The opaque `handle` the Kotlin side holds is a pointer
// to this, cast to jlong.
struct AdvisorLlm {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
};

// Decode a single token id back to its text piece.
std::string token_to_piece(const llama_model* model, llama_token id) {
    char buf[256];
    int n = llama_token_to_piece(model, id, buf, sizeof(buf), 0, /*special=*/false);
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
    static bool backend_ready = false;
    if (!backend_ready) {
        llama_backend_init();
        backend_ready = true;
    }

    const char* path = env->GetStringUTFChars(jpath, nullptr);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // pure CPU inference on-device

    llama_model* model = llama_load_model_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (model == nullptr) {
        LOGW("llama_load_model_from_file returned null");
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 4096;
    unsigned hw = std::thread::hardware_concurrency();
    int threads = hw > 1 ? static_cast<int>(hw / 2) : 1; // leave headroom for the UI
    cp.n_threads       = threads;
    cp.n_threads_batch = threads;

    llama_context* ctx = llama_new_context_with_model(model, cp);
    if (ctx == nullptr) {
        LOGW("llama_new_context_with_model returned null");
        llama_free_model(model);
        return 0;
    }

    auto* h = new AdvisorLlm{model, ctx};
    LOGI("Loaded Qwen3-4B GGUF; ctx=%d threads=%d", (int) cp.n_ctx, threads);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jstring JNICALL
Java_com_advisor_app_llm_LlamaCppBackend_nativeGenerate(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jprompt,
        jint maxTokens, jfloat temperature, jfloat topP, jint topK, jobjectArray jstops) {

    auto* h = reinterpret_cast<AdvisorLlm*>(handle);
    if (h == nullptr || h->ctx == nullptr) return env->NewStringUTF("");

    // Each ask re-sends the full assembled prompt, so start from a clean slate.
    llama_kv_cache_clear(h->ctx);

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
            h->model, text.c_str(), (int) text.size(),
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

    std::string out;
    llama_batch batch = llama_batch_get_one(tokens.data(), (int) tokens.size());

    const int budget = maxTokens > 0 ? maxTokens : 512;
    for (int generated = 0; generated < budget; generated++) {
        if (llama_decode(h->ctx, batch) != 0) {
            LOGW("llama_decode failed");
            break;
        }

        llama_token id = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_token_is_eog(h->model, id)) break;

        out += token_to_piece(h->model, id);

        size_t cut = first_stop(out, stops);
        if (cut != std::string::npos) {
            out.resize(cut);
            break;
        }

        batch = llama_batch_get_one(&id, 1);
    }

    llama_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_advisor_app_llm_LlamaCppBackend_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* h = reinterpret_cast<AdvisorLlm*>(handle);
    if (h == nullptr) return;
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_free_model(h->model);
    delete h;
}

// ---------------------------------------------------------------------------
// Embedding backend — the native side of com.advisor.app.llm.LlamaCppEmbedder.
//
// A second, small model loaded in *embedding* mode (mean-pooled) that turns a piece of text into one
// vector, so EmbeddingRetriever can rank the corpus by meaning. It shares this library and the b4000
// API pin with the generation backend above.
//
// NOTE: unlike nativeGenerate, this path has not been compiled/verified on-device in this repo yet
// (it needs an embedding GGUF, which is provisioned separately). It is written against the same b4000
// API and the stock `examples/embedding` pooling pattern; validate it when you first import an
// embedding model. The Kotlin side is fully guarded — any load/encode failure falls back to lexical
// retrieval — so a mismatch degrades gracefully rather than crashing.
// ---------------------------------------------------------------------------

namespace {

struct AdvisorEmbed {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
    int            n_embd = 0;
};

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_advisor_app_llm_LlamaCppEmbedder_nativeLoad(JNIEnv* env, jobject /*thiz*/, jstring jpath) {
    static bool backend_ready = false;
    if (!backend_ready) {
        llama_backend_init();
        backend_ready = true;
    }

    const char* path = env->GetStringUTFChars(jpath, nullptr);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // pure CPU inference on-device

    llama_model* model = llama_load_model_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (model == nullptr) {
        LOGW("embedding: llama_load_model_from_file returned null");
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

    llama_context* ctx = llama_new_context_with_model(model, cp);
    if (ctx == nullptr) {
        LOGW("embedding: llama_new_context_with_model returned null");
        llama_free_model(model);
        return 0;
    }

    auto* h = new AdvisorEmbed{model, ctx, llama_n_embd(model)};
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
            h->model, input.c_str(), (int) input.size(),
            tokens.data(), n_max, /*add_special=*/true, /*parse_special=*/false);
    if (n_tok <= 0) {
        LOGW("embedding: tokenization failed (%d)", n_tok);
        return env->NewFloatArray(0);
    }
    const int n_ctx = (int) llama_n_ctx(h->ctx);
    if (n_tok > n_ctx) n_tok = n_ctx; // truncate over-long inputs rather than fail
    tokens.resize(n_tok);

    // One sequence; all positions marked as outputs so mean-pooling sees every token.
    llama_kv_cache_clear(h->ctx);
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
    if (h->model) llama_free_model(h->model);
    delete h;
}

} // extern "C"
