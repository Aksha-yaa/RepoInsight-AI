
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

    public record Insight(String summary, String architecture, String recommendations) {}

    private final HttpClient client = HttpClient.newHttpClient();
    private final Gson gson = new Gson();
    private final GitHubService github = new GitHubService();

    public Insight generate(RepositoryData repo) throws ApiException {

        String key = Config.get("GEMINI_API_KEY", "");

        if (key.isBlank()) throw new ApiException("GEMINI_API_KEY is not configured. Add it to the environment.");

        // Prepare function declarations exactly as requested.
        JsonArray functionDeclarations = new JsonArray();

        functionDeclarations.add(new JsonObject());

        JsonObject f;

        f = new JsonObject();
        f.addProperty("name", "getFileTree");
        f.addProperty("description", "Returns the full file and folder structure of the repository.");
        f.add("parameters", new JsonObject());
        functionDeclarations.set(0, f);

        f = new JsonObject();
        f.addProperty("name", "getFileContents");
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
