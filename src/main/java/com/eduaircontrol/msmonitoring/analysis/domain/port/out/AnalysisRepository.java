package com.eduaircontrol.msmonitoring.analysis.domain.port.out;

import com.eduaircontrol.msmonitoring.analysis.domain.model.AnalysisPeriod;
import com.eduaircontrol.msmonitoring.analysis.domain.model.AnalysisStatusCode;
import com.eduaircontrol.msmonitoring.analysis.domain.model.EnvironmentalAnalysis;
import com.eduaircontrol.msmonitoring.analysis.domain.model.PageResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Salida de datos del modulo de analisis. El agregado se persiste completo con sus
 * resultados en cascada.
 */
public interface AnalysisRepository {

    EnvironmentalAnalysis save(EnvironmentalAnalysis analysis);

    Optional<EnvironmentalAnalysis> findById(UUID id);

    /** Ultimo analisis completado de un ambiente para una ventana concreta. */
    Optional<EnvironmentalAnalysis> findLatestCompleted(UUID environmentId,
                                                       AnalysisPeriod.Window window);

    boolean existsByEnvironmentAndWindow(UUID environmentId, AnalysisPeriod.Window window);

    PageResult<EnvironmentalAnalysis> search(UUID environmentId,
                                             AnalysisStatusCode status,
                                             int page, int limit);

    /** Analisis que quedaron en RUNNING y deben reintentarse o marcarse como FAILED. */
    List<EnvironmentalAnalysis> findByStatus(AnalysisStatusCode status);

    long count();
}
