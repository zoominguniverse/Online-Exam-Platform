package com.onlineexam.platform.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AttachmentRepository {
    private final JdbcClient jdbc;

    public record PrivateImage(UUID key, String contentType) {}

    public AttachmentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void stage(UUID id, UUID owner, UUID key, String type, int size, String digest, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO attachments (id, owner_id, storage_key, media_type, byte_size, sha256, created_at)
                VALUES (:id, :owner, :key, :type, :size, :digest, :now)
                """).param("id", id).param("owner", owner).param("key", key).param("type", type)
                .param("size", size).param("digest", digest).param("now", now).update();
    }

    public boolean mayLink(UUID id, UUID owner, UUID question) {
        return jdbc.sql("""
                SELECT a.id FROM attachments a WHERE a.id = :id AND (a.owner_id = :owner OR EXISTS (
                    SELECT 1 FROM attachment_links l WHERE l.attachment_id = a.id AND l.question_id = :question
                )) FOR UPDATE
                """).param("id", id).param("owner", owner).param("question", question)
                .query(UUID.class).optional().isPresent();
    }

    public void link(UUID id, UUID question, int version, String part) {
        jdbc.sql("""
                INSERT INTO attachment_links (attachment_id, question_id, question_version, part)
                VALUES (:id, :question, :version, :part)
                """).param("id", id).param("question", question).param("version", version).param("part", part).update();
    }

    public Optional<PrivateImage> ownedLinkedImage(UUID id, UUID owner) {
        return jdbc.sql("""
                SELECT storage_key, media_type FROM attachments a WHERE a.id = :id AND a.owner_id = :owner
                AND EXISTS (SELECT 1 FROM attachment_links l WHERE l.attachment_id = a.id)
                """).param("id", id).param("owner", owner)
                .query((row, index) -> new PrivateImage(row.getObject("storage_key", UUID.class), row.getString("media_type")))
                .optional();
    }

    public Optional<PrivateImage> questionImage(UUID id, UUID question, int version) {
        return jdbc.sql("""
                SELECT a.storage_key, a.media_type FROM attachments a
                JOIN attachment_links l ON a.id = l.attachment_id
                WHERE a.id = :id AND l.question_id = :question AND l.question_version = :version
                LIMIT 1
                """).param("id", id).param("question", question).param("version", version)
                .query((row, index) -> new PrivateImage(row.getObject("storage_key", UUID.class), row.getString("media_type")))
                .optional();
    }

    public List<UUID> deleteExpired(OffsetDateTime cutoff) {
        return jdbc.sql("""
                DELETE FROM attachments WHERE id IN (
                    SELECT a.id FROM attachments a WHERE a.created_at < :cutoff
                    AND NOT EXISTS (SELECT 1 FROM attachment_links l WHERE l.attachment_id = a.id)
                    LIMIT 500 FOR UPDATE SKIP LOCKED
                ) RETURNING storage_key
                """).param("cutoff", cutoff).query(UUID.class).list();
    }

    public boolean hasStorageKey(UUID key) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM attachments WHERE storage_key = :key)")
                .param("key", key).query(Boolean.class).single();
    }
}
