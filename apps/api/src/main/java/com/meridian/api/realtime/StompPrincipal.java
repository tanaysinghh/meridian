package com.meridian.api.realtime;

import java.security.Principal;
import java.util.UUID;

/**
 * The identity behind a WebSocket session.
 *
 * <p>Carries the org id as well as the user id, because authorization on this channel is entirely
 * about which org's topic a session may subscribe to.
 */
public record StompPrincipal(UUID userId, UUID orgId) implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }
}
