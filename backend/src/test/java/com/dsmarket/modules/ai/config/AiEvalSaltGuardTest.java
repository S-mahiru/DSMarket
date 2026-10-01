package com.dsmarket.modules.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生产不得用公开的盐（审计 §2.8② 的可执行判据）。
 *
 * <p><b>这个类存在的理由是一次实测翻车</b>：修法第一版只改了 YAML ——
 * {@code application-prod.yml} 里写 {@code hash-salt: ${AI_EVAL_HASH_SALT}}（不写默认值），
 * 以为照搬 {@code jwt.secret} 那一套就能 fail-fast。真机跑下来<b>应用照常启动</b>，
 * 盐被原样绑成 {@code "${AI_EVAL_HASH_SALT}"}。原因是绑定路径不同：
 * {@code @ConfigurationProperties} 走 {@code Binder} +
 * {@code PropertySourcesPlaceholdersResolver}，后者 {@code ignoreUnresolvablePlaceholders = true}，
 * 解不出来<b>不抛异常</b>，把原串绑进来了事。</p>
 *
 * <p>⇒ 所以本类的断言不看"YAML 写没写默认值"，只看
 * <b>"{@link AiEvalSaltGuard} 在 prod 下会不会拒绝启动"</b>。</p>
 *
 * <p><b>正向对照不可省</b>（{@link #prod下给了真盐_正常启动}）：没有它，
 * "所有情况都拒绝启动"（例如守卫写成了无条件抛）会冒充成"守卫生效"。
 * 同理 {@link #dev下用占位盐_不拦} 钉住"dev 不该被牵连"。</p>
 *
 * <p><b>反向突变（本类会红吗）</b>：把 {@link AiEvalSaltGuard} 里那个
 * {@code throw} 换成 {@code log.warn} ⇒ 前三条转红，两条正向对照仍绿；
 * 把判据 {@code hasPrivateSalt()} 里的 {@code startsWith("${")} 那一条删掉 ⇒
 * 只有"未解析占位符"那条转红（这正是真机翻车的那一种，也是最容易只靠脑补就漏掉的一种）。</p>
 */
class AiEvalSaltGuardTest {

    private static final String PUBLIC_SALT = AiProperties.Eval.LOCAL_PLACEHOLDER_SALT;

    /** 真机实测里被原样绑进来的那个字面量（也就是"环境变量没注入"的样子） */
    private static final String UNRESOLVED = "${AI_EVAL_HASH_SALT}";

    private static AiEvalSaltGuard guard(String profile, boolean enabled, String salt) {
        AiProperties properties = new AiProperties();
        properties.getEval().setEnabled(enabled);
        properties.getEval().setHashSalt(salt);
        MockEnvironment env = new MockEnvironment();
        if (profile != null) {
            env.setActiveProfiles(profile);
        }
        return new AiEvalSaltGuard(properties, env);
    }

    @Test
    void prod下用公开占位盐_拒绝启动() {
        assertThatThrownBy(() -> guard("prod", true, PUBLIC_SALT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝启动")
                .hasMessageContaining("已随公开仓库泄露");
    }

    @Test
    void prod下占位符没解析成功_同样拒绝启动() {
        // ★ 真机翻车的那一种：YAML 里明明"没有默认值"，绑定器却把 ${...} 原样塞了进来。
        //   只比对"是不是内置占位盐"的旧判据认不出它（它会说"已配置"）。
        assertThatThrownBy(() -> guard("prod", true, UNRESOLVED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("占位符没有解析成功")
                .hasMessageContaining("AI_EVAL_HASH_SALT");
    }

    @Test
    void prod下盐为空_拒绝启动() {
        assertThatThrownBy(() -> guard("prod", true, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("盐为空");
    }

    @Test
    void 自定义profile也拦_不是只有prod() {
        // 极性是"除 dev/test 外一律拦"。这条钉住它，免得有人为了省事把判据缩成 == "prod"。
        assertThatThrownBy(() -> guard("staging", true, PUBLIC_SALT))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── 正向对照 ──────────────────────────────────────────────────────────

    @Test
    void prod下给了真盐_正常启动() {
        assertThatCode(() -> guard("prod", true, "a-real-random-salt-from-env-not-in-git"))
                .doesNotThrowAnyException();
    }

    @Test
    void prod下关掉留痕_不拦启动() {
        // 一条 userIdHash 都不会写出去时，盐是什么都无所谓 —— 不该拿这个卡住启动。
        assertThatCode(() -> guard("prod", false, PUBLIC_SALT)).doesNotThrowAnyException();
    }

    @Test
    void dev下用占位盐_不拦() {
        // dev/test 的日志只有本机自己看，公开占位盐在那里的后果与生产完全不同。
        assertThatCode(() -> guard("dev", true, PUBLIC_SALT)).doesNotThrowAnyException();
        assertThatCode(() -> guard("test", true, PUBLIC_SALT)).doesNotThrowAnyException();
    }

    // ── 谓词本身 ──────────────────────────────────────────────────────────

    @Test
    void 谓词与守卫同源_四个边界() {
        AiProperties.Eval e = new AiProperties().getEval();
        assertThat(e.hasPrivateSalt()).as("默认就是公开占位盐").isFalse();

        e.setHashSalt(null);
        assertThat(e.hasPrivateSalt()).isFalse();
        e.setHashSalt("   ");
        assertThat(e.hasPrivateSalt()).isFalse();
        e.setHashSalt(UNRESOLVED);
        assertThat(e.hasPrivateSalt()).as("未解析的占位符不是机密").isFalse();

        e.setHashSalt("Rk9vQmFyQmF6UXV4MTIzNDU2Nzg5MA");
        assertThat(e.hasPrivateSalt()).isTrue();
    }
}
