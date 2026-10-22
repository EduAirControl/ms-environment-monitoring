package com.eduaircontrol.msmonitoring.monitoring.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Alerta ambiental trazable (modelo canonico §4.1, DEC-006).
 *
 * <p>Referencia el ambiente, la variable, el umbral activo que se excedio y la
 * medicion que disparo la condicion. No es una copia de la medicion: es un hecho
 * trazable basado en ella.
 *
 * <p>Estado por columnas (acknowledged_at/by), no por catalogo. NULL en
 * {@code acknowledgedAt} significa alerta activa.
 */
@Entity
@Table(name = "environment_alert", schema = "environment_monitoring")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnvironmentAlert {

    @Id
    @Column(name = "environment_alert_id")
    private UUID id;

    @Column(name = "educational_environment_id", nullable = false)
    private UUID educationalEnvironmentId;

    @Column(name = "variable_id", nullable = false)
    private UUID variableId;

    @Column(name = "variable_threshold_id", nullable = false)
    private UUID variableThresholdId;

    @Column(name = "triggering_measurement_id", nullable = false)
    private UUID triggeringMeasurementId;

    /**
     * Severidad real de la excedencia al generarse la alerta (DEC-006):
     * {@code WARNING} fuera del rango normal, {@code CRITICAL} fuera del rango de
     * advertencia. NULL solo en alertas anteriores a esta columna.
     */
    @Column(name = "severity_code", length = 20)
    private String severityCode;

    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "acknowledged_by")
    private UUID acknowledgedBy;

    /**
     * Soft delete (DELETE /alerts/{id}): la alerta deja de listarse pero sigue
     * siendo traza del hecho que la genero (referencias intactas).
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    public boolean isActive() {
        return acknowledgedAt == null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void softDelete(Instant at) {
        this.deletedAt = at;
    }

    public void acknowledge(UUID userId, Instant at) {
        if (!isActive()) {
            throw new IllegalStateException("La alerta ya fue reconocida");
        }
        this.acknowledgedAt = at;
        this.acknowledgedBy = userId;
    }

    public static EnvironmentAlert raise(UUID environmentId, UUID variableId,
                                         UUID thresholdId, UUID measurementId,
                                         String severityCode, Instant at) {
        return EnvironmentAlert.builder()
                .id(UUID.randomUUID())
                .educationalEnvironmentId(environmentId)
                .variableId(variableId)
                .variableThresholdId(thresholdId)
                .triggeringMeasurementId(measurementId)
                .severityCode(severityCode)
                .raisedAt(at)
                .build();
    }
}
