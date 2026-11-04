import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { config } from '../../../src/config.ts';
import { env } from '../../support.ts';

test('CT07-F: every missing required key fails without disclosing configuration values', () => {
  assert.equal(config(env).port, 3001);
  for (const key of Object.keys(env)) {
    const missing: Record<string, string> = { ...env }; delete missing[key];
    assert.throws(() => config(missing), (e: Error) => e.message.includes(key) && !e.message.includes(env.DATABASE_URL) && !e.message.includes(env.ACCESS_TOKEN_SECRET));
  }
  for (const patch of [{ PORT: '65536' }, { OUTBOX_ENABLED: 'yes' }, { APP_ORIGIN: 'http://exam.test' }, { CORS_ALLOWED_ORIGINS: '*' }, { ACCESS_TOKEN_SECRET: 'short' }, { DATABASE_URL: 'https://database.test' }, { PRIVATE_STORAGE_ROOT: 'relative' }]) assert.throws(() => config({ ...env, ...patch }));
  const child = spawnSync(process.execPath, ['src/main.ts'], { encoding: 'utf8', env: { PATH: process.env.PATH } });
  assert.notEqual(child.status, 0); assert.match(child.stderr, /DATABASE_URL/);
  assert.doesNotMatch(child.stderr, /synthetic-test-signing/);
});
