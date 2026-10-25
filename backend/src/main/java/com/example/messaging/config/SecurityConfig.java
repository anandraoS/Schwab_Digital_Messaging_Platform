package com.example.messaging.config;

import com.example.messaging.common.ApiErrorWriter;
import com.example.messaging.common.CorrelationIdFilter;
import com.example.messaging.common.ErrorCode;
import com.example.messaging.coordination.CoordinationService;
import com.example.messaging.metrics.MessagingMetrics;
import com.example.messaging.security.IdentityProviderUnavailableFilter;
import com.example.messaging.security.JwtAuthConverter;
import com.example.messaging.security.PrincipalMdcFilter;
import com.example.messaging.security.RateLimitFilter;
import com.example.messaging.security.RequestSizeFilter;
import com.example.messaging.security.Roles;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

/**
 * Stateless JWT resource server. Callback endpoints are permitAll at this layer (stage 2 validates the HMAC signature
 * in its own filter); everything else under /api requires a valid bearer token. Roles are enforced with
 * {@code @PreAuthorize} on the controllers, tenant ownership with {@code AuthorizationService}.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, MessagingProperties props,
            ApiErrorWriter errors, CoordinationService coordination, MessagingMetrics metrics) throws Exception {

        AuthenticationEntryPoint entryPoint = (request, response, ex) ->
                errors.write(request, response, ErrorCode.UNAUTHENTICATED, "Authentication is required");
        AccessDeniedHandler denied = (request, response, ex) ->
                errors.write(request, response, ErrorCode.FORBIDDEN, "You do not have permission to perform this action");

        MessagingProperties.Security sec = props.security();
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> {
                    a.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll();
                    a.requestMatchers(HttpMethod.OPTIONS, "/**").denyAll();
                    a.requestMatchers("/api/v1/callbacks/**").permitAll();
                    a.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                    if (sec.metricsPublic()) {
                        a.requestMatchers("/actuator/prometheus").permitAll();
                    } else {
                        a.requestMatchers("/actuator/prometheus")
                                .hasAnyRole(Roles.PLATFORM_ADMIN, Roles.OPERATOR);
                    }
                    a.requestMatchers("/actuator/**").hasRole(Roles.PLATFORM_ADMIN);
                    a.requestMatchers("/dev/token").permitAll();
                    if (sec.docsPublic()) {
                        a.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui.html",
                                "/swagger-ui/**").permitAll();
                    }
                    a.requestMatchers("/api/**", "/dev/**", "/v3/api-docs/**", "/swagger-ui/**").authenticated();
                    a.anyRequest().denyAll();
                })
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(denied)
                        .jwt(j -> j.jwtAuthenticationConverter(new JwtAuthConverter())))
                .headers(h -> h
                        .contentTypeOptions(c -> { })
                        .frameOptions(f -> f.deny())
                        .cacheControl(c -> { }));

        http.addFilterBefore(new CorrelationIdFilter(), HeaderWriterFilter.class);
        http.addFilterAfter(new RequestSizeFilter(props.api().maxBodyBytes(), errors), CorrelationIdFilter.class);
        http.addFilterAfter(new IdentityProviderUnavailableFilter(errors), RequestSizeFilter.class);
        http.addFilterAfter(new PrincipalMdcFilter(), BearerTokenAuthenticationFilter.class);
        http.addFilterAfter(new RateLimitFilter(coordination, props, metrics, errors), PrincipalMdcFilter.class);
        return http.build();
    }
}
