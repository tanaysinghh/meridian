package com.meridian.api.config;

import com.meridian.api.auth.AuthCookies;
import com.meridian.api.auth.JwtService;
import com.meridian.api.realtime.StompAuthChannelInterceptor;
import com.meridian.api.realtime.StompPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import java.util.Map;

/**
 * STOMP-over-WebSocket, replacing the Socket.IO {@code /live} namespace.
 *
 * <p>The endpoint path stays {@code /live} so the Vite dev proxy and the Render routing carry over
 * untouched. What changed is the protocol and, with it, the shape of a subscription: Socket.IO had
 * rooms, STOMP has destinations, so {@code org:{orgId}} becomes the destination
 * {@code /topic/org.{orgId}}.
 *
 * <p>Authentication happens twice, in two places, for two reasons:
 * <ul>
 *   <li>The <b>handshake interceptor</b> below reads the {@code mrd_at} cookie during the HTTP
 *       upgrade. This is the path a browser takes, since it cannot set headers on a WebSocket
 *       handshake.</li>
 *   <li>{@link StompAuthChannelInterceptor} reads a token from the CONNECT frame for clients that
 *       can send one, and — more importantly — enforces on every SUBSCRIBE that the destination
 *       belongs to the subscriber's own org. Without that second check, an authenticated user could
 *       subscribe to another org's topic simply by naming it.</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    /** Request attribute carrying the principal resolved during the HTTP handshake. */
    public static final String PRINCIPAL_ATTRIBUTE = "meridian.principal";

    private final AppProperties props;
    private final JwtService jwtService;
    private final StompAuthChannelInterceptor authInterceptor;

    public WebSocketConfig(AppProperties props,
                           JwtService jwtService,
                           StompAuthChannelInterceptor authInterceptor) {
        this.props = props;
        this.jwtService = jwtService;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // In-memory broker. The dashboard is a pure consumer of server-pushed events, so there is
        // nothing to route to an external broker and no client-to-client messaging to support.
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/live")
                .setAllowedOrigins(props.resolvedAllowedOrigins().toArray(String[]::new))
                .addInterceptors(new CookieHandshakeInterceptor())
                // SockJS is not enabled: every browser this app supports has native WebSocket, and
                // the fallback transports would need their own CSRF story.
                ;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    /**
     * Pulls the access token out of the handshake request's cookies and resolves it to a principal.
     *
     * <p>A browser opening a WebSocket cannot attach an Authorization header, but it does send
     * cookies — including the HttpOnly {@code mrd_at} — so this is the browser's authentication
     * path. A handshake with no usable token is still allowed to complete; the channel interceptor
     * then refuses the CONNECT, which produces a clean STOMP error rather than an opaque socket
     * failure.
     */
    private class CookieHandshakeInterceptor extends HttpSessionHandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request,
                                       ServerHttpResponse response,
                                       WebSocketHandler wsHandler,
                                       Map<String, Object> attributes) {

            if (request instanceof ServletServerHttpRequest servletRequest) {
                HttpServletRequest raw = servletRequest.getServletRequest();
                String token = readCookie(raw, AuthCookies.ACCESS);
                if (token != null && !token.isBlank()) {
                    try {
                        JwtService.AccessClaims claims = jwtService.verifyAccess(token);
                        attributes.put(PRINCIPAL_ATTRIBUTE, new StompPrincipal(claims.userId(), claims.orgId()));
                    } catch (Exception ex) {
                        log.debug("ws_handshake_token_rejected reason={}", ex.getClass().getSimpleName());
                    }
                }
            }
            return true;
        }

        private String readCookie(HttpServletRequest request, String name) {
            if (request.getCookies() == null) {
                return null;
            }
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if (name.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
            return null;
        }
    }
}
