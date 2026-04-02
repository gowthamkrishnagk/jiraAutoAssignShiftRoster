package com.jira.autoassign.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * In-memory user store. Key = name, Value = User (which contains email).
 * Default password for every user is: name + "Orderfallout"
 * e.g. Alice -> "AliceOrderfallout"
 *
 * To add more users permanently, add them inside init().
 * To add users at runtime, call addUser() via the /api/auth/users endpoint.
 */
@Component
public class UserStore {

    private final Map<String, User> users = new HashMap<>(); // key = name
    private final PasswordEncoder passwordEncoder;

    public UserStore(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @PostConstruct
    public void init() {
        addUser("Gowtham", "gowtham.krishna@libertypr.com", Role.ADMIN);
    }

    /** Creates a user with default password: name + "Orderfallout" and role USER */
    public void addUser(String name, String email) {
        addUser(name, email, Role.USER);
    }

    /** Creates a user with default password: name + "Orderfallout" and specified role */
    public void addUser(String name, String email, Role role) {
        String defaultPassword = name + "Orderfallout";
        users.put(name, new User(name, email, passwordEncoder.encode(defaultPassword), role));
    }

    /** Lookup by email — iterates values since key is name */
    public User findByEmail(String email) {
        return users.values().stream()
                .filter(u -> u.getEmail().equalsIgnoreCase(email))
                .findFirst()
                .orElse(null);
    }

    /** Check if a user with this email already exists */
    public boolean exists(String email) {
        return findByEmail(email) != null;
    }

    public void updatePassword(String email, String newHashedPassword) {
        User user = findByEmail(email);
        if (user != null) {
            user.setPassword(newHashedPassword);
        }
    }
}
