package com.researchagent.security;

import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JwtService}: issue/validate round-trip, expiry enforcement,
 * signature tamper detection and the fail-fast weak-secret check.
 */
class JwtServiceTest {

    private static final String SECRET_A = "unit-test-secret-a-32-bytes-minimum!";
    private static final String SECRET_B = "unit-test-secret-b-32-bytes-minimum!";

    private JwtService service(String secret, long expirationSeconds) {
        return new JwtService(secret, expirationSeconds, "research-agent");
    }

    @Test
    void issueAndValidate_roundTripPreservesSubjectRoleAndIssuer() {
        JwtService jwt = service(SECRET_A, 3600);

        String token = jwt.issue("alice", "USER");

        CurrentUser user = jwt.validate(token);
        assertEquals("alice", user.username());
        assertEquals("USER", user.role());

        // Raw claims: iss/iat/exp must all be present per the wire contract
        com.nimbusds.jwt.JWTClaimsSet claims;
        try {
            com.nimbusds.jwt.SignedJWT parsed = com.nimbusds.jwt.SignedJWT.parse(token);
            claims = parsed.getJWTClaimsSet();
        } catch (java.text.ParseException e) {
            throw new AssertionError("issued token must parse", e);
        }
        assertEquals("research-agent", claims.getIssuer());
        assertTrue(claims.getIssueTime() != null, "iat claim must be present");
        Date exp = claims.getExpirationTime();
        assertTrue(exp != null && exp.after(new Date()), "exp must be in the future");
    }

    @Test
    void expiredToken_isRejected() {
        // Negative expiry mints a token that is already expired.
        JwtService jwt = service(SECRET_A, -10);

        String token = jwt.issue("alice", "USER");

        assertThrows(InvalidTokenException.class, () -> jwt.validate(token));
    }

    @Test
    void tamperedSignature_isRejected() {
        JwtService issuer = service(SECRET_A, 3600);
        JwtService otherSecret = service(SECRET_B, 3600);

        String token = issuer.issue("alice", "USER");

        // A verifier with a different secret must reject the token
        assertThrows(InvalidTokenException.class, () -> otherSecret.validate(token));

        // Flipping one character of the signature segment must also fail
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");
        assertThrows(InvalidTokenException.class, () -> issuer.validate(tampered));
    }

    @Test
    void malformedToken_isRejected() {
        JwtService jwt = service(SECRET_A, 3600);

        assertThrows(InvalidTokenException.class, () -> jwt.validate("not-a-jwt"));
        assertThrows(InvalidTokenException.class, () -> jwt.validate(""));
        assertThrows(InvalidTokenException.class, () -> jwt.validate("aaa.bbb.ccc"));
    }

    @Test
    void wrongIssuer_isRejected() {
        JwtService otherIssuer = new JwtService(SECRET_A, 3600, "some-other-service");

        String token = otherIssuer.issue("alice", "USER");

        assertThrows(InvalidTokenException.class, () -> service(SECRET_A, 3600).validate(token));
    }

    @Test
    void shortSecret_failsAtConstruction() {
        assertThrows(IllegalStateException.class, () -> new JwtService("too-short", 3600, "research-agent"));
        assertThrows(IllegalStateException.class, () -> new JwtService("", 3600, "research-agent"));
    }
}
