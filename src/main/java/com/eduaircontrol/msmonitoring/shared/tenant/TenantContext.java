package com.eduaircontrol.msmonitoring.shared.tenant;

import java.util.UUID;

/**
 * Contexto de tenant de la petición (institución/sede) — ADR-016.
 *
 * <p>Lo puebla {@code JwtAuthFilter} al validar el token (claims
 * institutionId/campusId) y lo leen los adaptadores de persistencia para filtrar
 * por institución.
 *
 * <p>Cuando no hay petición (jobs, consumidores del broker) o el token no trae
 * institución (dispositivos, SUPER_ADMIN), el contexto queda vacío y el filtrado
 * no se aplica: esos son contextos de sistema.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> INSTITUTION = new ThreadLocal<>();
    private static final ThreadLocal<UUID> CAMPUS = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID institutionId, UUID campusId) {
        INSTITUTION.set(institutionId);
        CAMPUS.set(campusId);
    }

    public static UUID institutionId() {
        return INSTITUTION.get();
    }

    public static UUID campusId() {
        return CAMPUS.get();
    }

    public static void clear() {
        INSTITUTION.remove();
        CAMPUS.remove();
    }
}
