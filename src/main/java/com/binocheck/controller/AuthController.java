package com.binocheck.controller;

import com.binocheck.entity.User;
import com.binocheck.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody AuthRequest request) {
        if (request.username() == null || request.username().trim().isEmpty() ||
            request.password() == null || request.password().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Username and password are required."));
        }

        String username = request.username().trim().toLowerCase();
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
    public ResponseEntity<?> login(@RequestBody AuthRequest request, HttpSession session) {
        if (request.username() == null || request.password() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Username and password are required."));
        }

        String username = request.username().trim().toLowerCase();
        User user = userRepository.findByUsername(username)
                .orElse(null);

        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Invalid username or password."));
        }

        session.setAttribute("userId", user.getId());
        session.setAttribute("username", user.getUsername());

        return ResponseEntity.ok(Map.of(
                "message", "Login successful",
                "user", Map.of("id", user.getId(), "username", user.getUsername())
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
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
