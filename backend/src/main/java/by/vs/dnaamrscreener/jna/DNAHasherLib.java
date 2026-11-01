package by.vs.dnaamrscreener.jna;

import com.sun.jna.Library;
import com.sun.jna.Native;

public interface DNAHasherLib extends Library {
    DNAHasherLib INSTANCE = Native.load("dna_hasher", DNAHasherLib.class);

    void compute_minhash_jna(String dnaStr, int k, int numHashes, long[] outSignature);
    double verify_exact_alignment_with_depth(String refDna, String patientData, int[] outDepthArray, int refLen);
    long XXH3_64bits_withSeed(long[] data, long len, long seed);
}