#include <vector>
#include <string>
#include <string_view>
#include <algorithm>
#include <climits>
#include <cstring>
#include <cstdint>
#include <xxhash.h>
#include <edlib.h>
#include <sstream>

namespace {
    inline char get_complement(char base) {
        switch (base) {
            case 'A': return 'T';
            case 'T': return 'A';
            case 'C': return 'G';
            case 'G': return 'C';
            case 'a': return 't';
            case 't': return 'a';
            case 'c': return 'g';
            case 'g': return 'c';
            default:  return base;
        }
    }

    std::string get_reverse_complement(std::string_view dna) {
        std::string rev_comp;
        rev_comp.resize(dna.length());
        const size_t len = dna.length();
        for (size_t i = 0; i < len; ++i) {
            rev_comp[i] = get_complement(dna[len - 1 - i]);
        }
        return rev_comp;
    }

    inline uint64_t char_to_2bit(char c) {
        switch (c) {
            case 'A': case 'a': return 0ULL;
            case 'C': case 'c': return 1ULL;
            case 'G': case 'g': return 2ULL;
            case 'T': case 't': return 3ULL;
            default:  return 0ULL;
        }
    }

    constexpr int kMinKmer = 1;
    constexpr int kMaxKmer = 32;
    constexpr int kMinNumHashes = 1;
    constexpr int kMaxNumHashes = 1024;
    constexpr int kMinAlignLen = 40;
    constexpr double kMinIdentity = 0.80;
    constexpr int kMinReadLen = 30;
}

extern "C" {
    void compute_minhash_jna(const char* dna_str, int k, int num_hashes, long long* out_signature) {
        if (out_signature == nullptr) {
            return;
        }
        if (num_hashes < kMinNumHashes || num_hashes > kMaxNumHashes) {
            return;
        }

        for (int h = 0; h < num_hashes; ++h) {
            out_signature[h] = LLONG_MAX;
        }

        if (dna_str == nullptr) {
            return;
        }
        if (k < kMinKmer || k > kMaxKmer) {
            return;
        }

        const std::string_view dna(dna_str + get_reverse_complement(dna_str));
        const size_t len = dna.length();

        if (len < static_cast<size_t>(k)) {
            return;
        }

        const uint64_t mask = (k == 32) ? ~0ULL : ((1ULL << (static_cast<unsigned>(k) * 2U)) - 1ULL);

        uint64_t current_kmer_bits = 0ULL;
        size_t valid_chars = 0;

        for (size_t i = 0; i < len; ++i) {
            const char c = dna[i];

            if (c == 'N' || c == 'n') {
                valid_chars = 0;
                current_kmer_bits = 0ULL;
                continue;
            }

            current_kmer_bits = ((current_kmer_bits << 2) & mask) | char_to_2bit(c);
            ++valid_chars;

            if (valid_chars >= static_cast<size_t>(k)) {
                for (int h = 0; h < num_hashes; ++h) {
                    const uint64_t hash_val = XXH3_64bits_withSeed(
                        &current_kmer_bits, sizeof(uint64_t), static_cast<XXH64_hash_t>(h));

                    if (static_cast<long long>(hash_val) < out_signature[h]) {
                        out_signature[h] = static_cast<long long>(hash_val);
                    }
                }
            }
        }
    }

    double verify_exact_alignment_with_depth(const char* ref_dna,
                                             const char* patient_data,
                                             int* out_depth_array,
                                             int ref_len) {
        if (ref_dna == nullptr || patient_data == nullptr || ref_len <= 0) {
            return 0.0;
        }

        constexpr int kMaxRefLen = 1'000'000;
        if (ref_len > kMaxRefLen) {
            return 0.0;
        }

        if (out_depth_array != nullptr) {
            std::memset(out_depth_array, 0, static_cast<size_t>(ref_len) * sizeof(int));
        }

        const std::string_view target(ref_dna, static_cast<size_t>(ref_len));

        const size_t patient_len = std::strlen(patient_data);
        constexpr size_t kMaxPatientBytes = 64ULL * 1024ULL * 1024ULL;  // 64 MiB
        if (patient_len == 0 || patient_len > kMaxPatientBytes) {
            return 0.0;
        }

        std::vector<std::string> reads;
        {
            std::istringstream stream(std::string(patient_data, patient_len));
            std::string line;
            while (std::getline(stream, line)) {
                if (!line.empty()) {
                    reads.push_back(std::move(line));
                }
            }
        }

        if (reads.empty()) {
            return 0.0;
        }

        double total_identity = 0.0;
        int successful_alignments = 0;

        const EdlibAlignConfig config = edlibNewAlignConfig(
            -1,
            EDLIB_MODE_HW,
            EDLIB_TASK_LOC,
            nullptr,
            0
        );

        for (const auto& single_read : reads) {
            if (static_cast<int>(single_read.length()) < kMinReadLen) {
                continue;
            }

            if (single_read.length() > 100000) {
                continue;
            }

            EdlibAlignResult result_fwd = edlibAlign(
                single_read.c_str(),
                static_cast<int>(single_read.length()),
                target.data(),
                ref_len,
                config
            );

            const std::string rev_comp = get_reverse_complement(single_read);
            EdlibAlignResult result_rc = edlibAlign(
                rev_comp.c_str(),
                static_cast<int>(rev_comp.length()),
                target.data(),
                ref_len,
                config
            );

            EdlibAlignResult* best = nullptr;

            if (result_fwd.status == EDLIB_STATUS_OK && result_rc.status == EDLIB_STATUS_OK) {
                best = (result_fwd.editDistance <= result_rc.editDistance)
                           ? &result_fwd
                           : &result_rc;
            } else if (result_fwd.status == EDLIB_STATUS_OK) {
                best = &result_fwd;
            } else if (result_rc.status == EDLIB_STATUS_OK) {
                best = &result_rc;
            }

            if (best != nullptr && best->editDistance >= 0 && best->numLocations > 0
                && best->startLocations != nullptr && best->endLocations != nullptr) {

                int start_idx = best->startLocations[0];
                int end_idx   = best->endLocations[0];

                start_idx = std::max(0, start_idx);
                end_idx   = std::min(ref_len - 1, end_idx);

                if (end_idx >= start_idx) {
                    const int align_len = end_idx - start_idx + 1;

                    if (align_len >= kMinAlignLen) {
                        const double identity =
                            1.0 - (static_cast<double>(best->editDistance) / static_cast<double>(align_len));

                        if (identity >= kMinIdentity) {
                            total_identity += identity;
                            ++successful_alignments;

                            if (out_depth_array != nullptr) {
                                for (int pos = start_idx; pos <= end_idx; ++pos) {
                                    // Saturate to avoid int overflow on extremely deep coverage.
                                    if (out_depth_array[pos] < INT_MAX) {
                                        ++out_depth_array[pos];
                                    }
                                }
                            }
                        }
                    }
                }
            }

            edlibFreeAlignResult(result_fwd);
            edlibFreeAlignResult(result_rc);
        }

        if (successful_alignments == 0) {
            return 0.0;
        }
        return total_identity / static_cast<double>(successful_alignments);
    }
}
