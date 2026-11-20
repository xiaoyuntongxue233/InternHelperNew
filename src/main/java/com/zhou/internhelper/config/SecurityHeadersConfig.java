package com.zhou.internhelper.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 最小安全响应头集：不引入 Spring Security / 鉴权，只补上不影响现有页面行为的头。
 *
 * CSP 特意写成兼容内联脚本的宽松策略——本项目 HTML 大量使用内联 <script>/<style>
 * 与 onclick，严格策略会导致页面直接失效。目标是阻止外部源加载，而非杜绝内联执行。
 */
@Configuration
public class SecurityHeadersConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("X-Frame-Options", "DENY");
                response.setHeader("Referrer-Policy", "no-referrer");
                response.setHeader("Cache-Control", "no-store");
                response.setHeader("Content-Security-Policy",
                        "default-src 'self' 'unsafe-inline' 'unsafe-eval'; img-src 'self' data:; connect-src 'self'");
                return true;
            }
        });
    }
}
