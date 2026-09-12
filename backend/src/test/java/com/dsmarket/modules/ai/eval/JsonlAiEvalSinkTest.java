package com.dsmarket.modules.ai.eval;

import com.dsmarket.modules.ai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON Lines 落盘单测（REQ-20260908-C5 §1 落点 + §4.1 可重放前提）。
 *
 * <p>只有两条主张值得测：<b>一 record 一行</b>（重放脚本按行解析，一行两 record 就全乱），
 * 以及<b>写不进去时安静地失败</b>（§1 硬规则）。</p>
 */
class JsonlAiEvalSinkTest {

    private JsonlAiEvalSink sinkIn(Path dir) {
        AiProperties props = new AiProperties();
        props.getEval().setDir(dir.toString());
        return new JsonlAiEvalSink(props);
    }

    private List<String> linesOf(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> jsonls = files.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .collect(Collectors.toList());
            assertEquals(1, jsonls.size(), "本用例只该产生一个日志文件，实际=" + jsonls);
            return Files.readAllLines(jsonls.get(0), StandardCharsets.UTF_8);
        }
    }

    @Test
    void appendsOneLinePerRecord_andCreatesDirectory(@TempDir Path tmp) throws IOException {
        // 目录故意不存在：首次落盘必须自己建（部署时不预建 logs/ 是常态）
        Path dir = tmp.resolve("logs/ai-eval");
        JsonlAiEvalSink sink = sinkIn(dir);

        sink.write("{\"a\":1}");
        sink.write("{\"b\":2}");

        assertEquals(List.of("{\"a\":1}", "{\"b\":2}"), linesOf(dir),
                "§4.1：一行一条，重放脚本按行解析 —— 多行会直接读坏");
    }

    @Test
    void fileIsNamedByDay(@TempDir Path tmp) {
        JsonlAiEvalSink sink = sinkIn(tmp);

        Path p = sink.pathFor(LocalDate.of(2026, 9, 10));

        assertEquals("ai-eval-2026-09-10.jsonl", p.getFileName().toString());
        assertEquals(tmp, p.getParent(), "落在配置目录下（§1「logs/ai-eval/」）");
    }

    @Test
    void writtenFile_isUtf8(@TempDir Path tmp) throws IOException {
        // 中文问句是留痕的主要内容。平台默认字符集不是 UTF-8 时（Windows 默认 GBK），
        // 不显式指定编码就会写出一堆乱码，而重放脚本读出来是"合法 JSON、乱码内容"——
        // 比直接报错更难发现。
        JsonlAiEvalSink sink = sinkIn(tmp);

        sink.write("{\"content\":\"预售商品能否退款\"}");

        assertTrue(linesOf(tmp).get(0).contains("预售商品能否退款"));
    }

    @Test
    void unwritableTarget_isSwallowed(@TempDir Path tmp) throws IOException {
        // 把"目录"指向一个已存在的普通文件：createDirectories 必抛，留痕必须咽下去（§1）
        Path notADir = tmp.resolve("blocker");
        Files.writeString(notADir, "x");
        JsonlAiEvalSink sink = sinkIn(notADir.resolve("ai-eval"));

        sink.write("{\"a\":1}"); // 不该抛
    }
}
