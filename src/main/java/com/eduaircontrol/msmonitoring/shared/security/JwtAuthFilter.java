package com.eduaircontrol.msmonitoring.shared.security;

import com.eduaircontrol.msmonitoring.shared.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autenticacion del servicio: unicamente JWT RS256 validado contra el JWKS de
 * ms-security. Los headers internos del gateway (X-User-*) no se aceptan
 * como identidad (R-001): el gateway reenvia siempre el Authorization
 * resuelto (incluso en el flujo BFF por cookie), asi que las peticiones
 * legitimas llegan con token y quien alcanze el puerto del servicio sin el
 * no obtiene ninguna autoridad (SecurityConfig responde 401).
 *
 * <p>Ademas publica el tenant del token (institutionId/campusId) en
 * {@link TenantContext} para que los adaptadores de persistencia filtren por
 * institucion (ADR-016). El contexto se limpia al terminar la peticion.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;

    /** Principales que operan sin institucion: dispositivos y super administradores. */
    private static boolean isSystemPrincipal(JwtService.AuthenticatedUser user) {
        return user.roles().contains("SUPER_ADMIN") || user.roles().contains("DEVICE");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            final String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith(BEARER)) {
                filterChain.doFilter(request, response);
                return;
            }
            String token = authHeader.substring(BEARER.length());
            try {
                JwtService.AuthenticatedUser user = jwtService.authenticate(token);
                if (user.institutionId() == null && !isSystemPrincipal(user)) {
                    // Sin institucion el filtrado por tenant no se aplicaria y el
                    // usuario veria todas las instituciones: se rechaza.
                    log.warn("Token de usuario sin institutionId rechazado (roles={})", user.roles());
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType("application/json");
                    response.getWriter().write("{\"status\":403,\"message\":\"Token sin institucion\"}");
                    return;
                }
                // El token se guarda como credencial: UserIdentityAdapter lo relee
                // para resolver el userId desde los claims.
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(
                                user.email() != null ? user.email() : String.valueOf(user.userId()),
                                token,
                                user.roles().stream()
                                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                                        .toList());
                SecurityContextHolder.getContext().setAuthentication(auth);
                UUID institutionId = user.institutionId();
                if (institutionId == null && user.roles().contains("SUPER_ADMIN")) {
                    // El SUPER_ADMIN ve todo por defecto; con ?institutionId= acota la
                    // consulta a una institucion (y evita cargar el catalogo completo).
                    String requested = request.getParameter("institutionId");
                    if (requested != null && !requested.isBlank()) {
                        try {
                            institutionId = UUID.fromString(requested);
                        } catch (IllegalArgumentException ex) {
                            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                            response.setCharacterEncoding("UTF-8");
                            response.setContentType("application/json");
                            response.getWriter().write(
                                    "{\"status\":400,\"message\":\"institutionId invalido\"}");
                            return;
                        }
                    }
                }
                TenantContext.set(institutionId, user.campusId());
            } catch (Exception e) {
                log.debug("Token invalido: {}", e.getMessage());
                // Sin autenticacion: SecurityConfig responde 401.
            }

            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
