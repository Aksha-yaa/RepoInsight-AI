package com.repointel.model;

import java.util.List;
import java.util.Map;

public record RepositoryData(String url, String owner, String name, String description, long stars,
                             long forks, long watchers, long openIssues, String defaultBranch, String updatedAt,
                             Map<String, Integer> languages, List<String> topics, String readme,
                             List<String> files, List<String> recentCommits) { }
