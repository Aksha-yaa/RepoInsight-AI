package com.repointel.service;

import com.google.gson.*;
import com.repointel.model.RepositoryData;
import com.repointel.util.ApiException;
import com.repointel.util.Config;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class GitHubService {
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Gson gson = new Gson();
    private final String token = Config.get("GITHUB_TOKEN", "");

    public RepositoryData analyze(String repositoryUrl) throws ApiException {
        String[] parts = parseUrl(repositoryUrl);
        String owner = parts[0], name = parts[1], base = "https://api.github.com/repos/" + owner + "/" + name;
        JsonObject repository = getObject(base);
        Map<String, Integer> languages = new LinkedHashMap<>();
        getObject(base + "/languages").entrySet().forEach(e -> languages.put(e.getKey(), e.getValue().getAsInt()));
        List<String> topics = new ArrayList<>();
        JsonArray topicArray = repository.has("topics") ? repository.getAsJsonArray("topics") : new JsonArray();
        topicArray.forEach(t -> topics.add(t.getAsString()));
        String readme = getReadme(base);
        List<String> files = getFiles(base, repository.get("default_branch").getAsString());
        List<String> commits = getCommits(base);
        return new RepositoryData(repository.get("html_url").getAsString(), owner, name,
                nullableString(repository, "description"), repository.get("stargazers_count").getAsLong(),
                repository.get("forks_count").getAsLong(), repository.get("subscribers_count").getAsLong(),
                repository.get("open_issues_count").getAsLong(), repository.get("default_branch").getAsString(),
                repository.get("updated_at").getAsString(), languages, topics, readme, files, commits);
    }

    private String[] parseUrl(String input) throws ApiException {
        try {
            String candidate = input.trim();
            if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) candidate = "https://github.com/" + candidate;
            URI uri = URI.create(candidate.replaceAll("/$", ""));
            if (!"github.com".equalsIgnoreCase(uri.getHost()) && !"www.github.com".equalsIgnoreCase(uri.getHost())) throw new ApiException("Enter a public github.com repository URL.");
            String[] path = uri.getPath().replaceFirst("^/", "").split("/");
            if (path.length < 2 || path[0].isBlank() || path[1].isBlank()) throw new ApiException("The repository URL must include an owner and repository name.");
            return new String[]{path[0], path[1].replaceFirst("\\.git$", "")};
        } catch (IllegalArgumentException e) { throw new ApiException("Enter a valid GitHub repository URL.", e); }
    }
    private JsonObject getObject(String url) throws ApiException { return request(url).getAsJsonObject(); }
    private JsonElement request(String url) throws ApiException {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").GET();
            if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) throw new ApiException("Repository not found, private, or unavailable.");
            if (response.statusCode() == 403) throw new ApiException("GitHub rate limit reached. Add GITHUB_TOKEN to continue.");
            if (response.statusCode() >= 300) throw new ApiException("GitHub API returned HTTP " + response.statusCode());
            return JsonParser.parseString(response.body());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiException("GitHub request interrupted.", e); }
          catch (Exception e) { if (e instanceof ApiException api) throw api; throw new ApiException("Could not reach GitHub API.", e); }
    }
    private String getReadme(String base) {
        try { JsonObject value = getObject(base + "/readme"); return new String(Base64.getMimeDecoder().decode(value.get("content").getAsString()), StandardCharsets.UTF_8); }
        catch (Exception ignored) { return "No README was available."; }
    }
    private List<String> getFiles(String base, String branch) {
        try {
            JsonArray tree = request(base + "/git/trees/" + branch + "?recursive=1").getAsJsonObject().getAsJsonArray("tree");
            List<String> files = new ArrayList<>(); for (JsonElement item : tree) if ("blob".equals(item.getAsJsonObject().get("type").getAsString())) files.add(item.getAsJsonObject().get("path").getAsString());
            return files.stream().limit(200).toList();
        } catch (Exception ignored) { return List.of(); }
    }
    private List<String> getCommits(String base) {
        try { JsonArray arr = request(base + "/commits?per_page=8").getAsJsonArray(); List<String> commits = new ArrayList<>(); for (JsonElement e : arr) commits.add(e.getAsJsonObject().getAsJsonObject("commit").get("message").getAsString().split("\\R")[0]); return commits; }
        catch (Exception ignored) { return List.of(); }
    }
    private String nullableString(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "No description provided."; }
}
