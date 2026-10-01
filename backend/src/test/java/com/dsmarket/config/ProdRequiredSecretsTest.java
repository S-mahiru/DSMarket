package com.dsmarket.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产机密的<b>兜底必须不存在</b>（审计 §1.1 / §1.3 / §2.8②）。
 *
 * <p>本项目治理这四个机密的办法是同一个：{@code application-prod.yml} 里写
 * <b>没有默认值</b>的占位符（{@code ${VAR}} 而不是 {@code ${VAR:某值}}），
 * 未注入时 Spring 直接以 {@code Could not resolve placeholder} 启动失败。
 * 防的是同一件事——<b>静默回落</b>：仓库是公开的，凡是写进仓库的字面量都等于公开值，
 * 而回落到公开值的后果是"应用照常启动、照常服务，只是密钥/口令/盐已经不是秘密了"，
 * 从外部完全看不出来。</p>
 *
 * <p><b>为什么值得单独一条测试</b>：这个性质只由 YAML 里<b>少写一段</b>来保证 ——
 * 少写的内容不会报错、不会告警，任何人"顺手补个默认值好让本地能跑起来"就把它拆了，
 * 而拆掉之后 635 条测试<b>一条都不会红</b>（没有测试启动 prod profile）。</p>
 *
 * <p><b>断言的是占位符本身，不是解析后的值</b>：直接用 {@code YamlPropertySourceLoader}
 * 读原始 YAML，拿到的是字面量 {@code ${JWT_SECRET}}。若有人把它改成
 * {@code ${JWT_SECRET:dev-only-jwt-secret-…}}，这里断言的字符串就变了 ⇒ 转红。
 * 换句话说，这条测试钉的正是"那个冒号后面什么都没有"。</p>
 *
 * <p><b>反向突变（本类会红吗）</b>：把任意一个键改成带默认值的形式（例如
 * {@code hash-salt: ${AI_EVAL_HASH_SALT:dsmarket-ai-eval-local}}）⇒ 对应用例转红，
 * 其余三条不动。<b>只改值、不删行</b>：删行会把 YAML 结构一起改坏，
 * 那样红的是解析失败、不是本判据，归因就不干净了。</p>
 *
 * <p>本条<b>不覆盖</b> {@code deploy/start-prod.sh} / {@code .bat} 里那几道
 * {@code require_env} 前置拦截 —— 它们的作用只是把失败原因说清楚（占位符解析失败的
 * 报错不讲人话），真正的兜底是上面这个"没有默认值"。脚本与 YAML 是两道，
 * 这里钉的是承重的那一道。</p>
 */
class ProdRequiredSecretsTest {

    /**
     * 键 → 必须喂它的环境变量名。四个都必须是"无默认值的占位符"，且名字要与运维文档一致。
     *
     * <p>用 {@code LinkedHashMap} 而非 {@code Map.of} 只是为了失败时按声明顺序报，
     * 与判据本身无关。</p>
     */
    private static final Map<String, String> PROD_REQUIRED_SECRETS = new LinkedHashMap<>();

    static {
        PROD_REQUIRED_SECRETS.put("jwt.secret", "JWT_SECRET");                       // 审计 §1.1
        PROD_REQUIRED_SECRETS.put("spring.datasource.password", "DB_PASSWORD");      // 审计 §1.3
        PROD_REQUIRED_SECRETS.put("spring.data.redis.password", "REDIS_PASSWORD");   // 审计 §1.3
        PROD_REQUIRED_SECRETS.put("ai.eval.hash-salt", "AI_EVAL_HASH_SALT");         // 审计 §2.8②
    }

    private static PropertySource<?> prodYaml() {
        try {
            return new YamlPropertySourceLoader()
                    .load("application-prod", new ClassPathResource("application-prod.yml"))
                    .get(0);
        } catch (IOException e) {
            throw new IllegalStateException("读不到 application-prod.yml —— 本测试的对象就是它", e);
        }
    }

    @Test
    void 四个生产机密在prod里都必须是无默认值的占位符() {
        PropertySource<?> prod = prodYaml();
        PROD_REQUIRED_SECRETS.forEach((key, envVar) -> {
            Object raw = prod.getProperty(key);
            assertThat(raw)
                    .as("%s 在 application-prod.yml 里根本不存在 —— 那它就会从 application.yml "
                            + "继承默认值，而那份默认值是公开的", key)
                    .isNotNull();
            assertThat(String.valueOf(raw))
                    .as("%s 必须是【没有默认值】的 ${%s}。写成 ${%s:某值} 会让生产静默回落到"
                            + "那个已公开的值 —— 应用照常启动，只是秘密不再是秘密", key, envVar, envVar)
                    .isEqualTo("${" + envVar + "}");
        });
    }

    @Test
    void 盐的默认值只在本地生效_生产用的那个键确实被覆盖了() {
        // 承上：上面那条证明 prod 覆盖成了无默认占位符。这条防的是另一种写法 ——
        // 有人把 prod 的 ai.eval.hash-salt 整段删掉，以为"反正 application.yml 里有"。
        // 那种情况下 prod.getProperty 返回 null，上面那条会红；这里再补一句说明后果，
        // 让失败信息直接指向"生产会用公开盐算 userIdHash"。
        Object raw = prodYaml().getProperty("ai.eval.hash-salt");
        assertThat(raw)
                .as("prod 缺失 ai.eval.hash-salt ⇒ 继承 application.yml 的公开占位盐 ⇒ "
                        + "userIdHash（自增主键的 HMAC）可被枚举反查，伪匿名失效")
                .isNotNull();
    }
}
