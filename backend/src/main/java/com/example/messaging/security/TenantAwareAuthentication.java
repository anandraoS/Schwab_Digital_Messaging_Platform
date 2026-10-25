package com.example.messaging.security;

/** Implemented by the authentication token produced by {@link JwtAuthConverter} to expose the tenant claim. */
public interface TenantAwareAuthentication {
    String tenant();
}
