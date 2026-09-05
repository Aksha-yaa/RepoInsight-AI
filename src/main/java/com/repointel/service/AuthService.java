package com.repointel.service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.sql.*;

public class AuthService {
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Account(long id, String username, String email, boolean demoUsed) { }

    private final DatabaseService database = new DatabaseService();

    public Account register(String username, String email, String password) throws SQLException {
        String normalizedEmail = normalizeEmail(email);
        if (username == null || username.isBlank() || password == null || password.length() < 8) {
            throw new IllegalArgumentException("Use a name and a password with at least 8 characters.");
        }
        String sql = "INSERT INTO users (username, email, password_hash, demo_used) VALUES (?, ?, ?, FALSE)";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, username.trim());
            statement.setString(2, normalizedEmail);
            statement.setString(3, hash(password));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Account key was not generated.");
                return new Account(keys.getLong(1), username.trim(), normalizedEmail, false);
            }
        } catch (SQLIntegrityConstraintViolationException e) {
            throw new IllegalArgumentException("An account with that email already exists.");
        }
    }

    public Account login(String email, String password) throws SQLException {
        String sql = "SELECT user_id, username, email, password_hash, demo_used FROM users WHERE email = ?";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizeEmail(email));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !verify(password, result.getString("password_hash"))) {
                    throw new IllegalArgumentException("Email or password is incorrect.");
                }
                return new Account(result.getLong("user_id"), result.getString("username"), result.getString("email"), result.getBoolean("demo_used"));
            }
        }
    }

    public boolean claimDemo(long userId) throws SQLException {
        String sql = "UPDATE users SET demo_used = TRUE WHERE user_id = ? AND demo_used = FALSE";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            return statement.executeUpdate() == 1;
        }
    }

    private String normalizeEmail(String email) {
        if (email == null || !email.contains("@")) throw new IllegalArgumentException("Enter a valid email address.");
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encode(salt, derive(password.toCharArray(), salt, ITERATIONS), ITERATIONS);
    }

    private boolean verify(String password, String encoded) {
        try {
            String[] parts = encoded.split(":");
            if (parts.length != 3) return false;
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            byte[] actual = derive(password.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private byte[] derive(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec specification = new PBEKeySpec(password, salt, iterations, KEY_LENGTH);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Password hashing is unavailable.", e);
        }
    }

    private String encode(byte[] salt, byte[] hash, int iterations) {
        return iterations + ":" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
    }
}
