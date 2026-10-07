package com.eduaircontrol.msmonitoring.analysis.domain.port.in;

import com.eduaircontrol.msmonitoring.analysis.domain.model.AnalysisPeriod;
import com.eduaircontrol.msmonitoring.analysis.domain.model.AnalysisStatusCode;
import com.eduaircontrol.msmonitoring.analysis.domain.model.EnvironmentalAnalysis;
import com.eduaircontrol.msmonitoring.analysis.domain.model.PageResult;
import java.util.Optional;
import java.util.UUID;

/**
 * Caso de uso de entrada: consulta de analisis historicos materializados.
 */
public interface FindAnalysisUseCase {

    Optional<EnvironmentalAnalysis> byId(UUID id);

    PageResult<EnvironmentalAnalysis> search(Query query);

    /**
     * Ultimo analisis completado de un ambiente para el periodo que contiene el
     * instante de referencia. Es la lectura que consume el dashboard.
     */
    Optional<EnvironmentalAnalysis> latestCompleted(UUID environmentId,
                                                    AnalysisPeriod period,
                                                    java.time.Instant referenceDate);

    /** Total de analisis registrados. */
    long count();

    record Query(UUID environmentId,
                 AnalysisStatusCode status,
                 int page,
                 int limit) {
    }
}
