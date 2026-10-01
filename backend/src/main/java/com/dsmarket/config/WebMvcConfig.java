package com.dsmarket.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${web.cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    @Value("${web.upload.dir:./uploads}")
    private String uploadDir;

    /**
     * CORS 配置（审计 §2.6）。
     *
     * <p><b>用 {@code allowedOrigins} 而不是 {@code allowedOriginPatterns}</b>：两者在正常取值下
     * 行为一致，差别只在被误配成 {@code *} 时 ——
     * {@code allowedOriginPatterns("*")} + {@code allowCredentials(true)} 是 Spring **允许**的组合，
     * 它会反射任意 Origin 并带上 {@code Access-Control-Allow-Credentials: true}，
     * 且没有任何报错；而 {@code allowedOrigins("*")} + 凭据会被 Spring 直接拒掉。
     * 也就是说：把「静默危险」换成了「响亮失败」，代价是配置里不能再写通配模式。</p>
     *
     * <p>注意这里<b>没有</b>给 {@code allowed-origins} 留 {@code *} 默认值，默认值是
     * {@code http://localhost:5173}（dev）/ {@code http://localhost:8088}（prod），本就安全 ——
     * 这条修的是"有人把它填成 {@code *}"时会发生什么。</p>
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // /uploads/** 映射到磁盘 uploads 目录（商品图片、头像等上传文件）
        String location = Paths.get(uploadDir).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}
