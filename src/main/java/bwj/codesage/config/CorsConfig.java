package bwj.codesage.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 환경별 CORS 설정.
 *
 * 개발 환경:
 *   cors.allowed-origins=* (application.properties 기본값)
 *
 * 운영 환경 (Railway 배포 시):
 *   Railway 대시보드 → 프로젝트 → Variables 탭에서 아래 환경변수 추가
 *   CORS_ALLOWED_ORIGINS=https://your-frontend.vercel.app
 *
 *   여러 프론트엔드 도메인을 허용해야 하는 경우 쉼표로 구분:
 *   CORS_ALLOWED_ORIGINS=https://foo.vercel.app,https://bar.netlify.app
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = parseOrigins(allowedOrigins);
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    private String[] parseOrigins(String raw) {
        if (raw == null || raw.isBlank() || raw.equals("*")) {
            return new String[]{"*"};
        }
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }
}
