#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <climits>
#include <string>
#include <vector>
#include <cmath>

extern "C" {
    void compute_minhash_jna(const char* dna_str, int k, int num_hashes, long long* out_signature);
    double verify_exact_alignment_with_depth(const char* ref_dna,
                                             const char* patient_data,
                                             int* out_depth_array,
                                             int ref_len);
}

static int g_failed = 0;
static int g_passed = 0;

#define EXPECT_TRUE(cond) do { \
    if (!(cond)) { \
        std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
        ++g_failed; \
    } else { \
        ++g_passed; \
    } \
} while (0)

#define EXPECT_EQ(a, b) EXPECT_TRUE((a) == (b))
#define EXPECT_NEAR(a, b, eps) EXPECT_TRUE(std::fabs((a) - (b)) < (eps))

static void test_minhash_null_and_invalid() {
    long long sig[8];

    compute_minhash_jna("ACGTACGTACGTACGT", 8, 4, nullptr);

    compute_minhash_jna(nullptr, 8, 4, sig);
    for (int i = 0; i < 4; ++i) {
        EXPECT_EQ(sig[i], LLONG_MAX);
    }

    compute_minhash_jna("ACGTACGTACGTACGT", 0, 4, sig);
    EXPECT_EQ(sig[0], LLONG_MAX);

    compute_minhash_jna("ACGTACGTACGTACGT", 33, 4, sig);
    EXPECT_EQ(sig[0], LLONG_MAX);

    compute_minhash_jna("ACGTACGTACGTACGT", 8, 0, sig);
    compute_minhash_jna("ACGTACGTACGTACGT", 8, 2000, sig);

    compute_minhash_jna("ACGT", 8, 4, sig);
    EXPECT_EQ(sig[0], LLONG_MAX);
}

static void test_minhash_basic() {
    constexpr int k = 8;
    constexpr int n = 10;
    long long sig1[n];
    long long sig2[n];
    long long sig3[n];

    const char* seq = "ACGTACGTACGTACGTACGTACGTACGTACGT";

    compute_minhash_jna(seq, k, n, sig1);
    compute_minhash_jna(seq, k, n, sig2);

    for (int i = 0; i < n; ++i) {
        EXPECT_EQ(sig1[i], sig2[i]);
        EXPECT_TRUE(sig1[i] != LLONG_MAX);
    }

    compute_minhash_jna("TGCATGCATGCATGCATGCATGCATGCATGCA", k, n, sig3);
    int diff = 0;
    for (int i = 0; i < n; ++i) {
        if (sig1[i] != sig3[i]) ++diff;
    }
    EXPECT_TRUE(diff > 0);

    long long sig_lower[n];
    compute_minhash_jna("acgtacgtacgtacgtacgtacgtacgtacgt", k, n, sig_lower);
    for (int i = 0; i < n; ++i) {
        EXPECT_EQ(sig1[i], sig_lower[i]);
    }
}

static void test_minhash_with_N() {
    constexpr int n = 5;
    long long sig[n];

    const char* seq = "ACGTACGTNNACGTACGTACGTACGTACGTAC";
    compute_minhash_jna(seq, 8, n, sig);

    int valid = 0;
    for (int i = 0; i < n; ++i) {
        if (sig[i] != LLONG_MAX) ++valid;
    }
    EXPECT_TRUE(valid > 0);
}

static void test_align_null_and_invalid() {
    int depth[64] = {};
    depth[0] = 1;

    EXPECT_NEAR(verify_exact_alignment_with_depth(nullptr, "ACGT", depth, 4), 0.0, 1e-9);
    EXPECT_NEAR(verify_exact_alignment_with_depth("ACGT", nullptr, depth, 4), 0.0, 1e-9);
    EXPECT_NEAR(verify_exact_alignment_with_depth("ACGT", "ACGT", depth, 0), 0.0, 1e-9);
    EXPECT_NEAR(verify_exact_alignment_with_depth("ACGT", "ACGT", depth, -1), 0.0, 1e-9);

    EXPECT_NEAR(verify_exact_alignment_with_depth("ACGTACGTACGTACGTACGTACGTACGTACGTACGTACGT", "", depth, 40), 0.0, 1e-9);
}

static void test_align_perfect_match() {
    const char* ref =
        "ACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGT";
    const int ref_len = static_cast<int>(std::strlen(ref));

    std::string read = ref;
    EXPECT_TRUE(static_cast<int>(read.size()) >= 30);

    std::vector<int> depth(ref_len, -1);
    double id = verify_exact_alignment_with_depth(ref, read.c_str(), depth.data(), ref_len);

    EXPECT_TRUE(id > 0.95);
    EXPECT_TRUE(id <= 1.0 + 1e-6);

    int covered = 0;
    for (int d : depth) {
        if (d >= 1) ++covered;
    }
    EXPECT_TRUE(covered >= ref_len / 2);
}

static void test_align_reverse_complement() {
    const char* ref =
        "ACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGT";
    const int ref_len = static_cast<int>(std::strlen(ref));

    auto complement = [](char c) -> char {
        switch (c) {
            case 'A': return 'T'; case 'T': return 'A';
            case 'C': return 'G'; case 'G': return 'C';
            default:  return c;
        }
    };
    std::string rc(ref_len, 'N');
    for (int i = 0; i < ref_len; ++i) {
        rc[i] = complement(ref[ref_len - 1 - i]);
    }

    std::vector<int> depth(ref_len, 0);
    double id = verify_exact_alignment_with_depth(ref, rc.c_str(), depth.data(), ref_len);

    EXPECT_TRUE(id > 0.95);
}

static void test_align_no_match() {
    const char* ref =
        "ACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGT";
    const int ref_len = static_cast<int>(std::strlen(ref));

    const char* reads =
        "GGGGCCCCGGGGCCCCGGGGCCCCGGGGCCCCGGGGCCCCGGGGCCCCGGGGCCCCGGGG\n"
        "ATATATATATATATATATATATATATATATATATATATATATATATATATATATATATAT";

    std::vector<int> depth(ref_len, 0);
    double id = verify_exact_alignment_with_depth(ref, reads, depth.data(), ref_len);

    EXPECT_NEAR(id, 0.0, 1e-9);
}

static void test_align_multiple_reads() {
    const char* ref =
        "ACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGT";
    const int ref_len = static_cast<int>(std::strlen(ref));

    std::string payload = std::string(ref) + "\n" + std::string(ref);

    std::vector<int> depth(ref_len, 0);
    double id = verify_exact_alignment_with_depth(ref, payload.c_str(), depth.data(), ref_len);

    EXPECT_TRUE(id > 0.95);

    int max_depth = 0;
    for (int d : depth) {
        if (d > max_depth) max_depth = d;
    }
    EXPECT_TRUE(max_depth >= 2);
}

static void test_align_short_reads_ignored() {
    const char* ref =
        "ACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGT";
    const int ref_len = static_cast<int>(std::strlen(ref));

    const char* short_reads = "ACGTACGT\nACGTACGTACGT";

    std::vector<int> depth(ref_len, 0);
    double id = verify_exact_alignment_with_depth(ref, short_reads, depth.data(), ref_len);
    EXPECT_NEAR(id, 0.0, 1e-9);
}

int main() {
    std::printf("Running dna_hasher tests...\n");

    test_minhash_null_and_invalid();
    test_minhash_basic();
    test_minhash_with_N();

    test_align_null_and_invalid();
    test_align_perfect_match();
    test_align_reverse_complement();
    test_align_no_match();
    test_align_multiple_reads();
    test_align_short_reads_ignored();

    std::printf("Passed: %d  Failed: %d\n", g_passed, g_failed);
    return g_failed == 0 ? 0 : 1;
}
