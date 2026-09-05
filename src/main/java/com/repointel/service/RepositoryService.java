package com.repointel.service;

import com.google.gson.Gson;
import com.repointel.model.AnalysisReport;
import com.repointel.model.RepositoryData;
import java.sql.*;
import java.util.*;

public class RepositoryService {
    private final DatabaseService database = new DatabaseService();
    private final Gson gson = new Gson();
    public long saveAnalysis(RepositoryData repository, GeminiService.Insight insight) throws SQLException {
        String repositorySql = "INSERT INTO repositories (repository_name, repository_url, programming_languages, stars, forks, analyzed_date) VALUES (?, ?, ?, ?, ?, NOW())";
        String reportSql = "INSERT INTO analysis_reports (repository_id, summary, architecture_details, recommendations, generated_date) VALUES (?, ?, ?, ?, NOW())";
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement repositoryStatement = connection.prepareStatement(repositorySql, Statement.RETURN_GENERATED_KEYS)) {
                repositoryStatement.setString(1, repository.owner() + "/" + repository.name()); repositoryStatement.setString(2, repository.url()); repositoryStatement.setString(3, gson.toJson(repository.languages())); repositoryStatement.setLong(4, repository.stars()); repositoryStatement.setLong(5, repository.forks()); repositoryStatement.executeUpdate();
                try (ResultSet keys = repositoryStatement.getGeneratedKeys()) { if (!keys.next()) throw new SQLException("Repository key was not generated."); long id = keys.getLong(1);
                    try (PreparedStatement reportStatement = connection.prepareStatement(reportSql, Statement.RETURN_GENERATED_KEYS)) { reportStatement.setLong(1, id); reportStatement.setString(2, insight.summary()); reportStatement.setString(3, insight.architecture()); reportStatement.setString(4, insight.recommendations()); reportStatement.executeUpdate(); try (ResultSet reportKeys = reportStatement.getGeneratedKeys()) { if (!reportKeys.next()) throw new SQLException("Report key was not generated."); connection.commit(); return reportKeys.getLong(1); } }
                }
            } catch (SQLException e) { connection.rollback(); throw e; }
        }
    }
    public List<AnalysisReport> history() throws SQLException {
        String sql = "SELECT ar.report_id, ar.repository_id, r.repository_name, r.repository_url, ar.summary, ar.architecture_details, ar.recommendations, ar.generated_date FROM analysis_reports ar JOIN repositories r ON r.repository_id = ar.repository_id ORDER BY ar.generated_date DESC LIMIT 50";
        List<AnalysisReport> reports = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet rs = statement.executeQuery()) {
            while (rs.next()) reports.add(new AnalysisReport(rs.getLong("report_id"), rs.getLong("repository_id"), rs.getString("repository_name"), rs.getString("repository_url"), rs.getString("summary"), rs.getString("architecture_details"), rs.getString("recommendations"), rs.getTimestamp("generated_date").toInstant().toString()));
        } return reports;
    }
    public Optional<AnalysisReport> find(long reportId) throws SQLException {
        String sql = "SELECT ar.report_id, ar.repository_id, r.repository_name, r.repository_url, ar.summary, ar.architecture_details, ar.recommendations, ar.generated_date FROM analysis_reports ar JOIN repositories r ON r.repository_id = ar.repository_id WHERE ar.report_id = ?";
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(sql)) { s.setLong(1, reportId); try (ResultSet rs = s.executeQuery()) { return rs.next() ? Optional.of(new AnalysisReport(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant().toString())) : Optional.empty(); } }
    }
}
