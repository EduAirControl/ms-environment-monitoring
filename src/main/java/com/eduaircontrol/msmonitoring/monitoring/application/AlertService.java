package com.eduaircontrol.msmonitoring.monitoring.application;

import com.eduaircontrol.msmonitoring.analysis.domain.model.PageResult;
import com.eduaircontrol.msmonitoring.monitoring.domain.model.EnvironmentAlert;
import com.eduaircontrol.msmonitoring.monitoring.domain.port.in.AlertUseCase;
import com.eduaircontrol.msmonitoring.monitoring.domain.port.out.AlertRepository;
import com.eduaircontrol.msmonitoring.monitoring.domain.port.out.MeasurementRepository;
import com.eduaircontrol.msmonitoring.shared.contract.VariableCatalogPort;
import com.eduaircontrol.msmonitoring.shared.exception.ConflictException;
import com.eduaircontrol.msmonitoring.shared.exception.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generacion y gestion de alertas.
 *
 * <p>Las alertas se generan cuando una medicion excede el umbral activo. Son
 * trazables: referencian el umbral y la medicion que las disparo. Solo puede
 * haber una alerta vigente (no borrada) por ambiente+variable: mientras la
 * excedencia persista no se generan mas, reconozca o no la anterior (evita el
 * spam de una alerta nueva por medicion cada 10 s). Si la severidad escala, la
 * vigente se reemplaza por la mas grave. Cuando la medicion vuelve al rango
 * normal se cierra sola via {@link #resolveOpen}; una excedencia futura de ese
 * par si genera una alerta nueva (es otro evento).
 */
@Service
@RequiredArgsConstructor
public class AlertService implements AlertUseCase {

    private final AlertRepository alertRepository;
    private final MeasurementRepository measurementRepository;
    private final VariableCatalogPort variableCatalogPort;
    private final Clock clock;

    /**
     * Genera una alerta si no existe ya una vigente para el mismo ambiente+variable.
     * Idempotente: reprocesar no duplica, y mientras la excedencia persista no se
     * re-alarta (salvo escalada de severidad, que reemplaza a la vigente).
     */
    @Transactional
    public EnvironmentAlert raiseIfAbsent(UUID environmentId, UUID variableId,
                                          UUID thresholdId, UUID measurementId,
                                          String severityCode, Instant at) {
        List<EnvironmentAlert> sameMeasurement =
                alertRepository.findActiveByMeasurement(measurementId);
        if (!sameMeasurement.isEmpty()) {
            return sameMeasurement.get(0);
        }
        List<EnvironmentAlert> open =
                alertRepository.findOpenByEnvironmentAndVariable(environmentId, variableId);
        if (!open.isEmpty()) {
            EnvironmentAlert current = open.get(0);
            if (severityRank(severityCode) <= severityRank(current.getSeverityCode())) {
                return current; // ya hay alerta vigente igual o mas grave: no se re-alarta
            }
            // Escalada (p. ej. WARNING -> CRITICAL): la vigente queda reemplazada
            // para que el panel no muestre dos alertas del mismo evento.
            for (EnvironmentAlert stale : open) {
                stale.softDelete(clock.instant());
                alertRepository.save(stale);
            }
        }
        EnvironmentAlert alert = EnvironmentAlert.raise(
                environmentId, variableId, thresholdId, measurementId, severityCode, at);
        return alertRepository.save(alert);
    }

    /** Cierra las alertas vigentes del par cuando la medicion vuelve a rango. */
    @Override
    @Transactional
    public void resolveOpen(UUID environmentId, UUID variableId) {
        for (EnvironmentAlert alert :
                alertRepository.findOpenByEnvironmentAndVariable(environmentId, variableId)) {
            alert.softDelete(clock.instant());
            alertRepository.save(alert);
        }
    }

    private static int severityRank(String severityCode) {
        if ("CRITICAL".equalsIgnoreCase(severityCode)) {
            return 3;
        }
        if ("WARNING".equalsIgnoreCase(severityCode)) {
            return 2;
        }
        return 1; // INFO o desconocido
    }

    @Override
    @Transactional(readOnly = true)
    public List<AlertView> list(AlertQuery query) {
        Boolean active = query.status() == null ? null
                : !"acknowledged".equalsIgnoreCase(query.status());
        PageResult<EnvironmentAlert> result = alertRepository.search(
                query.environmentId(), active, query.from(), query.to(),
                query.page(), query.limit());
        return result.content().stream().map(this::toView).toList();
    }

    @Override
    @Transactional
    public AlertView acknowledge(UUID alertId, UUID acknowledgedBy, Instant at) {
        EnvironmentAlert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new NotFoundException("Alerta no encontrada: " + alertId));
        if (!alert.isActive()) {
            throw new ConflictException("La alerta ya fue reconocida");
        }
        alert.acknowledge(acknowledgedBy, at != null ? at : clock.instant());
        return toView(alertRepository.save(alert));
    }

    @Override
    @Transactional
    public void delete(UUID alertId, Instant at) {
        EnvironmentAlert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new NotFoundException("Alerta no encontrada: " + alertId));
        if (alert.isDeleted()) {
            return; // idempotente: dos DELETE seguidos no fallan
        }
        alert.softDelete(at != null ? at : clock.instant());
        alertRepository.save(alert);
    }

    private AlertView toView(EnvironmentAlert alert) {
        String variableCode = variableCatalogPort.findById(alert.getVariableId())
                .map(VariableCatalogPort.VariableRef::code)
                .orElse(alert.getVariableId().toString());
        // La severidad real se guardo al generarse la alerta (WARNING/CRITICAL);
        // antes se rellenaba con el estado (ACTIVE/ACKNOWLEDGED), que no es severidad.
        java.math.BigDecimal triggeringValue =
                measurementRepository.findById(alert.getTriggeringMeasurementId())
                        .map(m -> m.getMeasuredValue())
                        .orElse(null);
        return new AlertView(
                alert.getId(),
                alert.getEducationalEnvironmentId(),
                alert.getVariableId(),
                variableCode,
                alert.getSeverityCode(),
                triggeringValue,
                alert.getRaisedAt(),
                alert.getAcknowledgedAt(),
                alert.getAcknowledgedBy());
    }
}
