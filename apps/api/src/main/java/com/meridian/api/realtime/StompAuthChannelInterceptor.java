package com.meridian.api.realtime;

import com.meridian.api.auth.JwtService;
import com.meridian.api.config.WebSocketConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Authenticates STOMP CONNECT frames and authorizes SUBSCRIBE destinations.
 *
 * <p>This is the security boundary for the realtime channel, and it does two distinct jobs.
 *
 * <p><b>CONNECT</b> establishes who the session belongs to. It prefers the principal the HTTP
 * handshake already resolved from the {@code mrd_at} cookie; failing that it accepts a token in the
 * frame's {@code Authorization} or {@code token} header, which is how a non-browser client
 * authenticates. A frame that yields no principal is rejected, so an unauthenticated socket can
 * never reach the broker.
 *
 * <p><b>SUBSCRIBE</b> is where a bug would actually leak data. Destinations are namespaced per org
 * ({@code /topic/org.{orgId}}), and a client chooses its own destination string — so without a check
 * here, any authenticated user could subscribe to any org's feed by guessing a UUID. Every
 * subscription is therefore matched against the session's own org id, and anything else is refused.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);

    /** Destination prefix for an org's event feed. */
    public static final String ORG_TOPIC_PREFIX = "/topic/org.";

    private final JwtService jwtService;

    public StompAuthChannelInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        return switch (accessor.getCommand()) {
            case CONNECT -> handleConnect(accessor, message);
            case SUBSCRIBE -> handleSubscribe(accessor, message);
            default -> message;
        };
    }

    private Message<?> handleConnect(StompHeaderAccessor accessor, Message<?> message) {
        StompPrincipal principal = principalFromHandshake(accessor);

        if (principal == null) {
            principal = principalFromFrame(accessor);
        }

        if (principal == null) {
            log.debug("stomp_connect_rejected reason=no_valid_token");
            throw new org.springframework.messaging.MessagingException("unauthorized");
        }

        accessor.setUser(principal);
        return message;
    }

    private Message<?> handleSubscribe(StompHeaderAccessor accessor, Message<?> message) {
        if (!(accessor.getUser() instanceof StompPrincipal principal)) {
            throw new org.springframework.messaging.MessagingException("unauthorized");
        }

        String destination = accessor.getDestination();
        if (destination == null) {
            throw new org.springframework.messaging.MessagingException("invalid_destination");
        }

        // Only this session's own org topic is subscribable. Nothing else on /topic is.
        String allowed = ORG_TOPIC_PREFIX + principal.orgId();
        if (!destination.equals(allowed)) {
            log.warn("stomp_subscribe_denied user={} destination={}", principal.userId(), destination);
            throw new org.springframework.messaging.MessagingException("forbidden_destination");
        }

        return message;
    }

    private static StompPrincipal principalFromHandshake(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null) {
            return null;
        }
        Object value = attributes.get(WebSocketConfig.PRINCIPAL_ATTRIBUTE);
        return value instanceof StompPrincipal p ? p : null;
    }

    /** Token supplied in the CONNECT frame itself, for clients that are not a browser. */
    private StompPrincipal principalFromFrame(StompHeaderAccessor accessor) {
        String token = firstHeader(accessor, "Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring("Bearer ".length()).trim();
        }
        if (token == null || token.isBlank()) {
            token = firstHeader(accessor, "token");
        }
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            JwtService.AccessClaims claims = jwtService.verifyAccess(token);
            return new StompPrincipal(claims.userId(), claims.orgId());
        } catch (Exception ex) {
            log.debug("stomp_connect_token_rejected reason={}", ex.getClass().getSimpleName());
            return null;
        }
    }

    private static String firstHeader(StompHeaderAccessor accessor, String name) {
        var values = accessor.getNativeHeader(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
