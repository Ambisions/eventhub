package com.eventhub.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.eventhub.auth.api.AuthApi;
import com.eventhub.auth.api.command.LoginCommand;
import com.eventhub.auth.api.command.RegisterCommand;
import com.eventhub.auth.api.model.AccessToken;
import com.eventhub.config.SecurityConfiguration;
import com.eventhub.user.api.model.UserProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthSecurityIntegrationTest {

    private static final String TEST_PREFIX = "phase5-security-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthApi authApi;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void allowsAnonymousRegistrationAndLoginWithoutExposingCredentials() throws Exception {
        String username = uniqueUsername("http");
        String password = "http-password";

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new RegisterBody(username, password, "HTTP User"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        String loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new LoginBody(username, password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode body = objectMapper.readTree(loginResponse);
        assertThat(body.get("accessToken").asText()).isNotBlank();
    }

    @Test
    void returnsJson401WithoutBearerToken() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void usesSignedPrincipalInsteadOfRequestSuppliedIdentityAndKeepsTokenValidAfterLogout() throws Exception {
        UserProfile authenticatedUser = register("principal");
        UserProfile anotherUser = register("other");
        AccessToken accessToken = authApi.login(new LoginCommand(
                authenticatedUser.getUsername(),
                "valid-password"));

        mockMvc.perform(get("/api/users/me")
                        .queryParam("userId", anotherUser.getId().toString())
                        .header("X-User-Id", anotherUser.getId().toString())
                        .header("Authorization", "Bearer " + accessToken.getTokenValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(authenticatedUser.getId()))
                .andExpect(jsonPath("$.username").value(authenticatedUser.getUsername()));

        mockMvc.perform(put("/api/users/me")
                        .header("Authorization", "Bearer " + accessToken.getTokenValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"  Updated Through Principal  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(authenticatedUser.getId()))
                .andExpect(jsonPath("$.displayName").value("Updated Through Principal"));

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken.getTokenValue()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + accessToken.getTokenValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(authenticatedUser.getId()));
    }

    @Test
    void rejectsWrongSignatureExpiredIssuerAndRequiredClaimsWithJson401() throws Exception {
        Instant now = Instant.now();
        String wrongSignature = encode(
                alternateEncoder(), "eventhub", "42", "alice", "USER",
                now.minusSeconds(1), now.plusSeconds(1_799));
        String expired = encode(
                jwtEncoder, "eventhub", "42", "alice", "USER",
                now.minusSeconds(1_801), now.minusSeconds(1));
        String wrongIssuer = encode(
                jwtEncoder, "another-issuer", "42", "alice", "USER",
                now.minusSeconds(1), now.plusSeconds(1_799));
        String invalidRole = encode(
                jwtEncoder, "eventhub", "42", "alice", "ROOT",
                now.minusSeconds(1), now.plusSeconds(1_799));

        assertUnauthorized(wrongSignature);
        assertUnauthorized(expired);
        assertUnauthorized(wrongIssuer);
        assertUnauthorized(invalidRole);
        assertUnauthorized("malformed-token");
    }

    @Test
    void mapsUserRoleButDeniesAdminRouteWithJson403() throws Exception {
        UserProfile user = register("authority");
        AccessToken accessToken = authApi.login(new LoginCommand(user.getUsername(), "valid-password"));

        mockMvc.perform(post("/api/admin/events/1/approve")
                        .header("Authorization", "Bearer " + accessToken.getTokenValue()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void mapsDomainAndValidationFailuresToStableHttpStatuses() throws Exception {
        String username = uniqueUsername("errors");
        RegisterBody registration = new RegisterBody(username, "valid-password", null);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(registration)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(registration)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new LoginBody("!", "valid-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @AfterTransaction
    void verifiesTransactionRollbackRemovedSyntheticUsers() {
        Long residueCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username LIKE ?",
                Long.class,
                TEST_PREFIX + "%");
        assertThat(residueCount).isZero();
    }

    private UserProfile register(String scenario) {
        return authApi.register(new RegisterCommand(
                uniqueUsername(scenario),
                "valid-password",
                scenario));
    }

    private void assertUnauthorized(String token) throws Exception {
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private JwtEncoder alternateEncoder() {
        SecretKey key = new SecretKeySpec(
                "b".repeat(32).getBytes(StandardCharsets.UTF_8),
                "HmacSHA256");
        return new SecurityConfiguration().jwtEncoder(key);
    }

    private static String encode(
            JwtEncoder encoder,
            String issuer,
            String subject,
            String username,
            String role,
            Instant issuedAt,
            Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("username", username)
                .claim("role", role)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static String uniqueUsername(String scenario) {
        return TEST_PREFIX + scenario + '-' + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private record RegisterBody(String username, String password, String displayName) {
    }

    private record LoginBody(String username, String password) {
    }
}
