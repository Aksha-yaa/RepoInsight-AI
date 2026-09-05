package com.repointel.service;

import com.google.gson.*;
import com.repointel.model.RepositoryData;
import com.repointel.util.ApiException;
import com.repointel.util.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

public class GeminiService {
    private static final Logger LOGGER = Logger.getLogger(GeminiService.class.getName());
    private static final int MAX_TURNS = 8;

    public record Insight(String summary, String architecture, String technologyInsights, String recommendations) { }

    private final HttpClient client = HttpClient.newHttpClient();
    private final Gson gson = new Gson();
    private final GitHubService github = new GitHubService();

    public Insight generate(RepositoryData repo) throws ApiException {
        String key = Config.get("GEMINI_API_KEY", "");
        if (key.isBlank()) throw new ApiException("GEMINI_API_KEY is not configured. Add it to the environment.");

        JsonArray conversation = new JsonArray();
        addUserText(conversation, "Analyze the public GitHub repository " + repo.owner() + "/" + repo.name()
                + ". Use the available functions to gather evidence one step at a time. Do not assume repository files "
                + "that you have not fetched. Call generateReport exactly once when ready. Return concise, evidence-based "
                + "content for all four report sections.");

        JsonArray tools = new JsonArray();
        JsonObject tool = new JsonObject();
        tool.add("functionDeclarations", functionDeclarations());
        tools.add(tool);

        java.util.List<String> fileTree = List.of();
        for (int turn = 0; turn < MAX_TURNS; turn++) {
            JsonObject payload = new JsonObject();
            payload.add("contents", conversation);
            payload.add("tools", tools);

            JsonObject response = callGemini(payload, key);
            JsonArray candidates = response.getAsJsonArray("candidates");
            if (candidates == null || candidates.isEmpty()) throw new ApiException("Gemini returned no candidates:\n" + response);

            JsonObject modelContent = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
            if (modelContent == null || !modelContent.has("parts")) throw new ApiException("Gemini returned no content parts.");
            conversation.add(modelContent);

            JsonObject functionCall = findFunctionCall(modelContent);
            if (functionCall == null) {
                addUserText(conversation, "Continue using the available functions. Do not answer with prose; call a function.");
                continue;
            }

            String functionName = functionCall.has("name") ? functionCall.get("name").getAsString() : "";
            JsonObject args = functionCall.has("args") && functionCall.get("args").isJsonObject()
                    ? functionCall.getAsJsonObject("args") : new JsonObject();

            if ("generateReport".equals(functionName)) return reportFrom(args, fileTree);

            JsonObject result = new JsonObject();
            try {
                switch (functionName) {
                    case "getFileTree" -> {
                        fileTree = github.getFileTree(repo.url());
                        result.add("result", gson.toJsonTree(fileTree));
                    }
                    case "getFileContents" -> {
                        if (!args.has("path") || args.get("path").getAsString().isBlank()) throw new ApiException("The path argument is required.");
                        result.addProperty("result", github.getFileContents(repo.url(), args.get("path").getAsString()));
                    }
                    case "getReadme" -> result.addProperty("result", github.getReadmeForRepo(repo.url()));
                    case "getRecentCommits" -> {
                        int limit = args.has("limit") ? args.get("limit").getAsInt() : 10;
                        result.add("result", gson.toJsonTree(github.getRecentCommits(repo.url(), limit)));
                    }
                    default -> result.addProperty("error", "Unknown function: " + functionName);
                }
            } catch (ApiException e) {
                result.addProperty("error", e.getMessage());
            } catch (Exception e) {
                result.addProperty("error", "Tool execution failed: " + e.getMessage());
            }
            addFunctionResponse(conversation, functionName, result);
        }

        LOGGER.warning("Gemini agent did not call generateReport within " + MAX_TURNS + " turns.");
        return new Insight("Partial analysis", "The analysis agent reached its tool-call limit before completing the report.", "Some repository evidence may not have been collected.", "Please run the analysis again. The agent was limited to " + MAX_TURNS + " steps.");
    }

    private JsonObject callGemini(JsonObject payload, String key) throws ApiException {
        try {
            String model = Config.get("GEMINI_MODEL", "gemini-2.5-flash");
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").header("x-goog-api-key", key).POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload))).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) throw new ApiException("Gemini API returned HTTP " + response.statusCode() + "\n\nResponse:\n" + response.body());
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Gemini request interrupted.", e);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("Could not generate Gemini insights.", e);
        }
    }

    private JsonArray functionDeclarations() {
        JsonArray declarations = new JsonArray();
        declarations.add(declaration("getFileTree", "Returns the full file and folder structure of the repository.", objectSchema(new JsonObject())));

        JsonObject fileProperties = new JsonObject();
        fileProperties.add("path", property("string", "Repository-relative file path."));
        declarations.add(declaration("getFileContents", "Returns the raw text contents of a single file at the given path.", objectSchema(fileProperties, "path")));
        declarations.add(declaration("getReadme", "Returns the repository's README content, if present.", objectSchema(new JsonObject())));

        JsonObject commitProperties = new JsonObject();
        commitProperties.add("limit", property("integer", "Number of commits, defaults to 10."));
        declarations.add(declaration("getRecentCommits", "Returns the most recent commit messages and timestamps.", objectSchema(commitProperties)));

        JsonObject reportProperties = new JsonObject();
        reportProperties.add("summary", property("string", null));
        reportProperties.add("architectureNotes", property("string", null));
        reportProperties.add("technologyInsights", property("string", null));
        reportProperties.add("recommendations", property("string", null));
        reportProperties.add("claimedHasTests", property("boolean", "Whether the report concludes the project has automated tests. Will be independently verified against the real file tree before saving."));
        declarations.add(declaration("generateReport", "Call exactly once, when ready to produce the final report. Ends the loop.", objectSchema(reportProperties, "summary", "architectureNotes", "technologyInsights", "recommendations", "claimedHasTests")));
        return declarations;
    }

    private JsonObject declaration(String name, String description, JsonObject parameters) {
        JsonObject declaration = new JsonObject();
        declaration.addProperty("name", name);
        declaration.addProperty("description", description);
        declaration.add("parameters", parameters);
        return declaration;
    }

    private JsonObject objectSchema(JsonObject properties, String... requiredNames) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        for (String name : requiredNames) required.add(name);
        schema.add("required", required);
        return schema;
    }

    private JsonObject property(String type, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", type);
        if (description != null) property.addProperty("description", description);
        return property;
    }

    // Gemini uses candidates[].content.parts[].functionCall and functionCall.args.
    private JsonObject findFunctionCall(JsonObject content) {
        for (JsonElement partElement : content.getAsJsonArray("parts")) {
            JsonObject part = partElement.getAsJsonObject();
            if (part.has("functionCall")) return part.getAsJsonObject("functionCall");
        }
        return null;
    }

    private void addFunctionResponse(JsonArray conversation, String name, JsonObject result) {
        JsonObject content = new JsonObject();
        content.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        JsonObject functionResponse = new JsonObject();
        functionResponse.addProperty("name", name);
        functionResponse.add("response", result);
        part.add("functionResponse", functionResponse);
        parts.add(part);
        content.add("parts", parts);
        conversation.add(content);
    }

    private void addUserText(JsonArray conversation, String text) {
        JsonObject content = new JsonObject();
        content.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        parts.add(part);
        content.add("parts", parts);
        conversation.add(content);
    }

    private Insight reportFrom(JsonObject args, java.util.List<String> fileTree) {
        String summary = clean(args, "summary");
        String architecture = clean(args, "architectureNotes");
        String technology = clean(args, "technologyInsights");
        String recommendations = clean(args, "recommendations");
        boolean claimedHasTests = args.has("claimedHasTests") && args.get("claimedHasTests").getAsBoolean();
        if (claimedHasTests && !containsTestPath(fileTree)) recommendations = appendNote(recommendations, "The report claimed automated tests, but the fetched file tree contains no test/, tests/, or __test__ path.");
        return new Insight(summary, architecture, technology, recommendations);
    }

    private boolean containsTestPath(java.util.List<String> paths) {
        for (String path : paths) {
            String normalized = path.toLowerCase(Locale.ROOT).replace('\\', '/');
            if (normalized.startsWith("test/") || normalized.startsWith("tests/") || normalized.contains("/test/") || normalized.contains("/tests/") || normalized.contains("__test__") || normalized.contains("__tests__")) return true;
        }
        return false;
    }

    private String clean(JsonObject object, String key) {
        return object.has(key) ? normalize(object.get(key).getAsString()) : "No information was provided.";
    }

    private String normalize(String value) {
        return value.replace("**", "").replace("__", "").replace("\u2014", "-").replace("\u2013", "-").replaceAll("(?m)^#{1,6}\\s*", "").replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private String appendNote(String text, String note) {
        return text + (text.isBlank() ? "" : "\n\n") + "Verification note: " + note;
    }
}
  package com.repointel.service;

import com.google.gson.*;
import com.repointel.model.RepositoryData;
import com.repointel.util.ApiException;
import com.repointel.util.Config;

import java.net.URI;
import java.net.http.*;
import java.util.stream.Collectors;
import java.util.*;

public class GeminiService {

    public record Insight(String summary, String architecture, String technologyInsights, String recommendations) {}

    private final HttpClient client = HttpClient.newHttpClient();
    private final Gson gson = new Gson();
    private final GitHubService github = new GitHubService();

    public Insight generate(RepositoryData repo) throws ApiException {

        String key = Config.get("GEMINI_API_KEY", "");

        if (key.isBlank()) throw new ApiException("GEMINI_API_KEY is not configured. Add it to the environment.");

        // Prepare function declarations exactly as requested.
        JsonArray functionDeclarations = new JsonArray();
        JsonArray conversation = new JsonArray();
        JsonObject firstUser = new JsonObject();
        firstUser.addProperty("role", "user");
        JsonArray firstParts = new JsonArray();
        JsonObject firstText = new JsonObject();
        firstText.addProperty("text", "Analyze the public GitHub repository " + repo.owner() + "/" + repo.name()
                + ". Use the available functions to gather evidence one step at a time. Do not assume repository files "
                + "that you have not fetched. Call generateReport exactly once when ready. Return concise, evidence-based "
                + "content for all four report sections.");
        firstParts.add(firstText);
        firstUser.add("parts", firstParts);
        conversation.add(firstUser);
        JsonObject f;

        f = new JsonObject();
        f.addProperty("name", "getFileTree");
        f.addProperty("description", "Returns the full file and folder structure of the repository.");
        f.add("parameters", new JsonObject());
        functionDeclarations.set(0, f);

        f = new JsonObject();
            payload.add("contents", conversation);
        f.addProperty("description", "Returns the raw text contents of a single file at the given path.");
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject pathProp = new JsonObject();
        pathProp.addProperty("type", "string");
        pathProp.addProperty("description", "Repository-relative file path.");
        props.add("path", pathProp);
        params.add("properties", props);
        JsonArray required = new JsonArray(); required.add("path"); params.add("required", required);
        f.add("parameters", params);
        functionDeclarations.add(f);

        f = new JsonObject();
        f.addProperty("name", "getReadme");
        f.addProperty("description", "Returns the repository's README content, if present.");
        f.add("parameters", new JsonObject());
        functionDeclarations.add(f);

        f = new JsonObject();
        f.addProperty("name", "getRecentCommits");
        f.addProperty("description", "Returns the most recent commit messages and timestamps.");
        JsonObject p2 = new JsonObject(); p2.addProperty("type", "object");
        JsonObject p2props = new JsonObject();
        JsonObject limitProp = new JsonObject(); limitProp.addProperty("type", "integer"); limitProp.addProperty("description", "Number of commits, defaults to 10.");
        p2props.add("limit", limitProp); p2.add("properties", p2props); p2.add("required", new JsonArray());
        f.add("parameters", p2);
        functionDeclarations.add(f);

        f = new JsonObject();
        f.addProperty("name", "generateReport");
        f.addProperty("description", "Call exactly once, when ready to produce the final report. Ends the loop.");
        JsonObject p3 = new JsonObject(); p3.addProperty("type", "object");
        JsonObject p3props = new JsonObject();
        JsonObject s = new JsonObject(); s.addProperty("type", "string"); p3props.add("summary", s);
        JsonObject a = new JsonObject(); a.addProperty("type", "string"); p3props.add("architectureNotes", a);
        JsonObject t = new JsonObject(); t.addProperty("type", "string"); p3props.add("technologyInsights", t);
        JsonObject r = new JsonObject(); r.addProperty("type", "string"); p3props.add("recommendations", r);
        JsonObject ch = new JsonObject(); ch.addProperty("type", "boolean"); ch.addProperty("description", "Whether the report concludes the project has automated tests. Will be independently verified against the real file tree before saving."); p3props.add("claimedHasTests", ch);
        p3.add("properties", p3props);
        JsonArray p3req = new JsonArray(); p3req.add("summary"); p3req.add("architectureNotes"); p3req.add("technologyInsights"); p3req.add("recommendations"); p3req.add("claimedHasTests"); p3.add("required", p3req);
        f.add("parameters", p3);
        functionDeclarations.add(f);

        // Conversation state: we append assistant function responses as plain text parts when replying.
        String ownerName = repo.owner() + "/" + repo.name();
        String repoUrl = repo.url();

        String initialUser = "You are an analysis agent. Use only the provided functions to fetch repository data (do not assume any pre-fetched files). Repository: " + ownerName + ". When you need data, call the appropriate function with JSON arguments. When ready to finish, call generateReport exactly once.\n";

        // Keep the last retrieved file tree to verify test claim later.
        List<String> lastFileTree = List.of();

        String assistantFunctionResponse = null; // JSON encoded function result to include back to the model

        int maxTurns = 8;
        for (int turn = 0; turn < maxTurns; turn++) {
            JsonObject payload = new JsonObject();
            payload.add("functionDeclarations", functionDeclarations);

            JsonArray contents = new JsonArray();
            JsonObject content = new JsonObject();
            JsonArray parts = new JsonArray();

            JsonObject userPart = new JsonObject();
            String prompt = initialUser + "\nRepositoryUrl: " + repoUrl + "\n" + (assistantFunctionResponse == null ? "" : "Previous function response:\n" + assistantFunctionResponse + "\n");
            userPart.addProperty("text", prompt);
            parts.add(userPart);

            content.add("parts", parts);
            contents.add(content);
            payload.add("contents", contents);

            try {
                String model = Config.get("GEMINI_MODEL", "gemini-2.5-flash");
                String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
                HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("Content-Type", "application/json").header("x-goog-api-key", key).POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload))).build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 300) throw new ApiException("Gemini API returned HTTP " + response.statusCode() + "\n\nResponse:\n" + response.body());

                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonArray candidates = json.getAsJsonArray("candidates");
                if (candidates == null || candidates.size() == 0) throw new ApiException("Gemini returned no candidates:\n" + response.body());

                JsonObject candidate = candidates.get(0).getAsJsonObject();
                JsonObject contentObj = candidate.has("content") ? candidate.getAsJsonObject("content") : null;

                // Detect structured function call information in candidate or content
                JsonObject funcCall = null;
                if (candidate.has("function_call")) funcCall = candidate.getAsJsonObject("function_call");
                else if (contentObj != null && contentObj.has("function_call")) funcCall = contentObj.getAsJsonObject("function_call");
                else if (candidate.has("toolInvocation")) funcCall = candidate.getAsJsonObject("toolInvocation");
                else if (contentObj != null && contentObj.has("toolInvocation")) funcCall = contentObj.getAsJsonObject("toolInvocation");

                if (funcCall == null) {
                    // No function call: treat textual reply, include as assistant response and continue
                    String text = "";
                    try {
                        text = contentObj.getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString();
                    } catch (Exception ignored) { text = candidate.toString(); }
                    // Provide the textual response back as assistantFunctionResponse so model can continue
                    assistantFunctionResponse = gson.toJson(Map.of("assistant_text", text));
                    continue; // go to next turn
                }

                String functionName = funcCall.has("name") ? funcCall.get("name").getAsString() : null;
                JsonObject arguments = null;
                if (funcCall.has("arguments")) {
                    JsonElement argElem = funcCall.get("arguments");
                    try {
                        if (argElem.isJsonObject()) arguments = argElem.getAsJsonObject();
                        else if (argElem.isJsonPrimitive()) arguments = JsonParser.parseString(argElem.getAsString()).getAsJsonObject();
                    } catch (Exception e) { arguments = new JsonObject(); }
                } else { arguments = new JsonObject(); }

                // Execute the requested tool
                JsonObject toolResult = new JsonObject();
                try {
                    switch (functionName) {
                        case "getFileTree": {
                            List<String> tree = github.getFileTree(repo.url());
                            lastFileTree = tree;
                            toolResult.add("result", gson.toJsonTree(tree));
                            break;
                        }
                        case "getFileContents": {
                            String path = arguments.has("path") ? arguments.get("path").getAsString() : "";
                            String contentsStr = github.getFileContents(repo.url(), path);
                            toolResult.addProperty("result", contentsStr);
                            break;
                        }
                        case "getReadme": {
                            String rd = github.getReadmeForRepo(repo.url());
                            toolResult.addProperty("result", rd);
                            break;
                        }
                        case "getRecentCommits": {
                            int limit = arguments.has("limit") ? arguments.get("limit").getAsInt() : 10;
                            List<String> commits = github.getRecentCommits(repo.url(), limit);
                            toolResult.add("result", gson.toJsonTree(commits));
                            break;
                        }
                        case "generateReport": {
                            // Final step: parse report fields and return Insight after verification
                            String summary = arguments.has("summary") ? arguments.get("summary").getAsString() : "";
                            String architectureNotes = arguments.has("architectureNotes") ? arguments.get("architectureNotes").getAsString() : "";
                            String technologyInsights = arguments.has("technologyInsights") ? arguments.get("technologyInsights").getAsString() : "";
                            String recommendations = arguments.has("recommendations") ? arguments.get("recommendations").getAsString() : "";
                            boolean claimedHasTests = arguments.has("claimedHasTests") && arguments.get("claimedHasTests").getAsBoolean();

                            // Verify test/ presence in last fetched file tree
                            boolean hasTests = false;
                            for (String pth : lastFileTree) {
                                String lower = pth.toLowerCase(Locale.ROOT);
                                if (lower.startsWith("test/") || lower.startsWith("tests/") || lower.contains("/__test__/") || lower.contains("/__tests__/")) { hasTests = true; break; }
                            }
                            if (claimedHasTests && !hasTests) {
                                recommendations = recommendations + "\n\nNOTE: The model claimed automated tests are present, but no matching test/, tests/, or __test__ files were found in the repository file tree.";
                            }

                            return new Insight(summary, architectureNotes + "\n\n" + technologyInsights, recommendations);
                        }
                        default: {
                            toolResult.addProperty("error", "Unknown function: " + functionName);
                            break;
                        }
                    }
                } catch (ApiException ae) {
                    toolResult.addProperty("error", ae.getMessage());
                } catch (Exception e) {
                    toolResult.addProperty("error", "Tool execution error: " + e.getMessage());
                }

                // Send toolResult back to the model as assistant function response for next turn
                assistantFunctionResponse = toolResult.toString();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException("Gemini request interrupted.", e);
            } catch (Exception e) {
                if (e instanceof ApiException api) throw api;
                throw new ApiException("Could not generate Gemini insights.", e);
            }
        }

        // If loop hits the cap without generateReport, return a partial report with warning.
        System.err.println("Gemini agent did not call generateReport within " + maxTurns + " turns.");
        String warning = "Partial analysis: the agent did not finalize within the allowed number of steps (" + maxTurns + ").";
        return new Insight("Partial analysis", "", warning);
    }


    private Insight parse(String text) {

        String[] architectureSplit =
                text.split(
                    "(?i)ARCHITECTURE:",
                    2
                );


        String[] recommendationSplit =
                architectureSplit.length > 1
                ?
                architectureSplit[1]
                    .split(
                        "(?i)RECOMMENDATIONS:",
                        2
                    )
                :
                new String[]{""};



        return new Insight(

            architectureSplit[0]
                .replaceFirst(
                    "(?i)^\\s*SUMMARY:\\s*",
                    ""
                )
                .trim(),


            recommendationSplit[0]
                .trim(),


            recommendationSplit.length > 1
            ?
            recommendationSplit[1].trim()
            :
            "Review the generated analysis for next steps."
        );
    }
}
