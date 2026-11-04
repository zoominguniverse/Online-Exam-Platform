import { Pool } from 'pg';
import { randomUUID } from 'node:crypto';
import { migrate } from '../src/infrastructure/migrate.ts';
import { Postgres } from '../src/infrastructure/postgres.ts';

export function testTarget(env: NodeJS.ProcessEnv) {
  const url = new URL(env.EXAM_TEST_DATABASE_URL || 'invalid:');
  if (!/^[0-9a-f-]{36}$/.test(env.EXAM_TEST_RUN_ID || '') || url.hostname !== '127.0.0.1' || url.pathname !== '/exam_test' || url.username !== 'exam_test' || !['postgres:', 'postgresql:'].includes(url.protocol)) throw new Error('Refusing a database target outside the isolated test runner');
  return url.toString();
}
export async function fixture() {
  const connectionString = testTarget(process.env);
  const schema = `test_${randomUUID().replaceAll('-', '')}`;
  const admin = new Pool({ connectionString, max: 1 });
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new Pool({ connectionString, max: 12, options: `-c search_path=${schema} -c timezone=UTC` });
  try {
    await migrate(pool);
    return { pool, repo: new Postgres(pool), schema, async close() { await pool.end(); try { await admin.query(`DROP SCHEMA ${schema} CASCADE`); } finally { await admin.end(); } } };
  } catch (error) { await pool.end(); await admin.query(`DROP SCHEMA ${schema} CASCADE`); await admin.end(); throw error; }
}
