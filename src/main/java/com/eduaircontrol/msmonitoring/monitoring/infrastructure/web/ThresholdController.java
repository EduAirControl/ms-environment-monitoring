package com.eduaircontrol.msmonitoring.monitoring.infrastructure.web;

import com.eduaircontrol.msmonitoring.monitoring.application.ThresholdConfigService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Umbrales por ambiente (enmienda a ADR-014).
 *
 * <p>El gateway enruta {@code /api/v1/environments/**} a este servicio, asi que
 * la API cuelga de {@code /environments/{id}/thresholds}. Lectura para cualquier
 * usuario autenticado; escritura solo ADMIN/SUPER_ADMIN (el rol llega en el
 * token JWT que reenvia el gateway).
 */
@PreAuthorize("!hasRole('HEADER')")
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/thresholds")
@RequiredArgsConstructor
public class ThresholdController {

    private final ThresholdConfigService thresholdConfigService;

    /** Umbrales WARNING efectivos del ambiente (los propios o los del tipo). */
    @GetMapping
    public List<ThresholdConfigService.ThresholdView> effective(@PathVariable UUID environmentId) {
        return thresholdConfigService.effective(environmentId);
    }

    /** Crea o reemplaza el override de una variable para este ambiente. */
    @PutMapping("/{variableCode}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ThresholdConfigService.ThresholdView update(
            @PathVariable UUID environmentId,
            @PathVariable String variableCode,
            @RequestBody UpdateRequest request) {
        return thresholdConfigService.updateWarning(
                environmentId, variableCode, request.minValue(), request.maxValue());
    }

    public record UpdateRequest(BigDecimal minValue, BigDecimal maxValue) {
    }
}
