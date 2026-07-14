package com.railwaysim.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局 CORS 配置。
 *
 * <p>此前 CORS 只靠各控制器上的 {@code @CrossOrigin}，而
 * AlarmController、OperationLogController、ServiceHealthController、
 * SimulationRunController 没有该注解——非同源部署（前端不经 Vite 代理）时，
 * 告警确认、操作日志、服务健康和运行历史会被浏览器拦截。
 * 统一在此放开 /api/**，与 WebSocketConfig 的 allowedOrigins("*") 保持一致。
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns("*")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*");
    }
}
