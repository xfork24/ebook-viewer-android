#include <charconv>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cstdlib>
static uint64_t rng = 0x9E3779B97F4A7C15ULL;
static uint64_t next() {
    rng ^= rng << 13; rng ^= rng >> 7; rng ^= rng << 17;
    return rng;
}
int main() {
    // 1) random bit patterns (incl. subnormals / extreme exponents)
    for (int i = 0; i < 300000; i++) {
        uint64_t bits = next();
        double d; memcpy(&d, &bits, 8);
        if (d != d || d == (double)INFINITY || d == -(double)INFINITY) continue;
        char b[64]; auto r = std::to_chars(b, b+60, d, std::chars_format::general); *r.ptr = 0;
        printf("%016llx %s\n", (unsigned long long)bits, b);
    }
    // 2) structured: every exponent × every digit-count
    for (int x = -300; x <= 300; x++) {
        for (int p = 1; p <= 17; p++) {
            char buf[64]; buf[0] = '0'; buf[1] = '.';
            for (int j = 0; j < p; j++) buf[2+j] = '1' + (int)((next() % 10));
            int n = 2 + p;
            n += snprintf(buf + n, sizeof(buf) - n, "e%+d", x);
            double d = strtod(buf, NULL);
            uint64_t bits; memcpy(&bits, &d, 8);
            char b[64]; auto r = std::to_chars(b, b+60, d, std::chars_format::general); *r.ptr = 0;
            printf("%016llx %s\n", (unsigned long long)bits, b);
        }
    }
    // 3) specials
    const double sp[] = {0.0, -0.0, 4.9e-324, 2.2250738585072011e-308, 1.7976931348623157e308};
    for (double d : sp) {
        uint64_t bits; memcpy(&bits, &d, 8);
        char b[64]; auto r = std::to_chars(b, b+60, d, std::chars_format::general); *r.ptr = 0;
        printf("%016llx %s\n", (unsigned long long)bits, b);
    }
}
