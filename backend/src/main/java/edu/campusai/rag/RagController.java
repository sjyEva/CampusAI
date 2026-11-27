package edu.campusai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import edu.campusai.auth.DemoAccounts;
import edu.campusai.auth.DemoAccounts.Account;
import edu.campusai.rag.RagTypes.FileData;
import edu.campusai.web.ApiResult;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/campusai/courses/{courseId}/rag")
public class RagController {
    private final DemoAccounts accounts;
    private final RagAccess access;
    private final RagMaterialService materials;
    private final RagService rag;
    private final RagPreviewService previews;

    public RagController(DemoAccounts accounts, RagAccess access, RagMaterialService materials, RagService rag,
                          RagPreviewService previews) {
        this.accounts = accounts;
        this.access = access;
        this.materials = materials;
        this.rag = rag;
        this.previews = previews;
    }

    @GetMapping("/materials")
    public ApiResult list(Authentication principal, @PathVariable long courseId,
                          @RequestParam(required = false) Long chapterId) {
        return ok(materials.list(account(principal), courseId, chapterId));
    }

    @GetMapping("/capabilities")
    public ApiResult capabilities(Authentication principal, @PathVariable long courseId) {
        return ok(rag.capabilities(account(principal), courseId));
    }

    @GetMapping("/materials/{id}")
    public ApiResult detail(Authentication principal, @PathVariable long courseId, @PathVariable String id) {
        return ok(materials.detail(account(principal), courseId, id));
    }

    @PostMapping(value = "/materials", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult upload(Authentication principal, @PathVariable long courseId, @RequestParam MultipartFile file,
                            @RequestParam(required = false) Long chapterId) {
        Account account = account(principal);
        access.manage(account, courseId);
        return ok(materials.upload(account, courseId, file.getOriginalFilename(), bytes(file), chapterId));
    }

    @PatchMapping("/materials/{id}/chapter")
    public ApiResult assignChapter(Authentication principal, @PathVariable long courseId, @PathVariable String id,
                                    @RequestBody ChapterAssignment request) {
        if (request == null || request.chapterId() == null || request.expectedVersion() == null
                || !request.expectedVersion().isIntegralNumber() || !request.expectedVersion().canConvertToInt()
                || request.expectedVersion().intValue() < 1) {
            throw new RagException(400, "必须指定 chapterId（正整数或 null）和 expectedVersion 正整数");
        }
        JsonNode chapter = request.chapterId();
        if (!chapter.isNull() && (!chapter.isIntegralNumber() || !chapter.canConvertToLong() || chapter.longValue() < 1)) {
            throw new RagException(400, "chapterId 必须是正整数或 null");
        }
        return ok(materials.assignChapter(account(principal), courseId, id,
                chapter.isNull() ? null : chapter.longValue(), request.expectedVersion().intValue()));
    }

    @PutMapping(value = "/materials/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult replace(Authentication principal, @PathVariable long courseId, @PathVariable String id,
                             @RequestParam MultipartFile file, @RequestParam(required = false) Integer version) {
        Account account = account(principal);
        access.manage(account, courseId);
        return ok(materials.replace(account, courseId, id, file.getOriginalFilename(), bytes(file), version));
    }

    @PostMapping("/materials/{id}/reprocess")
    public ApiResult reprocess(Authentication principal, @PathVariable long courseId, @PathVariable String id,
                               @RequestParam(required = false) Integer version) {
        return ok(materials.reprocess(account(principal), courseId, id, version));
    }

    @PatchMapping("/materials/{id}/publication")
    public ApiResult publish(Authentication principal, @PathVariable long courseId, @PathVariable String id, @RequestBody Publication request) {
        if (request == null || request.published() == null || !request.published().isBoolean()) {
            throw new RagException(400, "必须指定 published 布尔值");
        }
        Integer expectedVersion = null;
        if (request.expectedVersion() != null) {
            if (!request.expectedVersion().isIntegralNumber() || !request.expectedVersion().canConvertToInt()
                    || request.expectedVersion().intValue() < 1) throw new RagException(400, "expectedVersion 必须是正整数");
            expectedVersion = request.expectedVersion().intValue();
        }
        return ok(materials.publish(account(principal), courseId, id, request.published().booleanValue(), expectedVersion));
    }

    @DeleteMapping("/materials/{id}")
    public ApiResult delete(Authentication principal, @PathVariable long courseId, @PathVariable String id,
                            @RequestParam(required = false) Integer version) {
        materials.delete(account(principal), courseId, id, version);
        return ApiResult.success();
    }

    @GetMapping("/materials/{id}/file")
    public ResponseEntity<byte[]> file(Authentication principal, @PathVariable long courseId, @PathVariable String id,
                                       @RequestParam(required = false) Integer version) {
        FileData file = materials.file(account(principal), courseId, id, version);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Material-Version", Integer.toString(file.version()))
                .header("X-Content-Type-Options", "nosniff").body(file.bytes());
    }

    @GetMapping("/materials/{id}/preview")
    public ResponseEntity<ApiResult> preview(Authentication principal, @PathVariable long courseId,
                                             @PathVariable String id, @RequestParam int version,
                                             @RequestParam(defaultValue = "0") int offset,
                                             @RequestParam(defaultValue = "12") int limit) {
        RagPreviewService.Preview preview = previews.preview(account(principal), courseId, id, version, offset, limit);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(ok(preview));
    }

    @GetMapping("/search")
    public ApiResult search(Authentication principal, @PathVariable long courseId, @RequestParam String q,
                            @RequestParam(defaultValue = "3") int topK) {
        return ok(rag.search(account(principal), courseId, q, topK));
    }

    @GetMapping("/sources/{chunkId}")
    public ApiResult source(Authentication principal, @PathVariable long courseId, @PathVariable String chunkId) {
        return ok(rag.source(account(principal), courseId, chunkId));
    }

    @PostMapping("/ask")
    public ApiResult ask(Authentication principal, @PathVariable long courseId, @RequestBody Question request) {
        if (request == null || request.question() == null || !request.question().isTextual()) {
            throw new RagException(400, "question 必须是非空字符串");
        }
        return ok(rag.ask(account(principal), courseId, request.question().textValue()));
    }

    private Account account(Authentication principal) { return accounts.find(principal.getName()); }
    private static ApiResult ok(Object data) { return ApiResult.success().with("data", data); }
    private static byte[] bytes(MultipartFile file) {
        if (file.getSize() == 0 || file.getSize() > DocumentParser.MAX_FILE_BYTES) throw new RagException(400, "请选择不超过 10MB 的非空资料文件");
        try { return file.getBytes(); }
        catch (IOException ex) { throw new RagException(400, "无法读取上传文件，请重试"); }
    }
    public record Publication(JsonNode published, JsonNode expectedVersion) {}
    public record Question(JsonNode question) {}
    public record ChapterAssignment(JsonNode chapterId, JsonNode expectedVersion) {}
}
