#include <vector>
#include <string>
#include <string_view>
#include <algorithm>
#include <climits>
#include <cstring>
#include <zlib.h>
#include <xxhash.h>
#include <edlib.h>
#include <sstream>

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
        case 'A': return 0ULL;
        case 'C': return 1ULL;
        case 'G': return 2ULL;
        case 'T': return 3ULL;
        default:  return 0ULL;
    }
}

extern "C" {
    void compute_minhash_jna(const char* dna_str, int k, int num_hashes, long long* out_signature) {
        std::string dna(dna_str);
        size_t len = dna.length();

        for (int h = 0; h < num_hashes; ++h) {
            out_signature[h] = LLONG_MAX;
        }

        if (len < (size_t)k) return;

        uint64_t mask = (k == 32) ? ~0ULL : (1ULL << (k * 2)) - 1;
        uint64_t current_kmer_bits = 0ULL;
        size_t valid_chars = 0;

        for (size_t i = 0; i < len; ++i) {
            char c = dna[i];

            if (c == 'N') {
                valid_chars = 0;
                continue;
            }

            current_kmer_bits = ((current_kmer_bits << 2) & mask) | char_to_2bit(c);
            valid_chars++;

            if (valid_chars >= (size_t)k) {

                for (int h = 0; h < num_hashes; ++h) {
                    uint64_t hash_val = XXH3_64bits_withSeed(&current_kmer_bits, sizeof(uint64_t), h);

                    if ((long long)hash_val < out_signature[h]) {
                        out_signature[h] = (long long)hash_val;
                    }
                }
            }
        }
    }

    double verify_exact_alignment_with_depth(const char* ref_dna, const char* patient_data, int* out_depth_array, int ref_len) {
        if (!ref_dna || !patient_data || !out_depth_array || ref_len <= 0) {
            return 0.0;
        }

        std::memset(out_depth_array, 0, ref_len * sizeof(int));
        std::string target(ref_dna);
        std::string query_stream(patient_data);
        std::vector<std::string> reads;
        std::string line;
        std::istringstream stream(query_stream);

        while (std::getline(stream, line)) {
            if (!line.empty()) {
                reads.push_back(line);
            }
        }

        if (reads.empty()) {
            return 0.0;
        }

        double total_identity = 0.0;
        int successful_alignments = 0;

        EdlibAlignConfig config = edlibNewAlignConfig(
            -1,
            EDLIB_MODE_HW,
            EDLIB_TASK_LOC,
            nullptr,
            0
        );

        for (const auto& single_read : reads) {
            if (single_read.length() < 30) continue;

            EdlibAlignResult result_fwd = edlibAlign(
                single_read.c_str(),
                static_cast<int>(single_read.length()),
                target.c_str(),
                ref_len,
                config
            );

            std::string rev_comp = get_reverse_complement(single_read);
            EdlibAlignResult result_rc = edlibAlign(
                rev_comp.c_str(),
                static_cast<int>(rev_comp.length()),
                target.c_str(),
                ref_len,
                config
            );

            EdlibAlignResult* best = nullptr;
            bool use_fwd = true;

            auto is_better = [](const EdlibAlignResult& a, const EdlibAlignResult& b) -> bool {
                if (a.status != EDLIB_STATUS_OK) return false;
                if (b.status != EDLIB_STATUS_OK) return true;
                return a.editDistance < b.editDistance;
            };

            if (result_fwd.status == EDLIB_STATUS_OK && result_rc.status == EDLIB_STATUS_OK) {
                if (result_fwd.editDistance <= result_rc.editDistance) {
                    best = &result_fwd;
                    use_fwd = true;
                } else {
                    best = &result_rc;
                    use_fwd = false;
                }
            } else if (result_fwd.status == EDLIB_STATUS_OK) {
                best = &result_fwd;
                use_fwd = true;
            } else if (result_rc.status == EDLIB_STATUS_OK) {
                best = &result_rc;
                use_fwd = false;
            }

            if (best != nullptr && best->editDistance >= 0 && best->numLocations > 0) {
                int start_idx = best->startLocations[0];
                int end_idx   = best->endLocations[0];

                start_idx = std::max(0, start_idx);
                end_idx   = std::min(ref_len - 1, end_idx);

                int align_len = end_idx - start_idx + 1;

                if (align_len >= 40) {
                    double identity = 1.0 - (static_cast<double>(best->editDistance) / align_len);

                    if (identity >= 0.80) {
                        total_identity += identity;
                        successful_alignments++;

                        for (int pos = start_idx; pos <= end_idx; ++pos) {
                            out_depth_array[pos] += 1;
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
        return total_identity / successful_alignments;
    }
}