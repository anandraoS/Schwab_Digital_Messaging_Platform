package com.example.messaging.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Development issuer: an RSA key pair generated in memory at startup (never persisted, never shared), used to mint
 * tokens via {@code POST /dev/token} and to validate them with the matching public key (RS256). Active only when
 * {@code messaging.security.dev-token-enabled=true} (profiles local/test). Production relies on
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}.
 */
@Configuration
@ConditionalOnProperty(name = "messaging.security.dev-token-enabled", havingValue = "true")
public class JwtConfig {

    public static final String DEV_KEY_ID = "dev-key-1";

    @Bean
    public RSAKey devRsaKey() throws NoSuchAlgorithmException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(DEV_KEY_ID)
                .build();
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAKey devRsaKey, MessagingProperties props) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(devRsaKey.toRSAPublicKey()).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(java.time.Duration.ofSeconds(2)),
                JwtValidators.createDefaultWithIssuer(props.security().devIssuer()));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    public JwtEncoder devJwtEncoder(RSAKey devRsaKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(devRsaKey)));
    }
}
