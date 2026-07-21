
  package com.repointel.service;

import com.google.gson.*;
import com.repointel.model.RepositoryData;
import com.repointel.util.ApiException;
import com.repointel.util.Config;

import java.net.URI;
import java.net.http.*;
import java.util.stream.Collectors;

public class GeminiService {

    public record Insight(String summary, String architecture, String recommendations) {}

    private final HttpClient client = HttpClient.newHttpClient();
    private final Gson gson = new Gson();

    public Insight generate(RepositoryData repo) throws ApiException {

        String key = Config.get("GEMINI_API_KEY", "");

        if (key.isBlank()) {
            throw new ApiException(
                "GEMINI_API_KEY is not configured. Add it to the environment."
            );
        }

        String prompt =
                "Analyze this public software repository. Return exactly three sections:\n" +
                "SUMMARY:\n" +
                "ARCHITECTURE:\n" +
                "RECOMMENDATIONS:\n\n" +

                "Repository: " + repo.owner() + "/" + repo.name() + "\n" +
                "Description: " + repo.description() + "\n" +
                "Languages: " + repo.languages() + "\n" +
                "Topics: " + repo.topics() + "\n" +
                "Files: " +
                repo.files().stream()
                        .limit(80)
                        .collect(Collectors.joining(", ")) +
                "\nRecent commits: " + repo.recentCommits() +
                "\nREADME:\n" +
                repo.readme()
                        .substring(0, Math.min(12000, repo.readme().length()));


        // Build Gemini request JSON
        JsonObject payload = new JsonObject();

        JsonArray contents = new JsonArray();
        JsonObject content = new JsonObject();

        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();

        part.addProperty("text", prompt);

        parts.add(part);
        content.add("parts", parts);

        contents.add(content);
        payload.add("contents", contents);


        try {

            String model = Config.get(
                    "GEMINI_MODEL",
                    "gemini-2.5-flash"
            );


            String url =
                    "https://generativelanguage.googleapis.com/v1beta/models/"
                    + model
                    + ":generateContent";


            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header(
                                "Content-Type",
                                "application/json"
                            )
                            .header(
                                "x-goog-api-key",
                                key
                            )
                            .POST(
                                HttpRequest.BodyPublishers.ofString(
                                    gson.toJson(payload)
                                )
                            )
                            .build();


            HttpResponse<String> response =
                    client.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                    );


            // Show actual Gemini error
            if (response.statusCode() >= 300) {

                throw new ApiException(
                    "Gemini API returned HTTP "
                    + response.statusCode()
                    + "\n\nResponse:\n"
                    + response.body()
                );
            }


            JsonObject json =
                    JsonParser.parseString(response.body())
                            .getAsJsonObject();


            JsonArray candidates =
                    json.getAsJsonArray("candidates");


            if (candidates == null || candidates.size() == 0) {

                throw new ApiException(
                    "Gemini returned no candidates:\n"
                    + response.body()
                );
            }


            String text =
                    candidates
                    .get(0)
                    .getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")
                    .get(0)
                    .getAsJsonObject()
                    .get("text")
                    .getAsString();


            return parse(text);


        }
        catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new ApiException(
                "Gemini request interrupted.",
                e
            );
        }

        catch (Exception e) {

            if (e instanceof ApiException api) {
                throw api;
            }

            throw new ApiException(
                "Could not generate Gemini insights.",
                e
            );
        }
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
