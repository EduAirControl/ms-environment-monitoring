package com.eduaircontrol.msmonitoring.monitoring.infrastructure.web;

import com.eduaircontrol.msmonitoring.monitoring.application.MeasurementExportService;
import com.eduaircontrol.msmonitoring.monitoring.application.MeasurementService;
import com.eduaircontrol.msmonitoring.monitoring.domain.port.in.MeasurementUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.eduaircontrol.msmonitoring.shared.tenant.TenantGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de mediciones (HU-MON-001 … HU-MON-004).
 *
 * <p>La ingesta principal llega por RabbitMQ desde ms-sensor-management. Este
 * endpoint HTTP es la alternativa para sensores con capacidad HTTPS (ADR-004).
 *
 * <p>Todo el controller exige JWT real (R-001): la via de headers del gateway
 * no se acepta como identidad. El gateway reenvia el {@code Authorization}
 * del cliente (o el de la sesion BFF), asi que las peticiones legitimas
 * llegan igual.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MeasurementController {

    private static final String DEVICE_ROLE = "DEVICE";

    private final MeasurementUseCase measurementUseCase;
    private final TenantGuard tenantGuard;
    private final com.eduaircontrol.msmonitoring.shared.contract.UserIdentityPort userIdentityPort;
    private final MeasurementExportService exportService;

    /**
     * Ingesta HTTP alternativa a MQTT (HU-MON-001). Idempotente por reintento.
     *
     * <p>Exige un <b>token real</b>: el rol {@code DEVICE} es el de un dispositivo
     * provisionado, {@code ADMIN} el de un operador y {@code SUPER_ADMIN} el del gestor
     * global, que cuenta como administrador.
     */
    @PreAuthorize("hasAnyRole('DEVICE','ADMIN','SUPER_ADMIN')")
    @PostMapping("/measurements")
    @ResponseStatus(HttpStatus.CREATED)
    public MeasurementIdResponse ingest(@Valid @RequestBody IngestRequest request) {
        UUID id = measurementUseCase.record(new MeasurementUseCase.RecordCommand(
                request.sensorInstallationId(),
                request.variableId(),
                request.value(),
                request.measuredAt()),
                callerSensorId());
        return new MeasurementIdResponse(id);
    }

    /**
     * Sensor del que viene la medicion, o {@code null} si quien llama es una
     * persona.
     *
     * <p>El {@code sub} del token de dispositivo ES el sensorId, y solo entonces se
     * exige pertenencia: un operador ({@code ADMIN}/{@code SUPER_ADMIN}) puede
     * cargar datos en cualquier instalacion.
     */
    private UUID callerSensorId() {
        if (!userIdentityPort.currentRoles().contains(DEVICE_ROLE)) {
            return null;
        }
        return userIdentityPort.currentUserId()
                .orElseThrow(() -> new com.eduaircontrol.msmonitoring.shared.exception.ForbiddenException(
                        "Token de dispositivo sin identidad"));
    }

    /**
     * Ingesta por lotes (buffer offline / reenvio). Idempotente por item.
     *
     * <p>Procesa hasta 1000 mediciones en una sola peticion. Cada item se valida
     * y persiste de forma independiente: un item invalido o duplicado no aborta
     * el lote. La respuesta incluye el resultado por item.
     */
    @PreAuthorize("hasAnyRole('DEVICE','ADMIN','SUPER_ADMIN')")
    @PostMapping("/measurements/batch")
    public MeasurementUseCase.BatchResult ingestBatch(
            @Valid @RequestBody IngestBatchRequest request) {
        UUID callerSensorId = callerSensorId();
        List<MeasurementUseCase.RecordCommand> commands = request.measurements().stream()
                .map(m -> new MeasurementUseCase.RecordCommand(
                        m.sensorInstallationId(),
                        m.variableId(),
                        m.value(),
                        m.measuredAt()))
                .toList();
        return measurementUseCase.recordBatch(commands, callerSensorId);
    }

    /**
     * Ultimos valores por variable de TODOS los ambientes (panel web).
     *
     * <p>Una sola consulta: pintar el ranking de ambientes sin este endpoint
     * obligaria a una peticion por ambiente.
     */
    @GetMapping("/environments/current")
    public List<MeasurementUseCase.EnvironmentCurrentValue> currentAll() {
        return measurementUseCase.currentValuesForAll();
    }

    /** Ultimos valores por variable de un ambiente (HU-MON-003). */
    @GetMapping("/environments/{id}/current")
    public List<MeasurementUseCase.CurrentValue> current(@PathVariable UUID id) {
        tenantGuard.requireEnvironment(id);
        return measurementUseCase.currentValues(id);
    }

    /** Historial paginado por variable y ventana (HU-MON-004). */
    @GetMapping("/environments/{id}/measurements")
    public List<MeasurementUseCase.MeasurementView> history(
            @PathVariable UUID id,
            @RequestParam(name = "variable", required = false) UUID variableId,
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        tenantGuard.requireEnvironment(id);
        return measurementUseCase.history(new MeasurementUseCase.HistoryQuery(
                id, variableId,
                from != null ? from : Instant.EPOCH,
                to != null ? to : Instant.now(),
                page, limit));
    }

    /**
     * Exporta mediciones a CSV o Excel.
     *
     * <p>Filtros: environmentId (requerido), variableId (opcional), from/to
     * (date-time, opcionales). Formato: csv (default) o xlsx.
     */
    @PreAuthorize("hasAnyRole('DEVICE','ADMIN','SUPER_ADMIN')")
    @GetMapping("/measurements/export")
    public org.springframework.http.ResponseEntity<byte[]> exportMeasurements(
            @RequestParam java.util.UUID environmentId,
            @RequestParam(required = false) java.util.UUID variableId,
            @RequestParam(required = false) java.time.Instant from,
            @RequestParam(required = false) java.time.Instant to,
            @RequestParam(defaultValue = "csv") String format) {
        tenantGuard.requireEnvironment(environmentId);

        if (!format.equals("csv") && !format.equals("xlsx")) {
            throw new com.eduaircontrol.msmonitoring.shared.exception.ValidationException(
                    "format debe ser 'csv' o 'xlsx'");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new com.eduaircontrol.msmonitoring.shared.exception.ValidationException(
                    "from debe ser anterior a to");
        }

        byte[] data;
        String contentType;
        String ext;
        if ("xlsx".equals(format)) {
            data = exportService.exportExcel(environmentId, variableId, from, to);
            contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            ext = "xlsx";
        } else {
            data = exportService.exportCsv(environmentId, variableId, from, to);
            contentType = "text/csv; charset=UTF-8";
            ext = "csv";
        }

        String filename = "measurements_" + environmentId + "_" +
                (from != null ? from.toString().substring(0, 10) : "all") + "_" +
                (to != null ? to.toString().substring(0, 10) : "now") + "." + ext;

        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(org.springframework.http.MediaType.parseMediaType(contentType))
                .body(data);
    }

    public record IngestBatchRequest(
            @NotNull @jakarta.validation.constraints.Size(min = 1, max = 1000)
            @Valid List<IngestRequest> measurements) {
    }

    public record IngestRequest(
            @NotNull UUID sensorInstallationId,
            @NotNull UUID variableId,
            @NotNull BigDecimal value,
            Instant measuredAt) {
    }

    public record MeasurementIdResponse(UUID id) {
    }
}
