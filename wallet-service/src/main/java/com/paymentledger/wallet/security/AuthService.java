package com.paymentledger.wallet.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class AuthService {

    /**
     * A real bcrypt hash of a value nobody knows, matched against when the email is unknown so a
     * login attempt costs one full bcrypt verification either way. Without this, "no such user"
     * would return in microseconds while "wrong password" took ~100ms, and that difference alone
     * is a working account-enumeration oracle.
     */
    private static final String DUMMY_HASH =
            "$2y$10$rVb1yVQG89J5MFVHT8WTGOp6Cv4uxQm6frRmrmfDJ45oa4s7MByLG";

    private static final int REFRESH_TOKEN_BYTES = 32;

    private final AppUserRepository appUserRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final long refreshTokenTtlDays;

    public AuthService(AppUserRepository appUserRepository,
                        RefreshTokenRepository refreshTokenRepository,
                        PasswordEncoder passwordEncoder,
                        JwtService jwtService,
                        @Value("${app.jwt.refresh-token-ttl-days}") long refreshTokenTtlDays) {
        this.appUserRepository = appUserRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenTtlDays = refreshTokenTtlDays;
    }

    /**
     * Unlike login, this does tell the caller that an email is taken. Signup has to - there is no
     * way to offer a usable registration form otherwise - and it reveals nothing that attempting
     * to register does not already reveal.
     */
    @Transactional
    public AuthResponse register(String email, String rawPassword) {
        if (appUserRepository.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        }
        AppUser user = appUserRepository.save(new AppUser(email, passwordEncoder.encode(rawPassword)));
        return issueTokenPair(user);
    }

    @Transactional
    public AuthResponse login(String email, String rawPassword) {
        Optional<AppUser> user = appUserRepository.findByEmail(email);
        String hashToCheck = user.map(AppUser::getPasswordHash).orElse(DUMMY_HASH);

        // Deliberately unconditional, and deliberately not short-circuited by the isPresent check
        // below - both branches must perform the same bcrypt work. See DUMMY_HASH.
        boolean passwordMatches = passwordEncoder.matches(rawPassword, hashToCheck);

        if (user.isEmpty() || !passwordMatches) {
            throw new InvalidCredentialsException();
        }
        return issueTokenPair(user.get());
    }

    /**
     * Rotates on every use: the presented token is revoked and a new one issued, so a token is
     * only ever valid once. That is what makes reuse detectable - a revoked token coming back
     * means either the client replayed it or someone else stole it, and we cannot tell which, so
     * we assume theft and cut the whole family off.
     */
    /**
     * noRollbackFor is load-bearing, not decoration. Reuse detection below revokes the user's
     * whole token family and then throws - and a throw out of a @Transactional method rolls the
     * transaction back by default, which would undo that revocation and leave the stolen token
     * working. The rejection must stick even though the request fails.
     */
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public AuthResponse refresh(String rawRefreshToken) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(sha256Hex(rawRefreshToken))
                .orElseThrow(InvalidCredentialsException::new);

        if (stored.isRevoked()) {
            refreshTokenRepository.revokeAllForUser(stored.getUserId());
            throw new InvalidCredentialsException();
        }
        if (stored.isExpired(Instant.now())) {
            throw new InvalidCredentialsException();
        }

        stored.revoke();
        refreshTokenRepository.save(stored);

        AppUser user = appUserRepository.findById(stored.getUserId())
                .orElseThrow(InvalidCredentialsException::new);
        return issueTokenPair(user);
    }

    private AuthResponse issueTokenPair(AppUser user) {
        byte[] raw = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        refreshTokenRepository.save(new RefreshToken(
                user.getId(),
                sha256Hex(refreshToken),
                Instant.now().plus(refreshTokenTtlDays, ChronoUnit.DAYS)));

        return new AuthResponse(jwtService.issueAccessToken(user.getId()), refreshToken, user.getId());
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
