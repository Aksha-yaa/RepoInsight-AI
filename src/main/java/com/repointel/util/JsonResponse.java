package com.repointel.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class JsonResponse {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private JsonResponse() { }
    public static void send(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        GSON.toJson(body, response.getWriter());
    }
}
