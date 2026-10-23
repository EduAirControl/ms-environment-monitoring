package com.eduaircontrol.msmonitoring.analysis.domain.model;

import java.util.List;

/**
 * Paginacion propia del dominio. Evita que los puertos dependan de Spring Data.
 */
public record PageResult<T>(List<T> content, long totalElements, int page, int limit) {

    public int totalPages() {
        return limit <= 0 ? 0 : (int) Math.ceil((double) totalElements / limit);
    }
}
