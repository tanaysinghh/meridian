package com.meridian.api.health;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health probes, with the exact bodies the previous service returned.
 *
 * <p>Actuator is on the classpath and its own endpoint stays at {@code /actuator/health}, but these
 * are hand-written because existing monitors and the Render health check parse
 * {@code {"ok": true, "status": "ready"}} — not Actuator's {@code {"status": "UP"}}.
 *
 * <p>The liveness/readiness split matters for how an orchestrator reacts. Liveness answers "is this
 * process wedged?" and must not depend on anything external, or a database blip would get the
 * container killed and restarted into the same blip. Readiness answers "can this instance serve
 * traffic?" and does check the database, so an instance that cannot reach it is pulled from the
 * load balancer and left running to recover.
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    @PersistenceContext
    private EntityManager em;

    /** Liveness — no dependencies, never fails while the process is responsive. */
    @GetMapping("/health/live")
    public Map<String, Object> live() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("status", "live");
        return body;
    }

    /** Readiness — 503 when the database is unreachable. */
    @GetMapping("/health/ready")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            Object result = em.createNativeQuery("SELECT 1").getSingleResult();
            boolean ok = result != null;
            body.put("ok", ok);
            body.put("status", ok ? "ready" : "db_unreachable");
            return ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
        } catch (Exception ex) {
            log.error("health_check_failed reason={}", ex.getClass().getSimpleName());
            body.put("ok", false);
            body.put("status", "db_unreachable");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }

    /** Legacy alias, kept for monitors that predate the live/ready split. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("service", "meridian-api");
        return body;
    }
}
