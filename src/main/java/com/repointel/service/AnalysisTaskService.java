package com.repointel.service;

import com.repointel.model.RepositoryData;
import java.util.UUID;
import java.util.concurrent.*;

public class AnalysisTaskService {
    public enum State { QUEUED, RUNNING, SUCCEEDED, FAILED }
    public record TaskView(String taskId, State state, String message, Long reportId, GeminiService.Insight insight, RepositoryData repository) { }

    private static final AnalysisTaskService INSTANCE = new AnalysisTaskService();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ConcurrentMap<String, Task> tasks = new ConcurrentHashMap<>();
    private final GeminiService gemini = new GeminiService();
    private final RepositoryService repositories = new RepositoryService();

    public static AnalysisTaskService getInstance() { return INSTANCE; }

    public String submit(long userId, RepositoryData data) {
        String id = UUID.randomUUID().toString();
        Task task = new Task(id, userId, data);
        tasks.put(id, task);
        executor.submit(() -> run(task));
        return id;
    }

    public TaskView view(long userId, String taskId) {
        Task task = tasks.get(taskId);
        return task == null || task.userId != userId ? null : task.view();
    }

    private void run(Task task) {
        task.state = State.RUNNING;
        task.message = "The analysis agent is gathering repository evidence.";
        try {
            GeminiService.Insight insight = gemini.generate(task.data);
            RepositoryData enriched = new RepositoryData(task.data.url(), task.data.owner(), task.data.name(), task.data.description(), task.data.stars(), task.data.forks(), task.data.watchers(), task.data.openIssues(), task.data.defaultBranch(), task.data.updatedAt(), task.data.languages(), task.data.topics(), insight.readme(), insight.files(), insight.recentCommits());
            task.reportId = repositories.saveAnalysis(task.userId, enriched, insight);
            task.insight = insight;
            task.repository = enriched;
            task.message = "Analysis completed and saved to your private history.";
            task.state = State.SUCCEEDED;
        } catch (Exception e) {
            task.message = e.getMessage() == null ? "The analysis could not be completed." : e.getMessage();
            task.state = State.FAILED;
        }
    }

    private static final class Task {
        private final String id;
        private final long userId;
        private final RepositoryData data;
        private volatile State state = State.QUEUED;
        private volatile String message = "Your analysis is queued.";
        private volatile Long reportId;
        private volatile GeminiService.Insight insight;
        private volatile RepositoryData repository;
        private Task(String id, long userId, RepositoryData data) { this.id = id; this.userId = userId; this.data = data; }
        private TaskView view() { return new TaskView(id, state, message, reportId, insight, repository); }
    }
}
