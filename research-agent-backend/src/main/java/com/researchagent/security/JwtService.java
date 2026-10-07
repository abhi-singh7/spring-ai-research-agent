package com.researchagent.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Issues and validates HS256 compact JWTs (RFC 7519) via nimbus-jose-jwt.
 *
 * <p>Claims: {@code sub}=username, {@code role}, {@code iat}, {@code exp} (bounded by the
 * configured expiry), {@code iss}. The signing secret must be at least 32 bytes — a weak or
 * missing configuration fails fast at startup instead of silently issuing forgeable tokens.</p>
 */
@Component
public class JwtService {

    private static final int MIN_SECRET_BYTES = 32;

    private final MACSigner signer;
    private final MACVerifier verifier;
    private final String issuer;
    private final long expirationSeconds;

    public JwtService(@Value("${app.security.jwt.secret}") String secret,
                      @Value("${app.security.jwt.expiration-seconds:28800}") long expirationSeconds,
                      @Value("${app.security.jwt.issuer:research-agent}") String issuer) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.security.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes long (got "
                            + secretBytes.length + ") — set a strong JWT_SECRET");
        }
        try {
            this.signer = new MACSigner(secretBytes);
            this.verifier = new MACVerifier(secretBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize JWT signer for the configured secret", e);
        }
        this.issuer = issuer;
        this.expirationSeconds = expirationSeconds;
    }

    /**
     * Mint a signed token for the given user.
     */
    public String issue(String username, String role) {
        Date now = new Date();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(username)
                .issuer(issuer)
                .claim("role", role)
                .issueTime(now)
                .expirationTime(new Date(now.getTime() + expirationSeconds * 1000L))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue JWT", e);
        }
    }

    /**
     * Validate a token's signature, expiry and issuer.
     *
     * @throws InvalidTokenException if the token is malformed, has an invalid signature,
     *         is expired, or was issued by a different issuer
     */
    public CurrentUser validate(String token) {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (java.text.ParseException e) {
            throw new InvalidTokenException("Malformed token");
        }

        boolean signatureValid;
        try {
            signatureValid = jwt.verify(verifier);
        } catch (Exception e) {
            throw new InvalidTokenException("Invalid token signature");
        }
        if (!signatureValid) {
            throw new InvalidTokenException("Invalid token signature");
        }

        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (java.text.ParseException e) {
            throw new InvalidTokenException("Malformed token claims");
        }

        Date expiration = claims.getExpirationTime();
        if (expiration == null || !expiration.after(new Date())) {
            throw new InvalidTokenException("Token expired");
        }
        if (!issuer.equals(claims.getIssuer())) {
            throw new InvalidTokenException("Unexpected token issuer");
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidTokenException("Token missing subject");
        }
        Object roleClaim = claims.getClaim("role");
        String role = roleClaim instanceof String r && !r.isBlank() ? r : "USER";
        return new CurrentUser(subject, role);
    }
}
