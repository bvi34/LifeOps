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

// Shortest shared prefix worth keeping across calls. Small overlaps are common between unrelated
// prompts (the ChatML preamble alone is a handful of tokens) and save nothing worth the bookkeeping.
constexpr int MIN_PREFIX_REUSE = 32;

// The set of CPUs worth running inference on. A phone's cores are not interchangeable: on this class
// of SoC (a Snapdragon 8+ Gen 1, say) four Cortex-A510s sit alongside three A710s and an X2 running
// at nearly twice the clock with several times the vector throughput. ggml synchronizes its worker
// threads at a barrier after every graph node, so the *slowest* worker sets the pace of every matmul
// — one thread scheduled onto a little core throttles the entire forward pass, and nothing in a
// wall-clock number says that is what happened.
//
// Nothing arranges this on its own: asking for n_threads says how many workers to run, never where,
// so left alone the kernel is free to spread them across both clusters.
struct BigCores {
    cpu_set_t mask{};
    int       count = 0;
};

// A human-readable "0,1,2,3" for a CPU mask.
std::string cpu_list(const cpu_set_t& mask) {
    std::string out;
    for (int cpu = 0; cpu < CPU_SETSIZE; cpu++) {
        if (!CPU_ISSET(cpu, &mask)) continue;
        if (!out.empty()) out += ",";
        out += std::to_string(cpu);
    }
    return out.empty() ? "none" : out;
}

// The policy half of pick_big_cores: given each core's clock ceiling in kHz (0 where it could not be
// read), which cores belong to the fastest cluster(s) — anything within 80% of the top clock. That
// captures the usual big+mid+little split (an X2 at 3.2 GHz and A710s at 2.75 GHz are both "big";
// A510s at 1.8 GHz are not) without hard-coding a CPU numbering that differs per SoC.
//
// Returns an empty set — meaning "no opinion, leave affinity alone" — in the two cases where pinning
// would be wrong rather than merely unhelpful: nothing readable to go on, and a homogeneous CPU, where
// there is no little cluster to stay off and pinning only takes away the scheduler's freedom to move
// work off a hot core. Kept pure and separate so it can be exercised against real topologies on the
// host (see hosttest/cpu_topology_test.cpp) rather than only on a phone.
BigCores select_big_cores(const std::vector<long long>& khz) {
    BigCores out;
    CPU_ZERO(&out.mask);

    const size_t n_cpu = khz.size();
    if (n_cpu <= 1 || n_cpu > (size_t) CPU_SETSIZE) return out;

    long long top = 0;
    for (long long value : khz) top = std::max(top, value);
    if (top <= 0) return out;

    const long long cutoff = top / 10 * 8;
    for (size_t cpu = 0; cpu < n_cpu; cpu++) {
        if (khz[cpu] < cutoff) continue;
        CPU_SET((int) cpu, &out.mask);
        out.count++;
    }
    if (out.count >= (int) n_cpu) {
        CPU_ZERO(&out.mask);
        out.count = 0;
    }
    return out;
}

// Read every core's clock ceiling out of cpufreq and hand it to the policy above. A core whose
// cpufreq node is missing reads 0; if *none* of them are readable (some devices restrict the node)
// the policy sees nothing to go on and declines to pin, which is the intended fallback.
BigCores pick_big_cores() {
    const long n_cpu = sysconf(_SC_NPROCESSORS_CONF);
    if (n_cpu <= 1 || n_cpu > CPU_SETSIZE) return BigCores{};

    std::vector<long long> khz((size_t) n_cpu, 0);
    for (long cpu = 0; cpu < n_cpu; cpu++) {
        char path[128];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%ld/cpufreq/cpuinfo_max_freq", cpu);
        FILE* f = fopen(path, "re");
        if (f == nullptr) continue;
        long long value = 0;
        if (fscanf(f, "%lld", &value) == 1 && value > 0) khz[(size_t) cpu] = value;
        fclose(f);
    }
    return select_big_cores(khz);
}

// Pins the calling thread to [cores] for a scope, restoring the mask it had on the way out.
// Both entry points run on a *shared* thread — a Kotlin coroutine dispatcher's — which must not keep
// the pinning after the call returns. The ggml workers created inside the scope inherit the mask and
// keep it, which is the entire point: that is how the compute threads end up on the big cores.
class BigCoreScope {
public:
    explicit BigCoreScope(const BigCores& cores) {
        if (cores.count <= 0) return;
        if (sched_getaffinity(0, sizeof(cpu_set_t), &previous_) != 0) return;
        if (sched_setaffinity(0, sizeof(cpu_set_t), &cores.mask) != 0) {
            LOGW("could not pin the inference thread to CPUs [%s] (errno %d); leaving affinity alone",
                 cpu_list(cores.mask).c_str(), errno);
            return;
        }
        restore_ = true;
    }

    ~BigCoreScope() {
        if (restore_) sched_setaffinity(0, sizeof(cpu_set_t), &previous_);
    }

    BigCoreScope(const BigCoreScope&) = delete;
    BigCoreScope& operator=(const BigCoreScope&) = delete;

private:
    cpu_set_t previous_{};
    bool      restore_ = false;
};

struct AdvisorLlm {
    llama_model*       model = nullptr;
    llama_context*     ctx   = nullptr;
    const llama_vocab* vocab = nullptr;
    // Set for the duration of a generate call; zero means "no deadline in force".
    std::atomic<long long> deadline_at_ms{0};
    std::atomic<bool>      aborted{false};
    // The prompt tokens whose KV entries are currently in the cache, so the next call can keep the
    // part it shares and prefill only what actually changed. Prompt tokens only — the generated reply
    // also sits in the cache but is never part of the next prompt, so it always falls after the shared
    // prefix and is dropped with everything else beyond it. Empty means "cache holds nothing usable".
    std::vector<llama_token> cached_prompt;
    // The CPUs the compute threads belong on, decided once at load (see pick_big_cores).
    BigCores big_cores;
};

// How many leading tokens two prompts share.
size_t common_prefix(const std::vector<llama_token>& a, const std::vector<llama_token>& b) {
    const size_t limit = std::min(a.size(), b.size());
    size_t n = 0;
    while (n < limit && a[n] == b[n]) n++;
    return n;
}

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
    if (sched_getaffinity(tid, sizeof(set), &set) == 0) cpus = cpu_list(set);

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

// How much of [s] ends on a complete UTF-8 sequence. A token's bytes are not characters: a single
// emoji or CJK glyph is routinely split across two tokens, so streaming each piece the moment it is
// produced would hand a truncated sequence to the JVM. Everything up to this point is safe to emit;
// the remainder waits for the bytes that finish it.
size_t complete_utf8_prefix(const std::string& s) {
    const size_t n = s.size();
    // A sequence is at most 4 bytes, so only the last few can be incomplete.
    for (size_t back = 1; back <= 4 && back <= n; back++) {
        const unsigned char c = (unsigned char) s[n - back];
        if ((c & 0xC0) == 0x80) continue;  // continuation byte; keep walking back to the lead
        size_t need;
        if      ((c & 0x80) == 0x00) need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        else return n;                     // not a lead byte at all; hold nothing back
        return back >= need ? n : n - back;
    }
    return n;
}

// The Kotlin object that receives each piece of the answer as it is produced, or null when the caller
// did not ask to stream. Bytes rather than a jstring on purpose: NewStringUTF wants *modified* UTF-8
// and rejects (or mangles) the 4-byte sequences that emoji are made of, so the conversion belongs on
// the Kotlin side where the standard decoder can do it.
struct TokenSink {
    jobject   obj    = nullptr;
    jmethodID method = nullptr;

    bool valid() const { return obj != nullptr && method != nullptr; }
};

TokenSink resolve_sink(JNIEnv* env, jobject listener) {
    TokenSink sink;
    if (listener == nullptr) return sink;
    jclass cls = env->GetObjectClass(listener);
    if (cls == nullptr) return sink;
    sink.method = env->GetMethodID(cls, "onToken", "([B)V");
    env->DeleteLocalRef(cls);
    if (sink.method == nullptr) {
        env->ExceptionClear();
        LOGW("token listener has no onToken([B)V; streaming disabled for this call");
        return sink;
    }
    sink.obj = listener;
    return sink;
}

// Hand [text] to the listener. Returns false if the callback threw, in which case the caller stops
// streaming but lets generation finish — a broken listener must not lose the answer.
bool emit_to_sink(JNIEnv* env, const TokenSink& sink, const std::string& text) {
    if (!sink.valid() || text.empty()) return true;
    jbyteArray bytes = env->NewByteArray((jsize) text.size());
    if (bytes == nullptr) {
        env->ExceptionClear();
        return false;
    }
    env->SetByteArrayRegion(bytes, 0, (jsize) text.size(),
                            reinterpret_cast<const jbyte*>(text.data()));
    env->CallVoidMethod(sink.obj, sink.method, bytes);
    env->DeleteLocalRef(bytes);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGW("token listener threw; continuing without streaming");
        return false;
    }
    return true;
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
    // The KV cache is anonymous, non-evictable memory competing with 2.3 GiB of weights for RAM, and
    // when the weights can't stay resident every decode re-faults them from flash and inference
    // collapses — so context length is a memory decision here, not a capacity one.
    //
    // Quantizing the cache to Q8_0 (below) roughly halves its cost per token, which buys length: 3072
    // tokens at Q8_0 is about 235 MiB, still *less* than the 288 MiB that 2048 tokens cost at f16
    // before. That extra room is not for longer answers — it is what lets more of the conversation
    // stay in the prompt, and the whole of it is prefix that the next turn reuses instead of
    // prefilling. If the quantized cache turns out to be unavailable, the fallback below returns to
    // 2048 rather than paying 432 MiB for the same window.
    cp.n_ctx = 3072;
    // n_batch must cover a full-context prompt in one llama_decode (see the SIGABRT fixed earlier);
    // n_ubatch stays at the default 512 so a typical prompt is a *single* pass over the weights.
    cp.n_batch = cp.n_ctx;

    // Run on the big cluster, and size the thread count from it. `hardware_concurrency() / 2` was a
    // stand-in for "the fast half of the cores" that happens to be right on an 8-core 4+4 phone and
    // wrong everywhere else; ask the device instead. When the topology says nothing useful
    // (pick_big_cores returns empty) fall back to exactly the old number, so no device gets worse.
    const BigCores big = pick_big_cores();
    const unsigned hw = std::thread::hardware_concurrency();
    const int threads = big.count > 0 ? big.count : (hw > 1 ? static_cast<int>(hw / 2) : 1);
    cp.n_threads       = threads;
    cp.n_threads_batch = threads;

    // Prefill is compute-bound and decode is memory-bandwidth-bound, but both are barrier-synchronized
    // across the same worker set, so they want the same answer here: every thread on a fast core, none
    // on a slow one. Pin for the whole of context creation — whichever thread first runs a graph is
    // the one whose affinity ggml's workers inherit.
    BigCoreScope pinned(big);

    // Flash attention + a Q8_0 KV cache. FA was switched off while chasing a prefill stall (the cause
    // turned out to be unoptimized ggml and chunked prefill, both since fixed) and never switched back
    // on; it is also the precondition for quantizing the V cache. At n_ctx=2048 Qwen3-4B's cache is
    // 2 * 36 layers * 8 KV heads * 128 dims * 2 bytes = 144 KiB/token = 288 MiB at f16, and roughly
    // half that at Q8_0. That memory is anonymous and non-evictable, so halving it hands ~140 MiB back
    // to the thing that actually decides inference speed on a phone: whether the 2.3 GiB of weights
    // stay resident. Q8_0 is near-lossless for a KV cache — far less lossy than the Q4_K_M weights it
    // sits beside.
    //
    // llama.cpp refuses a quantized V cache without FA, and FA itself can be unavailable on a given
    // backend build (the Vulkan path especially), in which case llama_init_from_model returns null. So
    // ask for the fast pair, and on failure fall back to exactly the previous configuration rather
    // than leaving the model unloadable.
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    cp.type_k = GGML_TYPE_Q8_0;
    cp.type_v = GGML_TYPE_Q8_0;

    llama_context* ctx = llama_init_from_model(model, cp);
    bool quantized_kv = ctx != nullptr;
    if (ctx == nullptr) {
        LOGW("flash attention + Q8_0 KV cache unavailable on this build; retrying with f16 KV at 2048");
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        cp.type_k = GGML_TYPE_F16;
        cp.type_v = GGML_TYPE_F16;
        // An f16 cache costs twice as much per token, so give back the length rather than the RAM.
        cp.n_ctx   = 2048;
        cp.n_batch = cp.n_ctx;
        ctx = llama_init_from_model(model, cp);
    }
    if (ctx == nullptr) {
        LOGW("llama_init_from_model returned null");
        llama_model_free(model);
        return 0;
    }

    auto* h = new AdvisorLlm();
    h->model = model;
    h->ctx   = ctx;
    h->vocab = llama_model_get_vocab(model);
    h->big_cores = big;
    llama_set_abort_callback(ctx, advisor_abort_cb, h);
    LOGI("Loaded Qwen3-4B GGUF; ctx=%d n_batch=%d n_ubatch=%d threads=%d n_gpu_layers=%d flash_attn=%s kv=%s",
         (int) cp.n_ctx, (int) cp.n_batch, (int) cp.n_ubatch, threads, mp.n_gpu_layers,
         llama_flash_attn_type_name(cp.flash_attn_type), quantized_kv ? "q8_0" : "f16");
    // Which cores the workers were put on, and why that number of threads. An `affinity` that spans
    // the little cores here means pick_big_cores found nothing to go on and the scheduler is free to
    // place a worker on a core that will hold every other worker at the barrier.
    LOGI("inference cpus: %s (%d of %ld online) — %s",
         big.count > 0 ? cpu_list(big.mask).c_str() : "unpinned",
         threads, sysconf(_SC_NPROCESSORS_ONLN),
         big.count > 0 ? "pinned to the fastest cluster" : "topology unreadable or homogeneous");
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
        jint maxTokens, jfloat temperature, jfloat topP, jint topK, jobjectArray jstops,
        jobject jlistener) {

    auto* h = reinterpret_cast<AdvisorLlm*>(handle);
    if (h == nullptr || h->ctx == nullptr) {
        LOGW("nativeGenerate called with no loaded model (handle/ctx null)");
        return env->NewStringUTF("");
    }
    // Pinned for the duration of the call: ggml may create its workers on the first decode rather
    // than at context creation, and they inherit the mask of whoever gets there first.
    BigCoreScope pinned(h->big_cores);

    LOGI("nativeGenerate: start (budget=%d tokens)", maxTokens);
    log_thread_scheduling("nativeGenerate thread");

    std::vector<std::string> stops;
    jsize nstops = jstops ? env->GetArrayLength(jstops) : 0;
    for (jsize i = 0; i < nstops; i++) {
        auto js = (jstring) env->GetObjectArrayElement(jstops, i);
        const char* s = env->GetStringUTFChars(js, nullptr);
        stops.emplace_back(s);
        env->ReleaseStringUTFChars(js, s);
        env->DeleteLocalRef(js);
    }

    // Text is streamed to the caller as it is produced, but never further than it is *settled*: a
    // stop sequence arrives one token at a time, so the tail that could still turn out to be the
    // start of one is held back rather than shown and retracted.
    TokenSink sink = resolve_sink(env, jlistener);
    size_t longest_stop = 0;
    for (const auto& stop : stops) longest_stop = std::max(longest_stop, stop.size());
    size_t emitted = 0;

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
    // Keep the KV entries this prompt shares with the last one. Advisor's prompts are a stable
    // ~320-token system preamble followed by per-turn context, so a conversation re-prefilled roughly
    // two thirds of every prompt from scratch when this call unconditionally cleared the cache. Match
    // on tokens rather than assuming where the stable part ends, so the saving tracks whatever the
    // prompt assembler actually keeps constant.
    //
    // Correctness rests on one rule: everything from the first differing token onward must go. That
    // covers the previous reply, which always falls beyond the shared prefix. Keep at most
    // n_prompt - 1 so there is always a token left to decode — the sampler reads its logits.
    llama_memory_t mem = llama_get_memory(h->ctx);
    int n_reused = 0;
    if (!h->cached_prompt.empty()) {
        const int shared = (int) common_prefix(h->cached_prompt, tokens);
        const int keep   = std::min(shared, (int) tokens.size() - 1);
        // Below a threshold the bookkeeping outweighs the saving; just start clean.
        if (keep >= MIN_PREFIX_REUSE && llama_memory_seq_rm(mem, 0, keep, -1)) {
            n_reused = keep;
        }
    }
    if (n_reused == 0) {
        llama_memory_clear(mem, /*data=*/true);
    }
    // Assume failure: only a completed prefill leaves the cache describing this prompt.
    h->cached_prompt.clear();

    const int n_prefill = (int) tokens.size() - n_reused;
    LOGI("nativeGenerate: prompt=%d tokens (%d reused from cache, %d to prefill); one batch (n_ubatch=%d)…",
         (int) tokens.size(), n_reused, n_prefill, (int) llama_n_ubatch(h->ctx));
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
        // Positions continue from the reused prefix — they index the whole prompt, not this batch.
        const int pos = n_reused + i;
        batch.token[i]     = tokens[pos];
        batch.pos[i]       = pos;
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = 0;
        // Only the last prompt token needs logits — that's what the first sample reads.
        batch.logits[i]    = (i == n_prefill - 1) ? 1 : 0;
    }

    bool prefill_ok = llama_decode(h->ctx, batch) == 0;
    if (prefill_ok) h->cached_prompt = tokens;
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
        int next_pos = (int) tokens.size();  // whole prompt, reused prefix included

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

            // Everything except the last `longest_stop` bytes can no longer become a stop sequence,
            // and of that, everything up to the last complete UTF-8 sequence is safe to hand over.
            if (sink.valid() && out.size() > emitted + longest_stop) {
                const size_t window = out.size() - longest_stop - emitted;
                const size_t safe = emitted + complete_utf8_prefix(out.substr(emitted, window));
                if (safe > emitted) {
                    if (emit_to_sink(env, sink, out.substr(emitted, safe - emitted))) emitted = safe;
                    else sink = TokenSink{};
                }
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

        // Whatever the loop ended on — a stop sequence, end-of-generation, the token budget or a
        // failed decode — the held-back tail is settled now.
        if (sink.valid() && emitted < out.size()) {
            emit_to_sink(env, sink, out.substr(emitted));
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

// The context length this model actually got, so the Kotlin side can size a prompt to it instead of
// assuming. It is not a constant: it depends on whether the quantized KV cache was available.
JNIEXPORT jint JNICALL
Java_com_advisor_app_llm_LlamaCppBackend_nativeContextTokens(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* h = reinterpret_cast<AdvisorLlm*>(handle);
    if (h == nullptr || h->ctx == nullptr) return 0;
    return (jint) llama_n_ctx(h->ctx);
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
    BigCores           big_cores;
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
    // Same reasoning as the generation context: the big cluster, sized from the device rather than
    // from a guess at what half the cores means. Embedding a corpus is a long barrier-synchronized
    // batch job, so a worker on a little core costs here exactly as it does in prefill.
    const BigCores big = pick_big_cores();
    const unsigned hw = std::thread::hardware_concurrency();
    const int threads = big.count > 0 ? big.count : (hw > 1 ? static_cast<int>(hw / 2) : 1);
    cp.n_threads       = threads;
    cp.n_threads_batch = threads;

    BigCoreScope pinned(big);
    llama_context* ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        LOGW("embedding: llama_init_from_model returned null");
        llama_model_free(model);
        return 0;
    }

    auto* h = new AdvisorEmbed{model, ctx, llama_model_get_vocab(model), llama_model_n_embd(model), big};
    LOGI("Loaded embedding GGUF; n_embd=%d threads=%d cpus=%s", h->n_embd, threads,
         big.count > 0 ? cpu_list(big.mask).c_str() : "unpinned");
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

    BigCoreScope pinned(h->big_cores);

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