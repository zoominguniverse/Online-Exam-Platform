import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
// @ts-expect-error The gate is also directly runnable by Node without a build.
import { architecture } from '../../../scripts/architecture.mjs';

test('CT01-F: reverse import, interface database access and SQL fail the architecture gate', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'exam-architecture-'));
  try {
    await mkdir(path.join(dir, 'application'));
    await mkdir(path.join(dir, 'interfaces'));
    await writeFile(path.join(dir, 'application', 'invalid.ts'), "import '../infrastructure/db.ts'; const sql = `SELECT id FROM users`; ");
    await writeFile(path.join(dir, 'interfaces', 'invalid.ts'), "const db = import('pg');");
    const failures = await architecture(dir);
    assert.ok(failures.some((v: string) => v.includes('application ->')));
    assert.ok(failures.some((v: string) => v.includes('SQL outside')));
    assert.ok(failures.some((v: string) => v.includes('interfaces -> pg')));
    await writeFile(path.join(dir, 'application', 'invalid.ts'), "import type { Principal } from '../domain/contracts.ts';");
    await writeFile(path.join(dir, 'interfaces', 'invalid.ts'), "import Fastify from 'fastify';");
    assert.deepEqual(await architecture(dir), []);
  } finally { await rm(dir, { recursive: true, force: true }); }
});
