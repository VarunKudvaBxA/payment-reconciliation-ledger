package com.recon;

import com.recon.embedding.LocalEmbedder;
import com.recon.embedding.VectorUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalEmbedderTest {

    private final LocalEmbedder embedder = new LocalEmbedder();

    @Test
    void vectorsAreUnitLength() {
        float[] v = embedder.embed("Card payment from ALICE");
        assertEquals(256, v.length);
        assertEquals(1.0, VectorUtil.cosine(v, v), 0.001);
    }

    @Test
    void messyVersionOfSameDescriptionIsCloserThanAnUnrelatedOne() {
        float[] original = embedder.embed("Card payment from ALICE");
        float[] messy = embedder.embed("TXN 55231 ALICE CARD PMT FROM");
        float[] unrelated = embedder.embed("Payout to BOB bank account");

        assertTrue(VectorUtil.cosine(original, messy) > VectorUtil.cosine(original, unrelated));
    }
}
