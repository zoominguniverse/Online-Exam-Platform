import type { Pool } from 'pg';
import { readdir, readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';

export async function migrate(pool: Pool, direction: 'up' | 'down' = 'up', directory = new URL('../../migrations/', import.meta.url)) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    await client.query("SELECT pg_advisory_xact_lock(hashtext(current_schema()),812309)");
    await client.query('CREATE TABLE IF NOT EXISTS schema_migrations (version text PRIMARY KEY, digest text NOT NULL, applied_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP)');
    const files = (await readdir(directory)).filter(f => f.endsWith('.up.sql')).sort();
    const recorded = (await client.query('SELECT version,digest FROM schema_migrations ORDER BY version')).rows;
    const source = new Map<string, { digest: string; sql: string }>();
    for (const file of files) {
      const sql = await readFile(new URL(file, directory), 'utf8');
      const down = await readFile(new URL(file.replace('.up.sql', '.down.sql'), directory), 'utf8');
      source.set(file, { sql, digest: createHash('sha256').update(sql).update('\0').update(down).digest('hex') });
    }
    for (const row of recorded) if (!source.has(row.version) || source.get(row.version)!.digest !== row.digest) throw new Error(`迁移历史摘要不一致：${row.version}`);
    if (direction === 'down') {
      const latest = recorded.at(-1);
      if (latest) {
        await client.query(await readFile(new URL(latest.version.replace('.up.sql', '.down.sql'), directory), 'utf8'));
        await client.query('DELETE FROM schema_migrations WHERE version=$1', [latest.version]);
      }
    } else {
      for (const [file, entry] of source) if (!recorded.some(r => r.version === file)) {
        await client.query(entry.sql);
        await client.query('INSERT INTO schema_migrations(version,digest) VALUES ($1,$2)', [file, entry.digest]);
      }
    }
    await client.query('COMMIT');
  } catch (error) { await client.query('ROLLBACK'); throw error; }
  finally { client.release(); }
}
