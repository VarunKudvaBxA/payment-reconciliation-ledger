package com.recon.embedding;

import java.util.List;

public interface Embedder {
    /** must match vector(256) in the migrations */
    int DIMS = 256;

    List<float[]> embedAll(List<String> texts);

    default float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }
}
