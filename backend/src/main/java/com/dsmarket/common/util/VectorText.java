package com.dsmarket.common.util;

/**
 * float 向量 → PostgreSQL vector 文本格式工具（C2 发布向量化写入用）。
 *
 * <p>pgvector 接受 {@code '[a,b,...]'::vector} 的方括号文本；这里用 float32 的
 * {@link Float#toString} 保留上游返回的原始精度（DashScope 返回即 float32，写入无损）。</p>
 */
public final class VectorText {

    private VectorText() {
    }

    /** 把 float 向量格式化为 pgvector 文本，如 {@code [0.1,0.2,-0.3]}；空/超大输入如实拼接。 */
    public static String of(float[] v) {
        if (v == null) {
            throw new IllegalArgumentException("vector must not be null");
        }
        StringBuilder sb = new StringBuilder(2 + v.length * 5);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(v[i]));
        }
        sb.append(']');
        return sb.toString();
    }
}
