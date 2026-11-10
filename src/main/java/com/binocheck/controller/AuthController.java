package com.binocheck.controller;

import com.binocheck.entity.User;
import com.binocheck.repository.UserRepository;
import com.binocheck.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final int MIN_PASSWORD_LENGTH = 8;
    static final int MAX_PASSWORD_LENGTH = 128; // BCrypt only uses the first 72 bytes; cap to bound hashing cost
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9._-]{3,50}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    // Compared against when the username does not exist, so a failed login takes the same time
    // whether or not the account exists (prevents username enumeration by timing).
    private final String dummyHash;

    @Autowired
    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, RateLimiter rateLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.dummyHash = passwordEncoder.encode("binocheck-timing-equaliser");
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody AuthRequest request, HttpServletRequest httpRequest) {
        rateLimiter.check("signup:" + httpRequest.getRemoteAddr(), 5, Duration.ofHours(1));

        if (request.username() == null || request.password() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Username and password are required."));
        }

        String username = request.username().trim().toLowerCase();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Username must be 3-50 characters: letters, digits, '.', '_' or '-'."));
        }
        if (request.password().length() < MIN_PASSWORD_LENGTH || request.password().length() > MAX_PASSWORD_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Password must be between " + MIN_PASSWORD_LENGTH + " and " + MAX_PASSWORD_LENGTH + " characters."));
        }
        if (userRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Username is already taken."));
        }

        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(request.password()))
                .createdAt(LocalDateTime.now())
                .build();
        userRepository.save(user);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message", "User registered successfully!"));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthRequest request, HttpServletRequest httpRequest) {
        rateLimiter.check("login:" + httpRequest.getRemoteAddr(), 10, Duration.ofMinutes(5));

        if (request.username() == null || request.password() == null
                || request.password().length() > MAX_PASSWORD_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("message", "Username and password are required."));
        }

        String username = request.username().trim().toLowerCase();
        User user = userRepository.findByUsername(username).orElse(null);

        boolean passwordMatches = passwordEncoder.matches(request.password(),
                user != null ? user.getPasswordHash() : dummyHash);
        if (user == null || !passwordMatches) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Invalid username or password."));
        }

        // Issue a fresh session ID on login so a session ID planted before login (session fixation) is useless.
        HttpSession session = httpRequest.getSession(true);
        httpRequest.changeSessionId();
        session.setAttribute("userId", user.getId());
        session.setAttribute("username", user.getUsername());

        return ResponseEntity.ok(Map.of(
                "message", "Login successful",
                "user", Map.of("id", user.getId(), "username", user.getUsername())
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        Long userId = session != null ? (Long) session.getAttribute("userId") : null;
        if (userId == null) {
            return ResponseEntity.ok(Map.of("authenticated", false));
        }

        return userRepository.findById(userId)
                .map(user -> ResponseEntity.ok(Map.of(
                        "authenticated", true,
                        "id", user.getId(),
                        "username", user.getUsername()
                )))
                .orElseGet(() -> {
                    session.invalidate();
                    return ResponseEntity.ok(Map.of("authenticated", false));
                });
    }

    public record AuthRequest(String username, String password) {}
}
