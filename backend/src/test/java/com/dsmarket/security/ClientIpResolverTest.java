package com.dsmarket.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 信任边界开关本身的语义（审计 §2.4）。
 *
 * <p>这里是<b>纯逻辑</b>测试：{@code trust-proxy-header} 取两种值时分别该读哪个来源，
 * 完全由本类的方法决定，不涉及"框架会不会调用它" —— 那一层由
 * {@code AuthRateLimitIntegrationTest} 走真实 {@code DispatcherServlet} 来证
 * （本项目已有的教训：直接调方法的单测证明不了框架会不会调用它）。</p>
 */
class ClientIpResolverTest {

    private static final String HEADER = "X-Real-IP";

    @Test
    void 不信任代理头时忽略头部取值() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.7");
        request.addHeader(HEADER, "1.2.3.4");

        assertThat(new ClientIpResolver(false, HEADER).resolve(request))
                .as("关掉信任后，头部是普通请求头，不该被采信")
                .isEqualTo("10.0.0.7");
    }

    @Test
    void 信任代理头时取头部取值() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader(HEADER, "1.2.3.4");

        assertThat(new ClientIpResolver(true, HEADER).resolve(request)).isEqualTo("1.2.3.4");
    }

    @Test
    void 信任代理头但缺头时回落到remoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        assertThat(new ClientIpResolver(true, HEADER).resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void 信任代理头但头为空白时回落到remoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader(HEADER, "   ");

        assertThat(new ClientIpResolver(true, HEADER).resolve(request))
                .as("空白头若被采信，所有请求会挤进同一个空桶")
                .isEqualTo("127.0.0.1");
    }

    @Test
    void 取不到任何地址时返回unknown而非空值() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(null);

        assertThat(new ClientIpResolver(false, HEADER).resolve(request))
                .as("返回空值会让限流键以空串结尾，把互不相干的请求并进同一个桶")
                .isEqualTo("unknown");
    }
}
