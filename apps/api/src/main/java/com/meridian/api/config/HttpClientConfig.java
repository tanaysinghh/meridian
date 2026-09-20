package com.meridian.api.config;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

/**
 * The HTTP client used for every outbound call — the ML service, GitHub, Slack and Resend.
 *
 * <p>Declared as a <b>prototype</b> bean because {@link RestClient.Builder} is mutable and each
 * consumer configures it differently: {@code MlClient} sets a base url and a short timeout,
 * {@code SlackNotifier} and {@code EmailSender} set their own. A singleton builder would let one
 * service's base url or timeout leak into another's client.
 *
 * <p>Timeouts are set per-consumer rather than here, since the right value differs: five seconds for
 * scoring (it is in the webhook path and has a fallback), longer for notification delivery.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
