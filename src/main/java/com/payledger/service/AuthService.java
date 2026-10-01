package com.payledger.service;

import com.payledger.domain.User;
import com.payledger.domain.Wallet;
import com.payledger.dto.AuthDtos.LoginRequest;
import com.payledger.dto.AuthDtos.LoginResponse;
import com.payledger.dto.AuthDtos.RegisterRequest;
import com.payledger.dto.AuthDtos.RegisterResponse;
import com.payledger.error.ApiException;
import com.payledger.repository.UserRepository;
import com.payledger.repository.WalletRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final WalletRepository wallets;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final Duration tokenTtl;
    /** Compared against when the email is unknown, so "no such user" costs the same as "wrong password". */
    private final String dummyHash;

    public AuthService(UserRepository users, WalletRepository wallets, PasswordEncoder passwordEncoder,
                       JwtEncoder jwtEncoder, @Value("${payledger.jwt.ttl:PT1H}") Duration tokenTtl) {
        this.users = users;
        this.wallets = wallets;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.tokenTtl = tokenTtl;
        this.dummyHash = passwordEncoder.encode("timing-equaliser-not-a-real-password");
    }

    /** Creates the user and their wallet atomically: either both exist or neither does. */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = normalise(request.email());
        User user;
        try {
            // The unique constraint is the real duplicate check: it also catches two sign-ups racing.
            user = users.saveAndFlush(new User(email, passwordEncoder.encode(request.password()), request.role()));
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "An account with this email already exists");
        }
        Wallet wallet = wallets.save(new Wallet(user.getId()));
        return new RegisterResponse(user.getId(), wallet.getId(), user.getEmail(), user.getRole());
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = users.findByEmail(normalise(request.email())).orElse(null);
        String hash = user != null ? user.getPasswordHash() : dummyHash;
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (user == null || !matches) {
            // Same code and message for unknown email and wrong password: no account enumeration.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect");
        }

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("payledger")
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(tokenTtl))
                .claim("role", user.getRole().name())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new LoginResponse(token, "Bearer", tokenTtl.toSeconds());
    }

    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
