package edu.campusai.rag;

import java.util.List;

public final class RagTypes {
    private RagTypes() {}

    public record Material(String id, long courseId, String filename, String parseStatus,
                           boolean published, int version, int chunkCount, String errorMessage) {}

    public record Source(String chunkId, String materialId, long courseId, int version,
                         String filename, String text, Integer pageNumber,
                         Integer paragraphStart, Integer paragraphEnd, String heading) {}

    public record SearchHit(Source source, double score, double coverage) {}
    public record SearchResult(String status, List<SearchHit> hits) {}
    public record Answer(String status, String answer, List<Source> citations,
                         List<SearchHit> evidence, String reason) {}
    public record FileData(String filename, byte[] bytes) {}
}
