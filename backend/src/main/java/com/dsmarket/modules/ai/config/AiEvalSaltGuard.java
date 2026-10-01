package com.dsmarket.modules.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 生产不得用「已公开的盐」算 {@code userIdHash}（审计 12-readiness-audit §2.8②）。
 *
 * <p><b>为什么判据必须落在代码里，不能只靠 YAML</b>：见
 * {@link AiProperties.Eval#hasPrivateSalt()}。一句话概括 2026-09-21 的真机实测结论——
 * <b>{@code @ConfigurationProperties} 绑定时未解析的占位符不抛异常</b>，
 * 所以 {@code application-prod.yml} 里那个"不写默认值"的 {@code ${AI_EVAL_HASH_SALT}}，
 * 在不注入环境变量时<b>根本没拦住任何东西</b>：应用照常启动，盐被原样绑成
 * {@code "${AI_EVAL_HASH_SALT}"} 这个写在仓库里、谁都能读到的固定串 ——
 * 与它想要替换掉的公开默认值<b>等价</b>，而且全程静默。</p>
 *
 * <p>对照：{@code jwt.secret} 走 {@code @Value} 绑定，缺环境变量时抛
 * {@code PlaceholderResolutionException} 拒绝启动（§1.1 的 fail-fast 是真的）。
 * 同一个 YAML 写法，两种绑定路径，两种结果 —— 差别不在 YAML，在绑定器。</p>
 *
 * <p><b>极性：除 dev / test 外一律拒绝启动</b>，而不是"只有 prod 才拦"。
 * 理由是拒错的方向不对称：漏拦一个自定义 profile（staging / 演示 / 客户现场）的代价是
 * "日志里攒了一批可反查的 userIdHash 而没人发现"，等发现时数据已经采完了；
 * 误拦的代价是启动失败 + 一条写明怎么补环境变量的报错。
 * <b>后者可修复，前者不可逆。</b></p>
 *
 * <p>这个极性的代价写在明处：任何非 dev / test 的 profile 都必须提供真盐，
 * 包括那些只是想跑个演示的自定义 profile。这是刻意的，不是疏漏。</p>
 *
 * <p><b>与 {@code AiEvalRecorder} 的启动播报共用同一个谓词</b>
 * （{@link AiProperties.Eval#hasPrivateSalt()}）。两处若各判各的，结果就是播报说了谎
 * —— 那恰好是本次真机第一次跑出来的现象（当时印的是 {@code salt=已配置}，
 * 而环境变量根本没设）。</p>
 */
@Slf4j
@Component
public class AiEvalSaltGuard {

    public AiEvalSaltGuard(AiProperties properties, Environment environment) {
        AiProperties.Eval eval = properties.getEval();
        // 关掉留痕 ⇒ 一条 userIdHash 都不会写出去 ⇒ 盐是什么都无所谓，不拿这个拦启动。
        if (!eval.isEnabled() || eval.hasPrivateSalt()) {
            return;
        }
        if (environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            // dev / test 用公开占位盐是【预期】：那些日志只有本机自己看。
            // 真实状态由 AiEvalRecorder 的启动播报如实写明，这里不重复、也不吵闹。
            return;
        }
        throw new IllegalStateException("""
                【拒绝启动】AI 评估留痕开着，但 ai.eval.hash-salt 不是机密值。原因：%s

                后果不会体现在任何日志或运行时行为里，只体现在【日志文件的内容】上：
                userIdHash = HMAC-SHA256(盐, userId)，而 userId 是 BIGINT 自增主键，
                取值空间就是 1..N 的小整数 —— 盐一旦是公开值，拿它枚举整张表是毫秒级的事。
                也就是说，落盘的那一列 userIdHash 等于明文，伪匿名失效。

                注入一个真随机盐后重启（生成：openssl rand -base64 32）：
                  export AI_EVAL_HASH_SALT="<32 字节以上的随机串>"
                部署脚本的用法：bash deploy/start-prod.sh（脚本会在起进程前先拦住并提示）

                ⚠ 定了就别改：改盐会让全部历史 userIdHash 与新日志对不上，
                  论文按用户 join（P2-H3 沉淀曲线）会断链。
                  本次拒绝启动，正是为了避免"用公开盐采完一批数据之后才发现"。

                若确实不打算留痕，显式关掉即可（关掉后本检查自动跳过）：
                  export AI_EVAL_ENABLED=false
                """.formatted(whyNotPrivate(eval)));
    }

    /** 只描述原因，<b>不回显盐的值</b>：本类只可能在"值不是机密"时被走到，但那个前提不该由调用方来保证。 */
    private static String whyNotPrivate(AiProperties.Eval eval) {
        String salt = eval.getHashSalt();
        if (salt == null || salt.isBlank()) {
            return "盐为空 —— 等于没有盐，退化成对自增主键的裸哈希";
        }
        if (AiProperties.Eval.LOCAL_PLACEHOLDER_SALT.equals(salt)) {
            return "用的是 application.yml 里的内置占位盐，而该字面量已随公开仓库泄露";
        }
        if (salt.startsWith("${")) {
            return "占位符没有解析成功，被原样绑成了字面量 " + salt
                    + " —— 也就是环境变量 AI_EVAL_HASH_SALT 没注入。"
                    + "（@ConfigurationProperties 绑定不会因此抛异常，这正是本类存在的理由）";
        }
        return "未知（判据与 AiProperties.Eval#hasPrivateSalt 不同步了，请一并检查）";
    }
}
