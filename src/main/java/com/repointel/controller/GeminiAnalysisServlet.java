package com.repointel.controller;

import com.google.gson.Gson;
import com.repointel.model.RepositoryData;
import com.repointel.service.AnalysisTaskService;
import com.repointel.util.JsonResponse;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;

@WebServlet("/api/insights/generate")
public class GeminiAnalysisServlet extends HttpServlet {
    private final Gson gson = new Gson();
    private final AnalysisTaskService tasks = AnalysisTaskService.getInstance();

    @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Long userId = AuthServlet.userId(request);
        if (userId == null) { JsonResponse.send(response, 401, Map.of("error", "Sign in to generate a report.")); return; }
        try {
            RepositoryData data = gson.fromJson(request.getReader(), RepositoryData.class);
            String taskId = tasks.submit(userId, data);
            JsonResponse.send(response, 202, Map.of("taskId", taskId, "state", AnalysisTaskService.State.QUEUED.name(), "message", "Your analysis is queued."));
        } catch (Exception e) {
            JsonResponse.send(response, 400, Map.of("error", "The analysis request is invalid."));
        }
    }
}
