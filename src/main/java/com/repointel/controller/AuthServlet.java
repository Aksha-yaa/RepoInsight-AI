package com.repointel.controller;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.repointel.service.AuthService;
import com.repointel.util.JsonResponse;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;

@WebServlet("/api/auth/*")
public class AuthServlet extends HttpServlet {
    private final Gson gson = new Gson();
    private final AuthService auth = new AuthService();

    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Long userId = userId(request);
        if (userId == null) { JsonResponse.send(response, 401, Map.of("error", "Sign in to continue.")); return; }
        JsonResponse.send(response, 200, Map.of("authenticated", true, "username", request.getSession(false).getAttribute("username"), "email", request.getSession(false).getAttribute("email")));
    }

    @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String path = request.getPathInfo();
            JsonObject body = JsonParser.parseReader(request.getReader()).getAsJsonObject();
            if ("/register".equals(path)) {
                AuthService.Account account = auth.register(value(body, "username"), value(body, "email"), value(body, "password"));
                startSession(request, account);
                JsonResponse.send(response, 201, accountResponse(account));
            } else if ("/login".equals(path)) {
                AuthService.Account account = auth.login(value(body, "email"), value(body, "password"));
                startSession(request, account);
                JsonResponse.send(response, 200, accountResponse(account));
            } else if ("/logout".equals(path)) {
                HttpSession session = request.getSession(false);
                if (session != null) session.invalidate();
                JsonResponse.send(response, 200, Map.of("authenticated", false));
            } else {
                JsonResponse.send(response, 404, Map.of("error", "Authentication endpoint not found."));
            }
        } catch (IllegalArgumentException e) {
            JsonResponse.send(response, 400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            JsonResponse.send(response, 500, Map.of("error", "Authentication is temporarily unavailable."));
        }
    }

    private void startSession(HttpServletRequest request, AuthService.Account account) {
        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval(60 * 60 * 24 * 7);
        session.setAttribute("userId", account.id());
        session.setAttribute("username", account.username());
        session.setAttribute("email", account.email());
    }

    private Map<String, Object> accountResponse(AuthService.Account account) {
        return Map.of("authenticated", true, "username", account.username(), "email", account.email(), "demoUsed", account.demoUsed());
    }

    private String value(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : "";
    }

    public static Long userId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute("userId");
        return value instanceof Long id ? id : null;
    }
}
