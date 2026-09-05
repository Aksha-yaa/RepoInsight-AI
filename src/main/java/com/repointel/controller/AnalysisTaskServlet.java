package com.repointel.controller;

import com.repointel.service.AnalysisTaskService;
import com.repointel.util.JsonResponse;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;

@WebServlet("/api/insights/tasks/*")
public class AnalysisTaskServlet extends HttpServlet {
    private final AnalysisTaskService tasks = AnalysisTaskService.getInstance();

    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Long userId = AuthServlet.userId(request);
        if (userId == null) { JsonResponse.send(response, 401, Map.of("error", "Sign in to view this analysis.")); return; }
        String path = request.getPathInfo();
        AnalysisTaskService.TaskView task = path == null ? null : tasks.view(userId, path.substring(1));
        if (task == null) { JsonResponse.send(response, 404, Map.of("error", "Analysis task not found.")); return; }
        JsonResponse.send(response, task.state() == AnalysisTaskService.State.FAILED ? 500 : 200, task);
    }
}
