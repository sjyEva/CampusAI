package edu.campusai.rag;

import edu.campusai.auth.DemoAccounts.Account;
import edu.campusai.rag.RagRepository.StoredMaterial;
import edu.campusai.rag.RagTypes.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class RagMaterialService {
    public static final String PARSER_VERSION = "office-v2-smartcn-v1";
    private final RagAccess access;
    private final RagRepository repository;
    private final DocumentParser parser;
    private final Bm25Engine engine;

    public RagMaterialService(RagAccess access, RagRepository repository, DocumentParser parser, Bm25Engine engine) {
        this.access = access;
        this.repository = repository;
        this.parser = parser;
        this.engine = engine;
    }

    public List<Material> list(Account account, long courseId) {
        access.read(account, courseId);
        return repository.list(courseId, !account.role().equals("student"));
    }

    public Material detail(Account account, long courseId, String id) {
        access.read(account, courseId);
        Material material = repository.material(courseId, id);
        if (account.role().equals("student") && (!material.published() || !material.parseStatus().equals("READY"))) {
            throw new RagException(404, "资料不存在或尚未发布");
        }
        return material;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Material upload(Account account, long courseId, String filename, byte[] content) {
        access.manage(account, courseId);
        validateUpload(filename, content);
        String id = UUID.randomUUID().toString();
        repository.create(id, courseId, filename, content, sha(content));
        return process(repository.stored(courseId, id, true), filename, content);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Material replace(Account account, long courseId, String id, String filename, byte[] content) {
        return replace(account, courseId, id, filename, content, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Material replace(Account account, long courseId, String id, String filename, byte[] content, Integer expectedVersion) {
        access.manage(account, courseId);
        validateUpload(filename, content);
        StoredMaterial stored = repository.stored(courseId, id, true);
        checkVersion(stored, expectedVersion);
        return process(stored, filename, content);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Material reprocess(Account account, long courseId, String id) {
        return reprocess(account, courseId, id, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Material reprocess(Account account, long courseId, String id, Integer expectedVersion) {
        access.manage(account, courseId);
        StoredMaterial stored = repository.stored(courseId, id, true);
        checkVersion(stored, expectedVersion);
        return process(stored, stored.filename(), stored.content());
    }

    private Material process(StoredMaterial previous, String filename, byte[] bytes) {
        String digest = sha(bytes);
        if (previous.parseStatus().equals("READY") && previous.sha256().equals(digest)
                && previous.filename().equals(filename) && previous.parserVersion().equals(PARSER_VERSION)) {
            return repository.material(previous.courseId(), previous.id());
        }
        int version = previous.version() + 1;
        repository.begin(previous.id(), filename, bytes, digest, version, PARSER_VERSION);
        List<DocumentParser.ParsedChunk> chunks;
        try {
            chunks = parser.parse(filename, bytes);
            if (chunks.stream().allMatch(chunk -> engine.tokenize(chunk.text()).isEmpty())) {
                throw new DocumentParser.ParsingException("资料没有可用于检索的文本词项");
            }
        } catch (DocumentParser.ParsingException ex) {
            repository.finish(previous.id(), "FAILED", ex.getMessage());
            return repository.material(previous.courseId(), previous.id());
        }
        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = UUID.nameUUIDFromBytes((previous.id() + ":" + version + ":" + i).getBytes(StandardCharsets.UTF_8)).toString();
            repository.insertChunk(chunkId, previous.id(), version, i + 1, chunks.get(i));
        }
        repository.finish(previous.id(), "READY", null);
        return repository.material(previous.courseId(), previous.id());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Material publish(Account account, long courseId, String id, boolean published) {
        return publish(account, courseId, id, published, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Material publish(Account account, long courseId, String id, boolean published, Integer expectedVersion) {
        access.manage(account, courseId);
        StoredMaterial stored = repository.stored(courseId, id, true);
        checkVersion(stored, expectedVersion);
        if (published && !stored.parseStatus().equals("READY")) {
            throw new RagException(409, "资料解析失败或尚未就绪，不能发布");
        }
        repository.publish(id, published);
        return repository.material(courseId, id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Account account, long courseId, String id) {
        delete(account, courseId, id, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Account account, long courseId, String id, Integer expectedVersion) {
        access.manage(account, courseId);
        checkVersion(repository.stored(courseId, id, true), expectedVersion);
        repository.delete(id);
    }

    public FileData file(Account account, long courseId, String id) {
        return file(account, courseId, id, null);
    }

    public FileData file(Account account, long courseId, String id, Integer expectedVersion) {
        access.read(account, courseId);
        // Read eligibility and bytes together; never resolve a client-supplied filesystem path.
        StoredMaterial material = repository.stored(courseId, id, false);
        if (account.role().equals("student") && (!material.published() || !material.parseStatus().equals("READY"))) {
            throw new RagException(404, "资料不存在或尚未发布");
        }
        checkVersion(material, expectedVersion);
        return new FileData(material.filename(), material.content(), material.version());
    }

    private static void checkVersion(StoredMaterial material, Integer expectedVersion) {
        if (expectedVersion != null && expectedVersion < 1) throw new RagException(400, "资料版本必须是正整数");
        if (expectedVersion != null && material.version() != expectedVersion) {
            throw new RagException(409, "资料已更新，请刷新并核对新版本后重试");
        }
    }

    private static void validateUpload(String filename, byte[] content) {
        if (filename == null || filename.isBlank() || filename.length() > 180
                || filename.contains("/") || filename.contains("\\") || filename.chars().anyMatch(Character::isISOControl)) {
            throw new RagException(400, "文件名为空、过长或含非法字符");
        }
        if (content == null || content.length == 0 || content.length > DocumentParser.MAX_FILE_BYTES) {
            throw new RagException(400, "请选择不超过 10MB 的非空资料文件");
        }
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
