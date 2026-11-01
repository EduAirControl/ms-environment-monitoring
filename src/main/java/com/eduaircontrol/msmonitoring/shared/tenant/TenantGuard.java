package com.eduaircontrol.msmonitoring.shared.tenant;

import com.eduaircontrol.msmonitoring.shared.contract.EnvironmentLookupPort;
import com.eduaircontrol.msmonitoring.shared.exception.NotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Comprueba que un ambiente pedido por id pertenece a la institucion del token (ADR-016).
 *
 * <p>Los listados ya filtran por institucion en los adaptadores; este guard cubre
 * los accesos por identificador, donde adivinar un UUID no debe dar acceso. Un
 * ambiente de otra institucion responde igual que uno inexistente (404) para no
 * revelar que existe. En contextos de sistema (sin tenant) no filtra.
 */
@Component
@RequiredArgsConstructor
public class TenantGuard {

    private final EnvironmentLookupPort environmentLookup;

    /** @throws NotFoundException si el ambiente no existe o es de otra institucion */
    public void requireEnvironment(UUID environmentId) {
        UUID institutionId = TenantContext.institutionId();
        if (institutionId == null || environmentId == null) {
            return;
        }
        boolean owned = environmentLookup.findById(environmentId)
                .map(env -> institutionId.equals(env.institutionId()))
                .orElse(false);
        if (!owned) {
            throw new NotFoundException("Ambiente no encontrado: " + environmentId);
        }
    }
}
