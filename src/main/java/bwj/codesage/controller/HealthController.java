package bwj.codesage.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 커스텀 헬스체크 엔드포인트.
 *
 * Railway 배포 시 헬스체크 설정:
 *   Railway 대시보드 → 프로젝트 → Settings → Health Check Path
 *   → /health 또는 /actuator/health 입력
 *
 * /health         : 이 컨트롤러 (status, version, timestamp 포함)
 * /actuator/health: Spring Actuator 기본 헬스체크 (DB 연결 상태 등 포함)
 */
@RestController
public class HealthController {

    private static final String VERSION = "1.0.0";

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "version", VERSION,
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
