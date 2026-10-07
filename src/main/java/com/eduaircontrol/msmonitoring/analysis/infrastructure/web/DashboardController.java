package com.eduaircontrol.msmonitoring.analysis.infrastructure.web;

import com.eduaircontrol.msmonitoring.analysis.domain.model.AnalysisPeriod;
import com.eduaircontrol.msmonitoring.analysis.domain.port.in.FindAnalysisUseCase;
import com.eduaircontrol.msmonitoring.monitoring.domain.port.in.MeasurementUseCase;
import com.eduaircontrol.msmonitoring.shared.contract.EnvironmentLookupPort;
import com.eduaircontrol.msmonitoring.shared.contract.VariableCatalogPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final EnvironmentLookupPort environmentLookupPort;
    private final MeasurementUseCase measurementUseCase;
    private final FindAnalysisUseCase findAnalysisUseCase;
    private final VariableCatalogPort variableCatalogPort;

    @GetMapping("/summary")
    public DashboardSummaryResponse summary() {
        int environments = environmentLookupPort.findAll().size();
        long measurements = measurementUseCase.count();
        long analyses = findAnalysisUseCase.count();

        Map<String, BigDecimal> averages = new LinkedHashMap<>();
        List<MeasurementUseCase.CurrentValue> currentValues = new ArrayList<>();
        environmentLookupPort.findAll().forEach(env -> {
            currentValues.addAll(findAnalysisUseCase.latestCompleted(
                    env.id(),
                    AnalysisPeriod.DAY,
                    Instant.now())
                    .stream()
                    .flatMap(a -> a.getResults().stream())
                    .map(r -> new MeasurementUseCase.CurrentValue(
                            r.getVariableId(),
                            variableCatalogPort.findAll().stream()
                                    .filter(v -> v.id().equals(r.getVariableId()))
                                    .findFirst()
                                    .map(VariableCatalogPort.VariableRef::code)
                                    .orElse("UNKNOWN"),
                            r.getAvgValue(),
                            r.getEnvironmentalAnalysis().getComputedAt()))
                    .toList());
        });

        Map<String, List<MeasurementUseCase.CurrentValue>> byVariable = new LinkedHashMap<>();
        currentValues.forEach(cv -> byVariable.computeIfAbsent(cv.variableCode(), k -> new ArrayList<>()).add(cv));
        byVariable.forEach((code, values) -> {
            BigDecimal avg = values.stream()
                    .map(MeasurementUseCase.CurrentValue::value)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
            averages.put(code, avg);
        });

        Instant lastUpdated = currentValues.stream()
                .map(MeasurementUseCase.CurrentValue::measuredAt)
                .max(Instant::compareTo)
                .orElse(Instant.now());

        return new DashboardSummaryResponse(
                new DashboardSummaryResponse.Counts(environments, (int) measurements, (int) analyses),
                averages,
                lastUpdated);
    }

    @GetMapping("/series")
    public List<DashboardSeriesResponse> series(
            @RequestParam(name = "period", defaultValue = "day") String period,
            @RequestParam(name = "variable", defaultValue = "temperature") String variable,
            @RequestParam(name = "environmentId", required = false) UUID environmentId) {

        ChronoUnit bucketUnit = switch (period.toLowerCase()) {
            case "day" -> ChronoUnit.DAYS;
            case "week" -> ChronoUnit.WEEKS;
            case "month" -> ChronoUnit.MONTHS;
            case "year" -> ChronoUnit.YEARS;
            default -> ChronoUnit.DAYS;
        };

        Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
        Instant to = Instant.now();

        Map<UUID, String> variableCodes = new LinkedHashMap<>();
        variableCatalogPort.findAll().forEach(v -> variableCodes.put(v.id(), v.code()));

        List<MeasurementUseCase.MeasurementView> measurements;
        if (environmentId != null) {
            measurements = measurementUseCase.history(new MeasurementUseCase.HistoryQuery(
                    environmentId,
                    variableCatalogPort.findByCode(variable).map(VariableCatalogPort.VariableRef::id).orElse(null),
                    from,
                    to,
                    1,
                    10000
            ));
        } else {
            measurements = new ArrayList<>();
            environmentLookupPort.findAll().forEach(env -> {
                variableCatalogPort.findAll().stream()
                        .filter(v -> v.code().equalsIgnoreCase(variable))
                        .findFirst()
                        .ifPresent(v -> measurements.addAll(measurementUseCase.history(
                                new MeasurementUseCase.HistoryQuery(
                                        env.id(),
                                        v.id(),
                                        from,
                                        to,
                                        1,
                                        10000
                                ))));
            });
        }

        Map<Instant, List<MeasurementUseCase.MeasurementView>> bucketed = new LinkedHashMap<>();
        measurements.forEach(m -> {
            Instant bucket = m.measuredAt().truncatedTo(bucketUnit);
            bucketed.computeIfAbsent(bucket, k -> new ArrayList<>()).add(m);
        });

        return bucketed.entrySet().stream()
                .map(e -> {
                    BigDecimal avg = e.getValue().stream()
                            .map(MeasurementUseCase.MeasurementView::value)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(e.getValue().size()), 2, RoundingMode.HALF_UP);
                    return new DashboardSeriesResponse(e.getKey(), avg, e.getValue().size());
                })
                .sorted((a, b) -> a.bucket().compareTo(b.bucket()))
                .toList();
    }

    public record DashboardSummaryResponse(
            Counts counts,
            Map<String, BigDecimal> averages,
            Instant lastUpdated) {

        public record Counts(int environments, int measurements, int analyses) {
        }
    }

    public record DashboardSeriesResponse(
            Instant bucket,
            BigDecimal value,
            int samples) {
    }
}
