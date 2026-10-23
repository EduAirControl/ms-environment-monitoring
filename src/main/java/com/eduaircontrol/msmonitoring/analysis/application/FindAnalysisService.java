package com.eduaircontrol.msmonitoring.analysis.application;

import com.eduaircontrol.msmonitoring.analysis.domain.model.AnalysisPeriod;
import com.eduaircontrol.msmonitoring.analysis.domain.model.EnvironmentalAnalysis;
import com.eduaircontrol.msmonitoring.analysis.domain.model.PageResult;
import com.eduaircontrol.msmonitoring.analysis.domain.port.in.FindAnalysisUseCase;
import com.eduaircontrol.msmonitoring.analysis.domain.port.out.AnalysisRepository;
import com.eduaircontrol.msmonitoring.analysis.domain.service.PeriodResolver;
import com.eduaircontrol.msmonitoring.shared.exception.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FindAnalysisService implements FindAnalysisUseCase {

    private final AnalysisRepository analysisRepository;
    private final PeriodResolver periodResolver;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Optional<EnvironmentalAnalysis> byId(UUID id) {
        return analysisRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<EnvironmentalAnalysis> search(Query query) {
        return analysisRepository.search(query.environmentId(), query.status(), query.page(), query.limit());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EnvironmentalAnalysis> latestCompleted(UUID environmentId,
                                                           AnalysisPeriod period,
                                                           Instant referenceDate) {
        Instant reference = referenceDate != null ? referenceDate : clock.instant();
        AnalysisPeriod.Window window = periodResolver.resolve(period, reference);
        return analysisRepository.findLatestCompleted(environmentId, window);
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return analysisRepository.count();
    }

    public EnvironmentalAnalysis require(UUID id) {
        return byId(id).orElseThrow(() -> new NotFoundException("Analisis no encontrado: " + id));
    }
}
