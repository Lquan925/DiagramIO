package com.diagramio.domain.auth;

import com.diagramio.domain.TransactionRunner;
import com.diagramio.domain.exception.ConflictException;
import com.diagramio.domain.exception.UnauthorizedException;
import com.diagramio.domain.user.User;
import com.diagramio.domain.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/** Nghiệp vụ xác thực: đăng ký, đăng nhập, refresh (rotation), phát hiện tái sử dụng, đăng xuất. */
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();
    public static final Duration DEFAULT_REUSE_LEEWAY = Duration.ofSeconds(5);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordHasher passwordHasher;
    private final TokenIssuer tokenIssuer;
    private final TransactionRunner transactionRunner;
    private final Duration refreshTokenTtl;
    private final Duration reuseLeeway;
    private final Clock clock;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordHasher passwordHasher,
                       TokenIssuer tokenIssuer,
                       TransactionRunner transactionRunner,
                       Duration refreshTokenTtl,
                       Clock clock) {
        this(userRepository, refreshTokenRepository, passwordHasher, tokenIssuer, transactionRunner,
                refreshTokenTtl, DEFAULT_REUSE_LEEWAY, clock);
    }

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordHasher passwordHasher,
                       TokenIssuer tokenIssuer,
                       TransactionRunner transactionRunner,
                       Duration refreshTokenTtl,
                       Duration reuseLeeway,
                       Clock clock) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordHasher = passwordHasher;
        this.tokenIssuer = tokenIssuer;
        this.transactionRunner = transactionRunner;
        this.refreshTokenTtl = refreshTokenTtl;
        this.reuseLeeway = reuseLeeway;
        this.clock = clock;
    }

    public User register(String username, String email, String rawPassword) {
        String normalizedEmail = normalizeEmail(email);
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ConflictException("Email is already registered");
        }
        if (userRepository.existsByUsername(username)) {
            throw new ConflictException("Username is already taken");
        }
        User user = new User(UUID.randomUUID(), username, normalizedEmail, passwordHasher.hash(rawPassword),
                clock.instant());
        return userRepository.save(user);
    }

    public AuthTokens login(String email, String rawPassword) {
        // Cùng một thông báo cho sai email và sai mật khẩu, để không lộ email nào đã đăng ký
        User user = userRepository.findByEmail(normalizeEmail(email))
                .filter(u -> passwordHasher.matches(rawPassword, u.passwordHash()))
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));
        return issueTokens(user, UUID.randomUUID());
    }

    /**
     * Đổi refresh token lấy cặp token mới; token cũ bị thu hồi ngay (rotation).
     * Dùng khóa dòng (findByTokenHashWithLock) để xếp hàng các request đồng thời.
     * Nếu token đã bị thu hồi trước đó và vượt quá thời gian ân hạn (reuseLeeway):
     * phát hiện hành vi tái sử dụng token (Token Reuse Attack) -> thu hồi toàn bộ chuỗi token (familyId).
     */
    public AuthTokens refresh(String rawRefreshToken) {
        return transactionRunner.inTransaction(() -> {
            Instant now = clock.instant();
            RefreshToken current = refreshTokenRepository.findByTokenHashWithLock(hash(rawRefreshToken))
                    .orElseThrow(() -> new UnauthorizedException("Invalid or expired refresh token"));

            if (current.revoked()) {
                // Token đã bị thu hồi trước đó.
                // Nếu vượt quá khoảng thời gian ân hạn (leeway) cho phép request đồng thời,
                // đây là hành vi tái sử dụng trái phép (Token Reuse Attack) -> thu hồi toàn bộ chuỗi token.
                if (isBeyondLeeway(current, now)) {
                    refreshTokenRepository.revokeFamily(current.familyId(), now);
                }
                throw new UnauthorizedException("Invalid or expired refresh token");
            }

            if (!current.isUsable(now)) {
                throw new UnauthorizedException("Invalid or expired refresh token");
            }

            refreshTokenRepository.save(current.revoke(now));
            User user = userRepository.findById(current.userId())
                    .orElseThrow(() -> new UnauthorizedException("Invalid or expired refresh token"));
            return issueTokens(user, current.familyId());
        });
    }

    /**
     * Thu hồi refresh token được gửi lên, chỉ khi nó thuộc người đang đăng nhập.
     * Token không tồn tại, đã thu hồi hoặc của người khác thì bỏ qua.
     */
    public void logout(UUID userId, String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(hash(rawRefreshToken))
                .filter(t -> t.userId().equals(userId))
                .filter(t -> !t.revoked())
                .ifPresent(t -> refreshTokenRepository.save(t.revoke(clock.instant())));
    }

    private boolean isBeyondLeeway(RefreshToken token, Instant now) {
        if (token.revokedAt() == null) {
            return true;
        }
        return now.isAfter(token.revokedAt().plus(reuseLeeway));
    }

    private AuthTokens issueTokens(User user, UUID familyId) {
        String rawRefreshToken = generateRefreshToken();
        Instant expiresAt = clock.instant().plus(refreshTokenTtl);
        refreshTokenRepository.save(
                new RefreshToken(UUID.randomUUID(), user.id(), hash(rawRefreshToken), expiresAt, false, familyId, null));
        return new AuthTokens(tokenIssuer.issueAccessToken(user), rawRefreshToken);
    }

    private static String generateRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
