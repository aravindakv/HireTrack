package com.aravindakv.hiretrack.common.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class HiretrackSecurityConfiguration {

    @Bean
    SecurityFilterChain hiretrackSecurityFilterChain(HttpSecurity http, HiretrackWebProperties props,
                                                     ObjectMapper json) throws Exception {
        String internalToken = props.internal().token();
        if (!StringUtils.hasText(internalToken)) {
            throw new IllegalStateException("hiretrack.internal.token must be set");
        }
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll();
                auth.requestMatchers("/internal/**").permitAll();         // checked by InternalTokenFilter
                props.security().publicPaths().forEach(p -> auth.requestMatchers(p).permitAll());
                auth.requestMatchers("/error").permitAll();
                auth.anyRequest().authenticated();
            })
            .oauth2ResourceServer(o -> o
                    .jwt(Customizer.withDefaults())
                    .authenticationEntryPoint(JsonSecurityHandlers.entryPoint(json)))
            .exceptionHandling(e -> e
                    .authenticationEntryPoint(JsonSecurityHandlers.entryPoint(json))
                    .accessDeniedHandler(JsonSecurityHandlers.accessDenied(json)))
            .addFilterBefore(new InternalTokenFilter(internalToken, json), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}