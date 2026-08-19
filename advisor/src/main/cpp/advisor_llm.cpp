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
#include <sched.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
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

// How long a single nativeGenerate call may run before it gives up. Inference on a phone is slow but
// bounded; anything past this is a pathology (a mis-built ggml, a device thrashing its page cache),
// and without a ceiling it presents as the app hanging forever with nothing in logcat. ggml polls the
// abort callback between graph nodes, so we surrender inside llama_decode instead of blocking the
// caller indefinitely, and return whatever text was produced so Kotlin can fall back and log.
constexpr long long GENERATE_DEADLINE_MS = 180000;

struct AdvisorLlm {
    llama_model*       model = nullptr;
    llama_context*     ctx   = nullptr;
    const llama_vocab* vocab = nullptr;
    // Set for the duration of a generate call; zero means "no deadline in force".
    std::atomic<long long> deadline_at_ms{0};
    std::atomic<bool>      aborted{false};
};

long long steady_now_ms() {
    return (long long) std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

// Called by ggml from the compute threads between graph nodes; returning true aborts the graph and
// makes llama_decode fail rather than run on forever.
bool advisor_abort_cb(void* data) {
    auto* h = static_cast<AdvisorLlm*>(data);
    if (h == nullptr) return false;
    const long long at = h->deadline_at_ms.load(std::memory_order_relaxed);
    if (at == 0 || steady_now_ms() < at) return false;
    h->aborted.store(true, std::memory_order_relaxed);
    return true;
}

// Whether a slow decode is starved of CPU or starved of memory is not something you can read off a
// wall clock, and guessing wrong costs a full rebuild-and-run cycle. These sample the kernel's own
// counters so one run answers it:
//   * cpu_ms / wall_ms — with n_threads workers pegged this approaches n_threads. Near 1x or below
//     means the threads are blocked, not computing.
//   * major faults — each one is a 4 KiB page fetched from flash. The weights are ~2.3 GiB = ~580k
//     pages; a delta near that per forward pass means the mmap'd weights are not staying resident and
//     every pass re-reads the model from storage.
struct ResSnapshot {
    long      majflt   = 0;
    long      minflt   = 0;
    long long cpu_ms   = 0;
    long long wall_ms  = 0;
};

long long timeval_ms(const struct timeval& tv) {
    return (long long) tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

// Reads a "<key>: <value> kB" line out of a /proc meminfo-style file. Returns -1 if absent.
long long proc_kb(const char* path, const char* key) {
    FILE* f = fopen(path, "re");
    if (f == nullptr) return -1;
    char line[256];
    const size_t key_len = strlen(key);
    long long value = -1;
    while (fgets(line, sizeof(line), f) != nullptr) {
        if (strncmp(line, key, key_len) == 0 && line[key_len] == ':') {
            value = strtoll(line + key_len + 1, nullptr, 10);
            break;
        }
    }
    fclose(f);
    return value;
}

// Resident set size of this process, in MiB (-1 if unavailable). /proc/self/statm field 2 is RSS in
// pages — it counts the mmap'd model pages that are *currently* resident, which is exactly what the
// page-cache question turns on.
long long rss_mib() {
    FILE* f = fopen("/proc/self/statm", "re");
    if (f == nullptr) return -1;
    long long total_pages = 0, rss_pages = 0;
    const int read = fscanf(f, "%lld %lld", &total_pages, &rss_pages);
    fclose(f);
    if (read != 2) return -1;
    return rss_pages * (long long) sysconf(_SC_PAGESIZE) / (1024 * 1024);
}

// Where this thread is actually allowed to run. On Android a thread's cgroup/cpuset is set by its
// scheduling priority, and a background-classified thread can be confined to the little cores (and
// given a small share of them) — which throttles inference by a large factor while leaving both the
// compiled kernels and the memory system entirely innocent. Like the fault counters, this is the kind
// of thing that is invisible in a wall-clock number and obvious the moment it is printed.
void log_thread_scheduling(const char* label) {
    const pid_t tid = (pid_t) syscall(SYS_gettid);

    std::string cpus = "?";
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(tid, sizeof(set), &set) == 0) {
        cpus.clear();
        for (int cpu = 0; cpu < CPU_SETSIZE; cpu++) {
            if (!CPU_ISSET(cpu, &set)) continue;
            if (!cpus.empty()) cpus += ",";
            cpus += std::to_string(cpu);
        }
        if (cpus.empty()) cpus = "none";
    }

    // e.g. "4:cpuset:/background" — the line that says the thread was demoted to the little cores.
    std::string cpuset = "?";
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/task/%d/cgroup", (int) tid);
    FILE* f = fopen(path, "re");
    if (f != nullptr) {
        char line[256];
        while (fgets(line, sizeof(line), f) != nullptr) {
            if (strstr(line, ":cpuset:") == nullptr) continue;
            std::string found(line);
            while (!found.empty() && (found.back() == '\n' || found.back() == '\r')) found.pop_back();
            cpuset = found;
            break;
        }
        fclose(f);
    }

    // getpriority legitimately returns -1 for a nice of -1, so errno is the only way to tell it apart
    // from a failure.
    errno = 0;
    const int nice_value = getpriority(PRIO_PROCESS, tid);
    const std::string nice_text = errno == 0 ? std::to_string(nice_value) : std::string("?");
    LOGI("%s: tid=%d nice=%s policy=%d affinity=[%s] online_cpus=%ld cpuset=%s",
         label, (int) tid, nice_text.c_str(), sched_getscheduler(tid), cpus.c_str(),
         sysconf(_SC_NPROCESSORS_ONLN), cpuset.c_str());
}

ResSnapshot sample_resources() {
    ResSnapshot snap;
    struct rusage ru {};
    if (getrusage(RUSAGE_SELF, &ru) == 0) {
        snap.majflt = ru.ru_majflt;
        snap.minflt = ru.ru_minflt;
        snap.cpu_ms = timeval_ms(ru.ru_utime) + timeval_ms(ru.ru_stime);
    }
    snap.wall_ms = steady_now_ms();
    return snap;
}

// One line that says whether `label` was CPU-bound or storage-bound.
void log_resource_delta(const char* label, const ResSnapshot& before, const ResSnapshot& after) {
    const long long wall = after.wall_ms - before.wall_ms;
    const long long cpu  = after.cpu_ms  - before.cpu_ms;
    const double    busy = wall > 0 ? (double) cpu / (double) wall : 0.0;
    LOGI("%s: %lld ms wall, %lld ms cpu (%.1fx busy), majflt +%ld, minflt +%ld, rss %lld MiB, "
         "MemAvailable %lld MiB",
         label, wall, cpu, busy, after.majflt - before.majflt, after.minflt - before.minflt,
         rss_mib(), proc_kb("/proc/meminfo", "MemAvailable") / 1024);
}

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
    // mmap off by default on Android. mmap'd weights are *file-backed*, so under memory pressure the
    // kernel simply drops them and the next forward pass re-reads 2.3 GiB from flash — repeatedly, for
    // every pass, at a cost that no amount of kernel optimisation touches. Reading the file into
    // anonymous memory instead puts the weights where Android's zram can compress them rather than
    // evict them. The trade is a slower first load and a hard commit: if the device genuinely cannot
    // spare the RAM, the app gets LMK-killed, which is at least an honest failure rather than a
    // generation that never returns. Flip back with -Padvisor.mmap=true to compare.
#ifdef ADVISOR_USE_MMAP
    mp.use_mmap = true;
#else
    mp.use_mmap = false;
#endif

    llama_model* model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (model == nullptr) {
        LOGW("llama_model_load_from_file returned null");
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    // 2048, not 4096: the KV cache is anonymous (non-evictable) memory — 576 MiB at 4096 vs 288 MiB
    // here — and it competes with the 2.3 GiB of mmap'd weights for the page cache. When the weights
    // can't stay resident, every decode re-faults them from flash and inference collapses. Advisor's
    // prompts run ~500 tokens, so 2048 leaves ample room for prompt + reply.
    cp.n_ctx = 2048;
    // n_batch must cover a full-context prompt in one llama_decode (see the SIGABRT fixed earlier);
    // n_ubatch stays at the default 512 so a typical prompt is a *single* pass over the weights.
    cp.n_batch = cp.n_ctx;
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
    llama_set_abort_callback(ctx, advisor_abort_cb, h);
    LOGI("Loaded Qwen3-4B GGUF; ctx=%d n_batch=%d n_ubatch=%d threads=%d n_gpu_layers=%d flash_attn=disabled",
         (int) cp.n_ctx, (int) cp.n_batch, (int) cp.n_ubatch, threads, mp.n_gpu_layers);
    // The single most useful line in this log: it names the ISA extensions ggml was actually compiled
    // with. `dotprod = 1` / `matmul_int8 = 1` mean the fast Q4_K and GEMM kernels are in; zeros mean the
    // build fell back to baseline armv8-a and prefill will be several times slower than it should be
    // (see GGML_CPU_ARM_ARCH in CMakeLists.txt).
    LOGI("ggml build: %s", llama_print_system_info());
    // The weights are mmap'd, so `load` finishing quickly means nothing was read yet — it says nothing
    // about whether the device can hold them. MemAvailable does: if it is well under the model size,
    // every forward pass will re-fault the weights from flash no matter how fast the kernels are.
    LOGI("device memory at load: rss %lld MiB, MemAvailable %lld MiB, MemTotal %lld MiB",
         rss_mib(), proc_kb("/proc/meminfo", "MemAvailable") / 1024,
         proc_kb("/proc/meminfo", "MemTotal") / 1024);
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
    log_thread_scheduling("nativeGenerate thread");

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

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // Prefill the whole prompt in ONE llama_decode. An earlier revision split it into 32-token
    // chunks as a diagnostic and the chunking was never taken back out — but every chunk is a full
    // sweep over all 2.3 GiB of weights, so a 490-token prompt paid for ~16 sweeps instead of 1. That
    // turns a compute-bound prefill into a memory-bound one and is what made generation look hung.
    // llama_decode splits the batch into n_ubatch (512) micro-batches internally, so a normal prompt
    // is still a single pass; there is nothing to hand-roll here.
    const int n_prefill = (int) tokens.size();
    LOGI("nativeGenerate: prompt=%d tokens; prefill in one batch (n_ubatch=%d)…",
         n_prefill, (int) llama_n_ubatch(h->ctx));
    const auto t_start = std::chrono::steady_clock::now();
    auto elapsed_ms = [&]() {
        return (long long) std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t_start).count();
    };

    // Arm the watchdog for the whole call so a pathologically slow decode fails loudly instead of
    // pinning a coroutine forever. Cleared on every exit path below.
    h->aborted.store(false, std::memory_order_relaxed);
    h->deadline_at_ms.store(steady_now_ms() + GENERATE_DEADLINE_MS, std::memory_order_relaxed);

    const ResSnapshot res_before_prefill = sample_resources();

    std::string out;
    llama_batch batch = llama_batch_init(std::max(1, n_prefill), 0, 1);
    batch.n_tokens = n_prefill;
    for (int i = 0; i < n_prefill; i++) {
        batch.token[i]     = tokens[i];
        batch.pos[i]       = i;
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = 0;
        // Only the last prompt token needs logits — that's what the first sample reads.
        batch.logits[i]    = (i == n_prefill - 1) ? 1 : 0;
    }

    bool prefill_ok = llama_decode(h->ctx, batch) == 0;
    const ResSnapshot res_after_prefill = sample_resources();
    log_resource_delta("prefill", res_before_prefill, res_after_prefill);
    if (!prefill_ok) {
        LOGW("nativeGenerate: prefill llama_decode failed after %lld ms (%d tokens)%s",
             elapsed_ms(), n_prefill,
             h->aborted.load(std::memory_order_relaxed) ? " — hit the generate deadline" : "");
    }

    if (prefill_ok) {
        LOGI("nativeGenerate: prefill done in %lld ms; streaming…", elapsed_ms());
        const int budget = maxTokens > 0 ? maxTokens : 512;
        int produced = 0;
        int next_pos = (int) tokens.size();

        // Sample the first token from the logits requested on the final prompt token, then decode
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
                LOGW("nativeGenerate: decode failed after %d generated tokens%s", produced,
                     h->aborted.load(std::memory_order_relaxed) ? " — hit the generate deadline" : "");
                break;
            }
        }

        const long long ms = elapsed_ms();
        const double tps = produced > 0 && ms > 0 ? produced * 1000.0 / ms : 0.0;
        LOGI("nativeGenerate: produced %d tokens (%zu chars) in %lld ms, %.1f tok/s",
             produced, out.size(), ms, tps);
        log_resource_delta("generation", res_after_prefill, sample_resources());
    }

    // llama.cpp's own accounting: separate prompt-eval (prefill) and eval (decode) ms/token. If the
    // model ever feels slow again, this line says which half is slow before anyone changes code.
    llama_perf_context_print(h->ctx);
    llama_perf_context_reset(h->ctx);

    h->deadline_at_ms.store(0, std::memory_order_relaxed);
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

} // extern "C" (generation)

// ---------------------------------------------------------------------------
// Embedding backend — the native side of com.advisor.app.llm.LlamaCppEmbedder.
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
    mp.n_gpu_layers = 0;

    llama_model* model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (model == nullptr) {
        // Almost always an architecture llama.cpp doesn't know at this pin rather than a bad file —
        // the ggml log line just above names it ("unknown model architecture: '<arch>'"). Jina v5 /
        // EuroBERT GGUFs hit this at b6490; use a bge / gte / e5 / MiniLM / nomic-embed GGUF instead.
        LOGW("embedding: llama_model_load_from_file returned null — see the llama_model_load error "
             "above; an unsupported architecture at this llama.cpp pin is the usual cause. "
             "Retrieval stays lexical.");
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.embeddings   = true;
    cp.pooling_type = LLAMA_POOLING_TYPE_MEAN;
    cp.n_ctx        = 2048;
    cp.n_batch      = 2048;
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
    if (n_tok > n_ctx) n_tok = n_ctx;
    tokens.resize(n_tok);

    llama_memory_clear(llama_get_memory(h->ctx), /*data=*/true);

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
        if (emb == nullptr) emb = llama_get_embeddings(h->ctx);
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