package com.eduaircontrol.msmonitoring.monitoring.application;

import com.eduaircontrol.msmonitoring.monitoring.domain.model.EnvironmentMeasurement;
import com.eduaircontrol.msmonitoring.monitoring.domain.port.out.MeasurementRepository;
import com.eduaircontrol.msmonitoring.shared.contract.VariableCatalogPort;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exportacion de mediciones a CSV y Excel (ADR-020, plan D6).
 */
@Service
@RequiredArgsConstructor
public class MeasurementExportService {

    private final MeasurementRepository measurementRepository;
    private final VariableCatalogPort variableCatalogPort;

    private static final int MAX_ROWS = 100_000;

    @Transactional(readOnly = true)
    public byte[] exportCsv(UUID environmentId, UUID variableId, Instant from, Instant to) {
        List<EnvironmentMeasurement> measurements = fetchMeasurements(environmentId, variableId, from, to);
        StringBuilder sb = new StringBuilder();
        sb.append("measurement_id,environment_id,variable_code,value,measured_at,quality_flag_id\n");
        for (EnvironmentMeasurement m : measurements) {
            String code = variableCodeOf(m.getVariableId());
            sb.append(m.getId()).append(',')
                    .append(environmentId).append(',')
                    .append(code).append(',')
                    .append(m.getMeasuredValue()).append(',')
                    .append(m.getMeasuredAt()).append(',')
                    .append(m.getQualityFlagId()).append('\n');
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public byte[] exportExcel(UUID environmentId, UUID variableId, Instant from, Instant to) {
        List<EnvironmentMeasurement> measurements = fetchMeasurements(environmentId, variableId, from, to);
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Measurements");
            Row header = sheet.createRow(0);
            String[] headers = {"Measurement ID", "Environment ID", "Variable Code", "Value", "Measured At", "Quality Flag"};
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            int rowIdx = 1;
            for (EnvironmentMeasurement m : measurements) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(m.getId().toString());
                row.createCell(1).setCellValue(environmentId.toString());
                row.createCell(2).setCellValue(variableCodeOf(m.getVariableId()));
                row.createCell(3).setCellValue(m.getMeasuredValue().doubleValue());
                row.createCell(4).setCellValue(m.getMeasuredAt().toString());
                row.createCell(5).setCellValue(m.getQualityFlagId().toString());
            }
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Error generating Excel file", e);
        }
    }

    private List<EnvironmentMeasurement> fetchMeasurements(UUID environmentId, UUID variableId,
                                                          Instant from, Instant to) {
        Instant f = from != null ? from : Instant.EPOCH;
        Instant t = to != null ? to : Instant.now();
        // Use history with large limit (paginated internally, but we fetch up to MAX_ROWS)
        return measurementRepository.history(environmentId, variableId, f, t, 1, MAX_ROWS)
                .content();
    }

    private String variableCodeOf(UUID variableId) {
        return variableCatalogPort.findById(variableId)
                .map(VariableCatalogPort.VariableRef::code)
                .orElse(variableId.toString());
    }
}
