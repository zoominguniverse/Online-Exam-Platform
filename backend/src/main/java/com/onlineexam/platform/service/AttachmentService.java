package com.onlineexam.platform.service;

import com.onlineexam.platform.domain.ApiException;
import com.onlineexam.platform.repository.AttachmentRepository;
import com.onlineexam.platform.repository.AttachmentRepository.PrivateImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AttachmentService {
    public record Staged(UUID id, String contentType, int byteSize) {}
    public record Image(byte[] bytes, String contentType) {}

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final long MAX_PIXELS = 16_000_000;
    private final AttachmentRepository attachments;
    private final Clock clock;
    private final Path root;

    public AttachmentService(AttachmentRepository attachments, Clock clock,
            @Value("${PRIVATE_STORAGE_ROOT:}") String root) {
        this.attachments = attachments;
        this.clock = clock;
        this.root = root.isBlank() ? null : Path.of(root).toAbsolutePath().normalize();
    }

    public Staged stage(UUID owner, byte[] bytes) {
        if (bytes.length < 1 || bytes.length > MAX_BYTES) throw ApiException.validation("图片必须小于或等于 5 MiB。");
        String type = verifyImage(bytes);
        if (root == null) throw unavailable();
        UUID id = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        Path image = storagePath(key);
        Path temporary = root.resolve(key + ".part");
        try {
            Files.createDirectories(root);
            try (var output = Files.newOutputStream(temporary, java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE)) {
                output.write(bytes);
            }
            Files.move(temporary, image, StandardCopyOption.ATOMIC_MOVE);
            attachments.stage(id, owner, key, type, bytes.length, digest(bytes), OffsetDateTime.now(clock));
            return new Staged(id, type, bytes.length);
        } catch (IOException error) {
            delete(temporary);
            throw unavailable();
        }
    }

    public Image readOwned(UUID id, UUID user) {
        return read(attachments.ownedLinkedImage(id, user).orElseThrow(AttachmentService::missing));
    }

    public Image readQuestionImage(UUID id, UUID question, int version) {
        return read(attachments.questionImage(id, question, version).orElseThrow(AttachmentService::missing));
    }

    private Image read(PrivateImage image) {
        try {
            return new Image(Files.readAllBytes(storagePath(image.key())), image.contentType());
        } catch (IOException error) {
            throw unavailable();
        }
    }

    public void clean() {
        if (root == null || !Files.isDirectory(root)) return;
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minusHours(24);
        attachments.deleteExpired(cutoff).forEach(key -> delete(storagePath(key)));
        try (var files = Files.list(root)) {
            files.forEach(path -> cleanOrphan(path, cutoff));
        } catch (IOException error) {
            log.warn("Could not inspect private attachments during cleanup");
        }
    }

    private void cleanOrphan(Path path, OffsetDateTime cutoff) {
        String file = path.getFileName().toString();
        if (!file.matches("[0-9a-f-]{36}\\.(img|part)")) return;
        try {
            UUID key = UUID.fromString(file.substring(0, 36));
            if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff.toInstant())
                    && (file.endsWith(".part") || !attachments.hasStorageKey(key))) delete(path);
        } catch (IOException | IllegalArgumentException error) {
            log.warn("Could not inspect a private attachment during cleanup");
        }
    }

    private Path storagePath(UUID key) {
        if (root == null) throw unavailable();
        return root.resolve(key + ".img");
    }

    private static String verifyImage(byte[] bytes) {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage();
            var reader = readers.next();
            try {
                String type = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                    case "png" -> "image/png";
                    case "jpeg", "jpg" -> "image/jpeg";
                    case "webp" -> "image/webp";
                    default -> throw invalidImage();
                };
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) throw invalidImage();
                reader.read(0).flush();
                return type;
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException error) {
            throw invalidImage();
        }
    }

    private static ApiException invalidImage() {
        return ApiException.validation("需要有效的 PNG、JPEG 或 WebP 图片，像素总数不能超过 1600 万。");
    }

    private static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "图片不存在。");
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required", error);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException error) {
            log.warn("Could not remove a private attachment during cleanup");
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "图片存储暂不可用。");
    }
}
