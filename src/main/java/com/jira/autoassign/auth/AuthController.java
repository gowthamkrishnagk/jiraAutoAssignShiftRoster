package com.jira.autoassign.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserStore userStore;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserStore userStore, JwtUtil jwtUtil, PasswordEncoder passwordEncoder) {
        this.userStore = userStore;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
    }

    /** Login — returns a JWT token valid for 8 hours */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        User user = userStore.findByEmail(req.email());
        if (user == null || !passwordEncoder.matches(req.password(), user.getPassword())) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid email or password"));
        }
        String token = jwtUtil.generateToken(user.getEmail());
        return ResponseEntity.ok(Map.of("token", token, "name", user.getName(), "role", user.getRole()));
    }

    /** Change own password — requires valid JWT */
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody ChangePasswordRequest req) {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        User user = userStore.findByEmail(email);
        if (!passwordEncoder.matches(req.currentPassword(), user.getPassword())) {
            return ResponseEntity.status(400).body(Map.of("error", "Current password is incorrect"));
        }
        userStore.updatePassword(email, passwordEncoder.encode(req.newPassword()));
        return ResponseEntity.ok(Map.of("message", "Password updated successfully"));
    }

    /** Add a new user — requires valid JWT.
     *  role is optional — defaults to USER if not provided.
     *  Default password: name + "Orderfallout" */
    @PostMapping("/users")
    public ResponseEntity<?> addUser(@RequestBody AddUserRequest req) {
        if (userStore.exists(req.email())) {
            return ResponseEntity.status(400).body(Map.of("error", "User already exists with that email"));
        }
        Role role = (req.role() != null) ? req.role() : Role.USER;
        userStore.addUser(req.name(), req.email(), role);
        return ResponseEntity.ok(Map.of(
                "message", "User added successfully",
                "role", role,
                "defaultPassword", req.name() + "Orderfallout"
        ));
    }

    record LoginRequest(String email, String password) {}
    record ChangePasswordRequest(String currentPassword, String newPassword) {}
    record AddUserRequest(String name, String email, Role role) {}
}
