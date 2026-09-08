package com.dsmarket.modules.ai.provider;

import com.dsmarket.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DashScopeEmbeddingClient 响应解析（纯逻辑，不触网）：按序返回 + 维度断言。
 */
class DashScopeEmbeddingClientTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void parseEmbeddings_returnsVectorsInOrder() throws Exception {
        String json = "{\"data\":[{\"embedding\":[1.0,2.0,3.0]},{\"embedding\":[4.0,5.0,6.0]}]}";
        List<float[]> vectors = DashScopeEmbeddingClient.parseEmbeddings(om.readTree(json), 3);

        assertEquals(2, vectors.size());
        assertArrayEquals(new float[]{1f, 2f, 3f}, vectors.get(0));
        assertArrayEquals(new float[]{4f, 5f, 6f}, vectors.get(1));
    }

    @Test
    void parseEmbeddings_dimensionMismatch_throws500() throws Exception {
        String json = "{\"data\":[{\"embedding\":[1.0,2.0]}]}";
        BusinessException ex = assertThrows(BusinessException.class,
                () -> DashScopeEmbeddingClient.parseEmbeddings(om.readTree(json), 1024));
        assertEquals(500, ex.getCode());
        assertTrue(ex.getMessage().contains("维度不符"));
    }

    @Test
    void parseEmbeddings_missingData_throws() throws Exception {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> DashScopeEmbeddingClient.parseEmbeddings(om.readTree("{\"error\":\"x\"}"), 3));
        assertEquals(502, ex.getCode());
    }
}
