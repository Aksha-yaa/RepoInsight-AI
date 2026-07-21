package com.repointel.controller;

import com.google.gson.Gson;
import com.repointel.model.RepositoryData;
import com.repointel.service.*;
import com.repointel.util.*;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;

@WebServlet("/api/insights/generate")
public class GeminiAnalysisServlet extends HttpServlet {
    private final Gson gson = new Gson(); private final GeminiService gemini = new GeminiService(); private final RepositoryService repositories = new RepositoryService();
    @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try { RepositoryData data = gson.fromJson(request.getReader(), RepositoryData.class); GeminiService.Insight insight = gemini.generate(data); long reportId = repositories.saveAnalysis(data, insight); JsonResponse.send(response, 200, Map.of("reportId", reportId, "insight", insight)); }
        catch (ApiException e) { JsonResponse.send(response, 400, Map.of("error", e.getMessage())); }
        catch (Exception e) { JsonResponse.send(response, 500, Map.of("error", "Insights could not be saved. Confirm the database is available.")); }
    }
}
