package com.dsmarket.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * VectorText：float 向量 → pgvector 方括号文本（C2 发布向量化写入格式）。
 */
class VectorTextTest {

    @Test
    void of_formatsFloatVectorAsPgvectorText() {
        assertEquals("[1.0,2.0,3.0]", VectorText.of(new float[]{1f, 2f, 3f}));
    }

    @Test
    void of_singleAndEmpty_areHandled() {
        assertEquals("[5.0]", VectorText.of(new float[]{5f}));
        assertEquals("[]", VectorText.of(new float[0]));
    }

    @Test
    void of_preservesFloat32Precision_notDouble() {
        // Float.toString 保留 float32 精度（1024 维向量 Float.valueOf 无损往返）
        float[] v = {0.1f, -0.12345678f, 1.5f};
        String text = VectorText.of(v);
        String[] parts = text.substring(1, text.length() - 1).split(",");
        for (int i = 0; i < v.length; i++) {
            assertEquals(Float.toString(v[i]), parts[i]);
        }
    }

    @Test
    void of_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> VectorText.of(null));
    }
}
