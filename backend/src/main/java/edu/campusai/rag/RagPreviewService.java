package edu.campusai.rag;

import edu.campusai.auth.DemoAccounts.Account;
import edu.campusai.rag.RagPreviewRepository.PreviewChunk;
import edu.campusai.rag.RagTypes.Material;
import edu.campusai.rag.RagTypes.Source;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagPreviewService {
    private final RagMaterialService materials;
    private final RagPreviewRepository repository;

    public RagPreviewService(RagMaterialService materials, RagPreviewRepository repository) {
        this.materials = materials;
        this.repository = repository;
    }

    public Preview preview(Account account, long courseId, String id, int version, int offset, int limit) {
        if (version < 1 || offset < 0 || limit < 1 || limit > 30) {
            throw new RagException(400, "version 必须为正整数，offset 不能为负数，limit 必须在 1 到 30 之间");
        }
        requireCurrent(materials.detail(account, courseId, id), version);
        List<PreviewChunk> rows = repository.page(courseId, id, version, offset, limit + 1);
        // Recheck course access and publication after reading. Keep these reads outside
        // a repeatable-read transaction so committed revocations/replacements are visible.
        requireCurrent(materials.detail(account, courseId, id), version);

        int count = Math.min(limit, rows.size());
        List<Source> sources = rows.subList(0, count).stream().map(PreviewChunk::source).toList();
        // The cursor is the last returned ordinal, not offset + limit: this also avoids
        // integer overflow and skips gaps without duplicating or losing chunks.
        Integer nextOffset = rows.size() > limit ? rows.get(count - 1).ordinal() : null;
        return new Preview(sources, nextOffset);
    }

    private static void requireCurrent(Material material, int version) {
        if (!material.published() || !"READY".equals(material.parseStatus())) {
            throw new RagException(404, "资料不存在或尚未发布");
        }
        if (material.version() != version) {
            throw new RagException(409, "资料版本已更新，请刷新后重试");
        }
    }

    public record Preview(List<Source> sources, Integer nextOffset) {}
}
