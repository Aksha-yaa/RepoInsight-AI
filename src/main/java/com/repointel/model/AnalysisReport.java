package com.repointel.model;

import java.util.List;
import java.util.Map;

public record AnalysisReport(long id, long repositoryId, String repositoryName, String repositoryUrl,
                             String summary, String architectureDetails, String technologyInsights,
                             String recommendations, String readme, List<String> files,
                             List<String> recentCommits, Map<String, String> fetchedFiles,
                             String generatedDate) { }
