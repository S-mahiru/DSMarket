package com.dsmarket.modules.ai.eval;

/**
 * 留痕落点（REQ-20260908-C5 §1「落点：JSON Lines 磁盘日志」）。
 *
 * <p><b>实现契约（硬）</b>：{@link #write} <b>永不抛异常</b>。C5 §1 明令"写日志失败仅记日志，
 * 不影响主流程"——磁盘满、目录只读、权限不足都不能让一轮对话挂掉。实现侧必须 catch 一切
 * （含 {@link Error} 之外的 Throwable 边界按需），把失败降级成一条 warn。</p>
 *
 * <p>抽象成接口的另一个原因：断言"关掉开关后行为不变"需要一个<b>能数写入次数</b>的替身，
 * 直接依赖文件实现就只能靠读磁盘，测不出"没写"与"写了空行"的区别。</p>
 */
public interface AiEvalSink {

    /**
     * 追加一条留痕（已是完整 JSON 对象字面量，不含换行）。
     *
     * @param jsonLine 待落盘的一行 JSON
     */
    void write(String jsonLine);
}
