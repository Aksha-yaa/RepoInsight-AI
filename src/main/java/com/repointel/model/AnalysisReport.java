package com.repointel.model;

public record AnalysisReport(long id, long repositoryId, String repositoryName, String repositoryUrl,
                             String summary, String architectureDetails, String recommendations,
                             String generatedDate) { }
