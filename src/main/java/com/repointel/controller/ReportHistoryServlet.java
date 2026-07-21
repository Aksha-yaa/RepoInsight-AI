package com.repointel.controller;

import com.repointel.service.RepositoryService;
import com.repointel.util.JsonResponse;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;

@WebServlet("/api/reports/*")
public class ReportHistoryServlet extends HttpServlet {
    private final RepositoryService repositories = new RepositoryService();
    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try { String path = request.getPathInfo(); if (path == null || "/".equals(path)) JsonResponse.send(response, 200, repositories.history()); else { long id = Long.parseLong(path.substring(1)); repositories.find(id).ifPresentOrElse(report -> { try { JsonResponse.send(response, 200, report); } catch (IOException e) { throw new RuntimeException(e); } }, () -> { try { JsonResponse.send(response, 404, Map.of("error", "Report not found.")); } catch (IOException e) { throw new RuntimeException(e); } }); } }
        catch (Exception e) { JsonResponse.send(response, 500, Map.of("error", "Report history is unavailable.")); }
    }
}
