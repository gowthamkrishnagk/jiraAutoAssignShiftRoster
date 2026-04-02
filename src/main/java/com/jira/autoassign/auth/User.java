package com.jira.autoassign.auth;

public class User {

    private final String name;
    private final String email;
    private String password; // BCrypt hashed
    private Role role;

    public User(String name, String email, String password, Role role) {
        this.name = name;
        this.email = email;
        this.password = password;
        this.role = role;
    }

    public String getName()     { return name; }
    public String getEmail()    { return email; }
    public String getPassword() { return password; }
    public Role getRole()       { return role; }

    public void setPassword(String password) { this.password = password; }
    public void setRole(Role role)           { this.role = role; }
}
