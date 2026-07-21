package com.repointel.service;

import com.repointel.util.Config;
import java.sql.*;

public class DatabaseService {
    static { try { Class.forName("com.mysql.cj.jdbc.Driver"); } catch (ClassNotFoundException e) { throw new ExceptionInInitializerError(e); } }
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(Config.get("DB_URL", "jdbc:mysql://localhost:3306/repo_intelligence?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"), Config.get("DB_USER", "repo_user"), Config.get("DB_PASSWORD", "repo_password"));
    }
}
