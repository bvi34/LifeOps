// Host test for complete_utf8_prefix in advisor_llm.cpp.
//
// Streaming hands raw model bytes across the JNI boundary, and a token boundary is not a character
// boundary: one emoji or CJK glyph routinely spans two tokens. Emitting a truncated sequence produces
// replacement characters at best and a JVM abort at worst, and it only shows up for text nobody tests
// with. So the boundary rule is pure, and this pins it down on the host.
//
// It lifts the function out of advisor_llm.cpp (see run.sh) rather than duplicating it.
#include <cstdio>
#include <string>
#include <algorithm>

#include "complete_utf8_prefix.inc"

namespace {

int failures = 0;

void expect(const char* name, const std::string& input, size_t want) {
    const size_t got = complete_utf8_prefix(input);
    if (got != want) {
        failures++;
        printf("FAIL %-42s got %zu want %zu\n", name, got, want);
    } else {
        printf("ok   %-42s %zu of %zu bytes\n", name, got, input.size());
    }
}

} // namespace

int main() {
    expect("empty", "", 0);
    expect("plain ascii", "Mow the lawn.", 13);

    // 'é' is 2 bytes: whole, then split after the lead byte.
    expect("2-byte char complete", "caf\xC3\xA9", 5);
    expect("2-byte char truncated", "caf\xC3", 3);

    // '…' is 3 bytes.
    expect("3-byte char complete", "wait\xE2\x80\xA6", 7);
    expect("3-byte char 1 of 3", "wait\xE2", 4);
    expect("3-byte char 2 of 3", "wait\xE2\x80", 4);

    // A 4-byte emoji — the case NewStringUTF would have mangled, split at every offset.
    expect("4-byte emoji complete", "hi \xF0\x9F\x91\x8B", 7);
    expect("4-byte emoji 1 of 4", "hi \xF0", 3);
    expect("4-byte emoji 2 of 4", "hi \xF0\x9F", 3);
    expect("4-byte emoji 3 of 4", "hi \xF0\x9F\x91", 3);

    // Text after a complete multi-byte char is not held back.
    expect("complete char then ascii", "caf\xC3\xA9 open", 10);

    // Garbage must not make the caller hold bytes back forever: a lone continuation byte, and a
    // sequence longer than any real one, both settle rather than stall.
    expect("stray continuation byte", "\x80", 1);
    expect("five continuation bytes", "\x80\x80\x80\x80\x80", 5);
    expect("invalid lead byte", "ok\xFF", 3);

    printf(failures == 0 ? "\nall utf-8 boundary tests passed\n" : "\n%d test(s) failed\n", failures);
    return failures == 0 ? 0 : 1;
}
