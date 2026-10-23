package com.eduaircontrol.msmonitoring.monitoring.infrastructure.persistence;

import com.eduaircontrol.msmonitoring.monitoring.domain.model.EnvironmentAlert;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EnvironmentAlertJpaRepository extends JpaRepository<EnvironmentAlert, UUID> {

    @Query(value = """
            select a.* from environment_monitoring.environment_alert a
            join environment_monitoring.educational_environment e
                on e.educational_environment_id = a.educational_environment_id
            where (cast(:environmentId as uuid) is null
                   or a.educational_environment_id = cast(:environmentId as uuid))
              and (cast(:institutionId as uuid) is null
                   or e.institution_id = cast(:institutionId as uuid))
              and (cast(:active as boolean) is null
                   or (cast(:active as boolean) = true and a.acknowledged_at is null)
                   or (cast(:active as boolean) = false and a.acknowledged_at is not null))
              and (cast(:from as timestamptz) is null
                   or a.raised_at >= cast(:from as timestamptz))
              and (cast(:to as timestamptz) is null
                   or a.raised_at < cast(:to as timestamptz))
              and a.deleted_at is null
            order by a.raised_at desc
            """,
            countQuery = """
            select count(*) from environment_monitoring.environment_alert a
            join environment_monitoring.educational_environment e
                on e.educational_environment_id = a.educational_environment_id
            where (cast(:environmentId as uuid) is null
                   or a.educational_environment_id = cast(:environmentId as uuid))
              and (cast(:institutionId as uuid) is null
                   or e.institution_id = cast(:institutionId as uuid))
              and (cast(:active as boolean) is null
                   or (cast(:active as boolean) = true and a.acknowledged_at is null)
                   or (cast(:active as boolean) = false and a.acknowledged_at is not null))
              and (cast(:from as timestamptz) is null
                   or a.raised_at >= cast(:from as timestamptz))
              and (cast(:to as timestamptz) is null
                   or a.raised_at < cast(:to as timestamptz))
              and a.deleted_at is null
            """,
            nativeQuery = true)
    Page<EnvironmentAlert> search(@Param("environmentId") UUID environmentId,
                                  @Param("institutionId") UUID institutionId,
                                  @Param("active") Boolean active,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to,
                                  Pageable pageable);

    List<EnvironmentAlert> findByTriggeringMeasurementIdAndAcknowledgedAtIsNull(
            UUID triggeringMeasurementId);

    /** Alertas vigentes (no borradas) de un ambiente+variable, de cualquier estado. */
    @Query("""
            select a from EnvironmentAlert a
            where a.educationalEnvironmentId = :environmentId
              and a.variableId = :variableId
              and a.deletedAt is null
            order by a.raisedAt desc
            """)
    List<EnvironmentAlert> findOpenByEnvironmentAndVariable(
            @Param("environmentId") UUID environmentId,
            @Param("variableId") UUID variableId);
}
