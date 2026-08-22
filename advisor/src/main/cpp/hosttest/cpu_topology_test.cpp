// Host test for the big-core selection policy in advisor_llm.cpp.
//
// The policy decides which CPUs ggml's workers are pinned to, and getting it wrong is expensive in
// both directions: too greedy and a Cortex-A510 holds every other worker at the barrier, too shy and
// inference runs on one core. It is also invisible on-device — you find out by reading a wall clock.
// So `select_big_cores` is pure, and this exercises it against real phone topologies on the host.
//
// It textually lifts the function out of advisor_llm.cpp (see extract() in run.sh) rather than
// duplicating it, so the thing under test is always the shipping code.
#include <cstdio>
#include <cstdlib>
#include <sched.h>
#include <string>
#include <vector>
#include <algorithm>

#define LOGW(...) ((void) 0)

struct BigCores {
    cpu_set_t mask{};
    int       count = 0;
};

#include "select_big_cores.inc"

namespace {

int failures = 0;

void expect(const char* name, const std::vector<long long>& khz, int want_count,
            const std::vector<int>& want_cpus) {
    const BigCores got = select_big_cores(khz);
    std::vector<int> cpus;
    for (int cpu = 0; cpu < CPU_SETSIZE; cpu++) {
        if (CPU_ISSET(cpu, &got.mask)) cpus.push_back(cpu);
    }
    const bool ok = got.count == want_count && cpus == want_cpus;
    if (!ok) {
        failures++;
        std::string got_list, want_list;
        for (int c : cpus) got_list += std::to_string(c) + " ";
        for (int c : want_cpus) want_list += std::to_string(c) + " ";
        printf("FAIL %-38s count=%d [%s] want count=%d [%s]\n",
               name, got.count, got_list.c_str(), want_count, want_list.c_str());
    } else {
        printf("ok   %-38s count=%d\n", name, got.count);
    }
}

} // namespace

int main() {
    // Snapdragon 8+ Gen 1 (Nothing Phone 2): 4x A510 @1.8, 3x A710 @2.75, 1x X2 @3.19.
    // The three A710s and the X2 are the cluster worth using; the A510s must be excluded.
    expect("snapdragon 8+ gen 1",
           {1804800, 1804800, 1804800, 1804800, 2745600, 2745600, 2745600, 3187200},
           4, {4, 5, 6, 7});

    // Snapdragon 888: 4x A55 @1.8, 3x A78 @2.42, 1x X1 @2.84. Same shape, tighter big/mid gap.
    expect("snapdragon 888",
           {1804800, 1804800, 1804800, 1804800, 2419200, 2419200, 2419200, 2841600},
           4, {4, 5, 6, 7});

    // A 2+6 mid-ranger: only the two fast cores qualify.
    expect("2 big + 6 little",
           {1900000, 1900000, 1900000, 1900000, 1900000, 1900000, 2400000, 2400000},
           2, {6, 7});

    // Homogeneous: no little cluster to avoid, so decline to pin and let the scheduler work.
    expect("homogeneous octa-core", {2000000, 2000000, 2000000, 2000000,
                                     2000000, 2000000, 2000000, 2000000}, 0, {});

    // cpufreq unreadable on every core: nothing to go on, so no opinion.
    expect("cpufreq unreadable", {0, 0, 0, 0, 0, 0, 0, 0}, 0, {});

    // Partially readable: the cores that did report still sort correctly, and an unreadable core is
    // treated as slow rather than silently pinned to.
    expect("cpufreq partially readable",
           {0, 0, 0, 0, 2745600, 2745600, 2745600, 3187200},
           4, {4, 5, 6, 7});

    // Degenerate inputs must not pin.
    expect("single core", {2000000}, 0, {});
    expect("no cores", {}, 0, {});

    printf(failures == 0 ? "\nall cpu topology tests passed\n" : "\n%d test(s) failed\n", failures);
    return failures == 0 ? 0 : 1;
}
