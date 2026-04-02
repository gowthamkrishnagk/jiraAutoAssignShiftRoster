package com.jira.autoassign.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * User store backed by PostgreSQL via JPA.
 * Users persist across restarts — no more in-memory wipe on redeploy.
 */
@Component
public class UserStore {

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public UserStore(UserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    /** Creates a user with default password: name + "Orderfallout" and role USER */
    public void addUser(String name, String email) {
        addUser(name, email, Role.USER);
    }

    /** Creates a user with default password: name + "Orderfallout" and specified role */
    public void addUser(String name, String email, Role role) {
        String defaultPassword = name + "Orderfallout";
        repository.save(new User(name, email, passwordEncoder.encode(defaultPassword), role));
    }

    public User findByEmail(String email) {
        return repository.findByEmailIgnoreCase(email).orElse(null);
    }

    public boolean exists(String email) {
        return repository.existsByEmailIgnoreCase(email);
    }

    public boolean isEmpty() {
        return repository.count() == 0;
    }

    public void updatePassword(String email, String newHashedPassword) {
        User user = findByEmail(email);
        if (user != null) {
            user.setPassword(newHashedPassword);
            repository.save(user);
        }
    }
}
