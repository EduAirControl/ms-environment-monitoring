package com.eduaircontrol.msmonitoring.monitoring.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tokens push (Expo) de la app movil, por institucion.
 *
 * <p>El token es unico: re-registrarlo actualiza plataforma/institucion/usuario
 * (ON CONFLICT) en lugar de duplicar filas.
 */
@Repository
@RequiredArgsConstructor
public class PushTokenDao {

    private final JdbcTemplate jdbc;

    @Transactional
    public void upsert(String token, String platform, UUID institutionId, UUID userId) {
        jdbc.update("""
                INSERT INTO environment_monitoring.push_token
                    (token, platform, institution_id, user_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (token) DO UPDATE SET
                    platform = EXCLUDED.platform,
                    institution_id = EXCLUDED.institution_id,
                    user_id = EXCLUDED.user_id
                """, token, platform, institutionId, userId);
    }

    @Transactional
    public void delete(String token) {
        jdbc.update("DELETE FROM environment_monitoring.push_token WHERE token = ?", token);
    }

    @Transactional(readOnly = true)
    public List<String> tokensByInstitution(UUID institutionId) {
        if (institutionId == null) {
            return List.of();
        }
        return jdbc.queryForList(
                "SELECT token FROM environment_monitoring.push_token WHERE institution_id = ?",
                String.class, institutionId);
    }
}
