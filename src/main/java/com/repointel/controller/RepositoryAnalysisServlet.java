package com.repointel.controller;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.repointel.service.GitHubService;
import com.repointel.util.*;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;

@WebServlet("/api/repositories/analyze")
public class RepositoryAnalysisServlet extends HttpServlet {
    private final GitHubService github = new GitHubService();
    @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try { JsonObject body = JsonParser.parseReader(request.getReader()).getAsJsonObject(); JsonResponse.send(response, 200, github.analyze(body.get("repositoryUrl").getAsString())); }
        catch (ApiException e) { JsonResponse.send(response, 400, Map.of("error", e.getMessage())); }
        catch (Exception e) { JsonResponse.send(response, 500, Map.of("error", "Unable to analyze this repository.")); }
    }
}
