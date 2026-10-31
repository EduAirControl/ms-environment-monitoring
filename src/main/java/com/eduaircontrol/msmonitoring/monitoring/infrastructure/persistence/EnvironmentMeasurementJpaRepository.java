package com.eduaircontrol.msmonitoring.monitoring.infrastructure.persistence;

import com.eduaircontrol.msmonitoring.monitoring.domain.model.EnvironmentMeasurement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EnvironmentMeasurementJpaRepository
        extends JpaRepository<EnvironmentMeasurement, UUID> {

    boolean existsBySensorInstallationIdAndVariableIdAndMeasuredAt(
            UUID sensorInstallationId, UUID variableId, Instant measuredAt);

    @Query(value = """
            select m.* from environment_monitoring.environment_measurement m
            join environment_monitoring.installation_projection p
                on p.sensor_installation_id = m.sensor_installation_id
            join environment_monitoring.educational_environment e
                on e.educational_environment_id = p.educational_environment_id
            where p.educational_environment_id = :environmentId
              and p.removed_at is null
              and (cast(:institutionId as uuid) is null
                   or e.institution_id = cast(:institutionId as uuid))
              and (m.variable_id, m.measured_at) in (
                  select m2.variable_id, max(m2.measured_at)
                  from environment_monitoring.environment_measurement m2
                  join environment_monitoring.installation_projection p2
                      on p2.sensor_installation_id = m2.sensor_installation_id
                  join environment_monitoring.educational_environment e2
                      on e2.educational_environment_id = p2.educational_environment_id
                  where p2.educational_environment_id = :environmentId
                    and p2.removed_at is null
                    and (cast(:institutionId as uuid) is null
                         or e2.institution_id = cast(:institutionId as uuid))
                  group by m2.variable_id
              )
            """, nativeQuery = true)
    List<EnvironmentMeasurement> latestByEnvironment(
            @Param("environmentId") UUID environmentId,
            @Param("institutionId") UUID institutionId);

    @Query(value = """
            select p.educational_environment_id as educational_environment_id,
                   e.environment_type_id as environment_type_id,
                   m.variable_id as variable_id,
                   m.measured_value as measured_value,
                   m.measured_at as measured_at
            from environment_monitoring.environment_measurement m
            join environment_monitoring.installation_projection p
                on p.sensor_installation_id = m.sensor_installation_id
            join environment_monitoring.educational_environment e
                on e.educational_environment_id = p.educational_environment_id
            where p.removed_at is null
              and (cast(:institutionId as uuid) is null
                   or e.institution_id = cast(:institutionId as uuid))
              and (p.educational_environment_id, m.variable_id, m.measured_at) in (
                  select p2.educational_environment_id, m2.variable_id, max(m2.measured_at)
                  from environment_monitoring.environment_measurement m2
                  join environment_monitoring.installation_projection p2
                      on p2.sensor_installation_id = m2.sensor_installation_id
                  join environment_monitoring.educational_environment e2
                      on e2.educational_environment_id = p2.educational_environment_id
                  where p2.removed_at is null
                    and (cast(:institutionId as uuid) is null
                         or e2.institution_id = cast(:institutionId as uuid))
                  group by p2.educational_environment_id, m2.variable_id
              )
            order by p.educational_environment_id
            """, nativeQuery = true)
    List<EnvironmentCurrentProjection> latestAll(@Param("institutionId") UUID institutionId);

    @Query(value = """
            select m.* from environment_monitoring.environment_measurement m
            join environment_monitoring.installation_projection p
                on p.sensor_installation_id = m.sensor_installation_id
            join environment_monitoring.educational_environment e
                on e.educational_environment_id = p.educational_environment_id
            where p.educational_environment_id = :environmentId
              and p.removed_at is null
              and (cast(:institutionId as uuid) is null
                   or e.institution_id = cast(:institutionId as uuid))
              and (:variableId is null or m.variable_id = :variableId)
              and m.measured_at >= :from
              and m.measured_at < :to
            """,
            countQuery = """
                    select count(*) from environment_monitoring.environment_measurement m
                    join environment_monitoring.installation_projection p
                        on p.sensor_installation_id = m.sensor_installation_id
                    join environment_monitoring.educational_environment e
                        on e.educational_environment_id = p.educational_environment_id
                    where p.educational_environment_id = :environmentId
                      and p.removed_at is null
                      and (cast(:institutionId as uuid) is null
                           or e.institution_id = cast(:institutionId as uuid))
                      and (:variableId is null or m.variable_id = :variableId)
                      and m.measured_at >= :from
                      and m.measured_at < :to
                    """,
            nativeQuery = true)
    Page<EnvironmentMeasurement> history(@Param("environmentId") UUID environmentId,
                                         @Param("institutionId") UUID institutionId,
                                         @Param("variableId") UUID variableId,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to,
                                         Pageable pageable);

    /**
     * Mediciones de instalaciones activas de la institucion (null = todas).
     */
    @Query(value = """
            select count(*) from environment_monitoring.environment_measurement m
            join environment_monitoring.installation_projection p
                on p.sensor_installation_id = m.sensor_installation_id
            join environment_monitoring.educational_environment e
                on e.educational_environment_id = p.educational_environment_id
            where p.removed_at is null
              and (cast(:institutionId as uuid) is null
                   or e.institution_id = cast(:institutionId as uuid))
            """, nativeQuery = true)
    long countByInstitution(@Param("institutionId") UUID institutionId);
}
