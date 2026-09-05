package com.repointel.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.repointel.model.AnalysisReport;
import com.repointel.model.RepositoryData;
import java.lang.reflect.Type;
import java.sql.*;
import java.util.*;

public class RepositoryService {
    private static final Type LIST_TYPE = new TypeToken<List<String>>() { }.getType();
    private final DatabaseService database = new DatabaseService();
    private final Gson gson = new Gson();

    public long saveAnalysis(long userId, RepositoryData repository, GeminiService.Insight insight) throws SQLException {
        String repositorySql = "INSERT INTO repositories (user_id, repository_name, repository_url, programming_languages, stars, forks, analyzed_date) VALUES (?, ?, ?, ?, ?, ?, NOW())";
        String reportSql = "INSERT INTO analysis_reports (repository_id, summary, architecture_details, technology_insights, recommendations, readme_content, file_tree_json, recent_commits_json, fetched_files_json, generated_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement repositoryStatement = connection.prepareStatement(repositorySql, Statement.RETURN_GENERATED_KEYS)) {
                repositoryStatement.setLong(1, userId);
                repositoryStatement.setString(2, repository.owner() + "/" + repository.name());
                repositoryStatement.setString(3, repository.url());
                repositoryStatement.setString(4, gson.toJson(repository.languages()));
                repositoryStatement.setLong(5, repository.stars());
                repositoryStatement.setLong(6, repository.forks());
                repositoryStatement.executeUpdate();
                try (ResultSet keys = repositoryStatement.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Repository key was not generated.");
                    long repositoryId = keys.getLong(1);
                    try (PreparedStatement reportStatement = connection.prepareStatement(reportSql, Statement.RETURN_GENERATED_KEYS)) {
                        reportStatement.setLong(1, repositoryId);
                        reportStatement.setString(2, insight.summary());
                        reportStatement.setString(3, insight.architecture());
                        reportStatement.setString(4, insight.technologyInsights());
                        reportStatement.setString(5, insight.recommendations());
                        reportStatement.setString(6, insight.readme());
                        reportStatement.setString(7, gson.toJson(insight.files()));
                        reportStatement.setString(8, gson.toJson(insight.recentCommits()));
                        reportStatement.setString(9, gson.toJson(insight.fetchedFiles()));
                        reportStatement.executeUpdate();
                        try (ResultSet reportKeys = reportStatement.getGeneratedKeys()) {
                            if (!reportKeys.next()) throw new SQLException("Report key was not generated.");
                            connection.commit();
                            return reportKeys.getLong(1);
                        }
                    }
                }
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public List<AnalysisReport> history(long userId) throws SQLException {
        String sql = "SELECT ar.report_id, ar.repository_id, r.repository_name, r.repository_url, ar.summary, ar.architecture_details, ar.technology_insights, ar.recommendations, '' AS readme_content, '[]' AS file_tree_json, '[]' AS recent_commits_json, '{}' AS fetched_files_json, ar.generated_date FROM analysis_reports ar JOIN repositories r ON r.repository_id = ar.repository_id WHERE r.user_id = ? ORDER BY ar.generated_date DESC LIMIT 50";
        List<AnalysisReport> reports = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) reports.add(report(rs));
            }
        }
        return reports;
    }

    public Optional<AnalysisReport> find(long userId, long reportId) throws SQLException {
        String sql = "SELECT ar.report_id, ar.repository_id, r.repository_name, r.repository_url, ar.summary, ar.architecture_details, ar.technology_insights, ar.recommendations, ar.readme_content, ar.file_tree_json, ar.recent_commits_json, ar.fetched_files_json, ar.generated_date FROM analysis_reports ar JOIN repositories r ON r.repository_id = ar.repository_id WHERE r.user_id = ? AND ar.report_id = ?";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setLong(2, reportId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(report(rs)) : Optional.empty();
            }
        }
    }

    public boolean claimDemo(long userId) throws SQLException {
        return new AuthService().claimDemo(userId);
    }

    private AnalysisReport report(ResultSet rs) throws SQLException {
        return new AnalysisReport(rs.getLong("report_id"), rs.getLong("repository_id"), rs.getString("repository_name"), rs.getString("repository_url"), rs.getString("summary"), rs.getString("architecture_details"), rs.getString("technology_insights"), rs.getString("recommendations"), rs.getString("readme_content"), parseList(rs.getString("file_tree_json")), parseList(rs.getString("recent_commits_json")), parseMap(rs.getString("fetched_files_json")), rs.getTimestamp("generated_date").toInstant().toString());
    }

    private List<String> parseList(String json) {
        try {
            List<String> values = gson.fromJson(json, LIST_TYPE);
            return values == null ? List.of() : values;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private Map<String, String> parseMap(String json) {
        try {
            Map<String, String> values = gson.fromJson(json, new TypeToken<Map<String, String>>() { }.getType());
            return values == null ? Map.of() : values;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
