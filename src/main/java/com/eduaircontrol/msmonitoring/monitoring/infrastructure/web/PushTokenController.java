package com.eduaircontrol.msmonitoring.monitoring.infrastructure.web;

import com.eduaircontrol.msmonitoring.monitoring.infrastructure.persistence.PushTokenDao;
import com.eduaircontrol.msmonitoring.shared.contract.UserIdentityPort;
import com.eduaircontrol.msmonitoring.shared.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registro de tokens push (Expo) de la app movil.
 *
 * <p>La institucion sale del claim del token (ADR-016); el usuario del propio JWT.
 * Las alertas se notifican a los dispositivos de la institucion del ambiente.
 */
@RestController
@RequestMapping("/api/v1/push-tokens")
@RequiredArgsConstructor
public class PushTokenController {

    private final PushTokenDao pushTokenDao;
    private final UserIdentityPort userIdentityPort;

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(@Valid @RequestBody PushTokenRequest request) {
        pushTokenDao.upsert(
                request.token(),
                request.platform(),
                TenantContext.institutionId(),
                userIdentityPort.currentUserId().orElse(null));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(@RequestParam("token") String token) {
        pushTokenDao.delete(token);
    }

    public record PushTokenRequest(
            @NotBlank @Size(max = 255) String token,
            @Size(max = 20) String platform) {
    }
}
