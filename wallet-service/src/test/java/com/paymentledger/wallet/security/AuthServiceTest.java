package com.paymentledger.wallet.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private static final String EMAIL = "someone@example.com";
    private static final String PASSWORD = "correct-horse-battery";

    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final PasswordEncoder passwordEncoder = spy(new BCryptPasswordEncoder());
    private final JwtService jwtService = new JwtService(
            "test-only-secret-key-must-be-at-least-32-bytes-long", 15);

    /**
     * A hand-rolled in-memory stand-in rather than a mock: rotation and reuse detection are about
     * how rows change over a sequence of calls, and stubbing that with when/thenReturn would only
     * be asserting against the stubbing.
     */
    private final Map<String, RefreshToken> tokensByHash = new HashMap<>();
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);

    private AuthService authService;

    @BeforeEach
    void setUp() {
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> {
            RefreshToken token = invocation.getArgument(0);
            tokensByHash.put(token.getTokenHash(), token);
            return token;
        });
        when(refreshTokenRepository.findByTokenHash(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(tokensByHash.get(invocation.getArgument(0))));
        when(refreshTokenRepository.revokeAllForUser(any(UUID.class))).thenAnswer(invocation -> {
            UUID userId = invocation.getArgument(0);
            int revoked = 0;
            for (RefreshToken token : tokensByHash.values()) {
                if (token.getUserId().equals(userId) && !token.isRevoked()) {
                    token.revoke();
                    revoked++;
                }
            }
            return revoked;
        });

        authService = new AuthService(appUserRepository, refreshTokenRepository,
                passwordEncoder, jwtService, 14);
    }

    private AppUser seedUser() {
        AppUser user = new AppUser(EMAIL, passwordEncoder.encode(PASSWORD));
        when(appUserRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void loginWithTheRightPasswordIssuesATokenPair() {
        AppUser user = seedUser();

        AuthResponse response = authService.login(EMAIL, PASSWORD);

        assertThat(response.userId()).isEqualTo(user.getId());
        assertThat(jwtService.parseUserId(response.accessToken())).contains(user.getId());
        assertThat(response.refreshToken()).isNotBlank();
    }

    @Test
    void loginWithAWrongPasswordIsRejected() {
        seedUser();

        assertThatThrownBy(() -> authService.login(EMAIL, "not-the-password"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid credentials");
    }

    @Test
    void loginWithAnUnknownEmailFailsIdenticallyToAWrongPassword() {
        seedUser();

        Throwable unknownEmail = org.assertj.core.api.Assertions.catchThrowable(
                () -> authService.login("nobody@example.com", PASSWORD));
        Throwable wrongPassword = org.assertj.core.api.Assertions.catchThrowable(
                () -> authService.login(EMAIL, "not-the-password"));

        assertThat(unknownEmail).isInstanceOf(InvalidCredentialsException.class);
        assertThat(wrongPassword).isInstanceOf(InvalidCredentialsException.class);
        assertThat(unknownEmail.getMessage()).isEqualTo(wrongPassword.getMessage());
    }

    /**
     * The anti-enumeration guarantee is "the same work happens either way". Asserting on elapsed
     * time would be flaky under a loaded CI runner, so this asserts the thing that actually costs
     * the time: one full bcrypt verification runs even when there is no user to verify against.
     */
    @Test
    void loginWithAnUnknownEmailStillPerformsABcryptVerification() {
        when(appUserRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("nobody@example.com", PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
    }

    @Test
    void registerRejectsAnEmailThatIsAlreadyTaken() {
        seedUser();

        assertThatThrownBy(() -> authService.register(EMAIL, PASSWORD))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void refreshRotatesTheTokenAndRevokesTheOnePresented() {
        AppUser user = seedUser();
        String first = authService.login(EMAIL, PASSWORD).refreshToken();

        String second = authService.refresh(first).refreshToken();

        assertThat(second).isNotEqualTo(first);
        assertThat(tokenFor(first).isRevoked()).isTrue();
        assertThat(tokenFor(second).isRevoked()).isFalse();
        assertThat(tokensFor(user.getId())).hasSize(2);
    }

    /**
     * Note what this test CANNOT prove: the in-memory repository below has no transaction
     * semantics, so it cannot catch the revocation being rolled back by the exception thrown
     * immediately after it - which is exactly the bug that shipped and was caught only against a
     * real database. See AuthService#refresh's noRollbackFor, and ErrorResponseIT, which does
     * exercise this over a real Postgres.
     */
    @Test
    void refreshTokenReplayedAfterRotationIsRejectedAndRevokesEveryTokenForThatUser() {
        AppUser user = seedUser();
        String first = authService.login(EMAIL, PASSWORD).refreshToken();
        String second = authService.refresh(first).refreshToken();

        assertThatThrownBy(() -> authService.refresh(first))
                .isInstanceOf(InvalidCredentialsException.class);

        // The replayed token was already revoked; the point is that the *live* one is now dead
        // too, because a revoked token coming back means we cannot trust the family any more.
        assertThat(tokenFor(second).isRevoked()).isTrue();
        assertThat(tokensFor(user.getId())).allMatch(RefreshToken::isRevoked);
        assertThatThrownBy(() -> authService.refresh(second))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refreshWithATokenThatWasNeverIssuedIsRejected() {
        assertThatThrownBy(() -> authService.refresh("never-issued-by-us"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void rawRefreshTokenIsNeverStored() {
        seedUser();

        String raw = authService.login(EMAIL, PASSWORD).refreshToken();

        assertThat(tokensByHash).doesNotContainKey(raw);
        assertThat(tokensByHash.values())
                .noneMatch(token -> token.getTokenHash().equals(raw))
                .allMatch(token -> token.getTokenHash().matches("[0-9a-f]{64}"));
    }

    /**
     * The V5 backfill hash is a literal - Flyway cannot call an encoder - so nothing else would
     * catch it being wrong until every pre-existing owner found themselves locked out. This reads
     * the hash back out of the migration and verifies it against the password README documents.
     */
    @Test
    void backfillHashInV5MigrationVerifiesAgainstTheDocumentedDevPassword() throws Exception {
        String migration = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/resources/db/migration/V5__auth_tables.sql")),
                java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("'(\\$2[aby]\\$\\d\\d\\$[./A-Za-z0-9]{53})'")
                .matcher(migration);

        assertThat(matcher.find()).as("V5 migration contains a bcrypt literal").isTrue();
        assertThat(new BCryptPasswordEncoder().matches("dev-password-change-me", matcher.group(1)))
                .as("backfilled users can log in with the password README documents")
                .isTrue();
    }

    private RefreshToken tokenFor(String rawToken) {
        return tokensByHash.values().stream()
                .filter(token -> token.getTokenHash().equals(sha256Hex(rawToken)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no stored token for the given raw value"));
    }

    private List<RefreshToken> tokensFor(UUID userId) {
        List<RefreshToken> found = new ArrayList<>();
        for (RefreshToken token : tokensByHash.values()) {
            if (token.getUserId().equals(userId)) {
                found.add(token);
            }
        }
        return found;
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
