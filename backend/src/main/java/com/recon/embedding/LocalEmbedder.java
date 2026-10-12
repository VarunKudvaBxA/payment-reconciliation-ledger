package com.recon.embedding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Works offline. Hashes character trigrams and whole words into 256 buckets.
 * It catches abbreviations, reordered words and noise but it does not understand meaning,
 * so for real semantic matching set EMBEDDING_MODE=openai and use RemoteEmbedder.
 */
@Component
@ConditionalOnProperty(name = "recon.embedding.mode", havingValue = "local", matchIfMissing = true)
public class LocalEmbedder implements Embedder {

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        for (String t : texts) {
            out.add(embedOne(t));
        }
        return out;
    }

    private float[] embedOne(String text) {
        String s = clean(text);
        float[] v = new float[DIMS];
        String padded = " " + s + " ";
        for (int i = 0; i + 3 <= padded.length(); i++) {
            v[Math.floorMod(padded.substring(i, i + 3).hashCode(), DIMS)] += 1f;
        }
        for (String word : s.split(" ")) {
            if (word.length() > 1) {
                v[Math.floorMod(("w:" + word).hashCode(), DIMS)] += 2f;
            }
        }
        return VectorUtil.unitLength(v);
    }

    static String clean(String text) {
        if (text == null) return "";
        return text.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }
}
