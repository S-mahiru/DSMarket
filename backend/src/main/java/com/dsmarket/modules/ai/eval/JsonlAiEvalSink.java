package com.dsmarket.modules.ai.eval;

import com.dsmarket.modules.ai.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;

/**
 * JSON Lines 落盘实现（REQ-20260908-C5 §1：{@code logs/ai-eval/}，一行一条结构化 JSON，
 * 毕设不建新表）。
 *
 * <p><b>按天切文件</b>：REQ 未指定切分口径（实施取值）。同一天的所有事件在同一文件里，
 * 重放脚本按文件集合读即可；跨天切分是为了单文件不无限增长，且"某天的数据"能整份归档。</p>
 *
 * <p><b>并发</b>：{@link #write} 是 synchronized 的 —— /chat 是每用户一个线程并发跑，
 * 不串行化会让两行 JSON 交错成一行坏数据。代价是写盘成为串行点，但一行 <1KB 的 append
 * 在本机是微秒级，且这条路径<b>不在</b>回答关键路径上（在 finally 里）。</p>
 *
 * <p><b>每行 flush</b>：用 {@code Files.writeString(..., APPEND)} 而非常驻 BufferedWriter，
 * 语义就是"写一条即落一条"。进程被 kill（harness 常态）时不丢已写的留痕；
 * 代价是每行一次 open/write/close，对本项目的事件量（每分钟个位数）完全可忽略。</p>
 *
 * <p><b>多实例边界（诚实声明）</b>：本实现只保证<b>单进程内</b>行不交错。多实例部署到共享目录
 * 会交错（append 的原子性只在单机小写入下成立）。毕设是单实例本地环境，不为此加锁文件。</p>
 */
@Slf4j
@Component
public class JsonlAiEvalSink implements AiEvalSink {

    /** 文件名前缀（重放脚本按 {@code ai-eval-*.jsonl} 收集） */
    public static final String FILE_PREFIX = "ai-eval-";
    public static final String FILE_SUFFIX = ".jsonl";

    private final AiProperties.Eval config;

    public JsonlAiEvalSink(AiProperties properties) {
        this.config = properties.getEval();
    }

    /** 某一天的落盘路径（包级可见：单测直接断言路径口径，不必真写文件） */
    Path pathFor(LocalDate day) {
        return Paths.get(config.getDir(), FILE_PREFIX + day + FILE_SUFFIX);
    }

    @Override
    public synchronized void write(String jsonLine) {
        try {
            Path path = pathFor(LocalDate.now());
            Path dir = path.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Files.writeString(path, jsonLine + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            // 唯一允许的失败姿态（C5 §1）：记一条日志，然后当没发生过。
            // 注意这里 catch 的是 Exception 而非 IOException —— createDirectories 会抛
            // SecurityException、Charset 相关的运行时异常也在列，留痕没有"该崩就崩"的余地。
            log.warn("[ai][c5] 留痕写盘失败（已忽略，本轮对话不受影响）: {}", e.toString());
        }
    }
}
