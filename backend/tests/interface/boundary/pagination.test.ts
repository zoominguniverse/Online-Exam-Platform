import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildServer } from '../../../src/interfaces/http.ts';
import type { Exams } from '../../../src/application/exams.ts';
import type { Row, ListQuery } from '../../../src/domain/contracts.ts';
import { security, headers } from '../../support.ts';

test('CT06-N/F: pagination limits and independent filter whitelists', async () => {
  const records = Array.from({ length: 6 }, (_, i) => ({ id: String(i), createdAt: '2026-01-01T00:00:00Z' }));
  let calls = 0;
  const exams = { async list(_actor: unknown, _kind: unknown, query: ListQuery) {
    calls++; const ordered = [...records].sort((a, b) => (a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id)) * (query.direction === 'desc' ? -1 : 1));
    return { items: ordered.slice((query.page - 1) * query.pageSize, query.page * query.pageSize), page: query.page, pageSize: query.pageSize, total: records.length };
  } } as unknown as Exams;
  const app = buildServer({ origins: ['https://exam.test'], rateLimit: 1000, security, exams });
  try {
    const pages: Row[] = [];
    for (const page of [1, 2]) pages.push((await app.inject({ url: `/api/v1/papers?page=${page}&pageSize=3&sort=createdAt&direction=asc`, headers })).json().data);
    assert.deepEqual(pages.flatMap(page => page.items.map((item: Row) => item.id)), ['0', '1', '2', '3', '4', '5']);
    for (const n of [1, 100, 101]) {
      const response = await app.inject({ url: `/api/v1/papers?pageSize=${n}`, headers });
      assert.equal(response.json().data.pageSize, Math.min(n, 100));
    }
    const before = calls;
    for (const suffix of ['page=0', 'pageSize=-1', 'pageSize=0', 'pageSize=1.5', 'sort=password', 'gradeId=999999', 'type=unknown', 'unknown=true', 'page=1&page=2']) {
      const response = await app.inject({ url: `/api/v1/papers?${suffix}`, headers });
      assert.equal(response.statusCode, 400, suffix); assert.equal(response.json().code, 'PARAM_INVALID');
    }
    assert.equal(calls, before);
  } finally { await app.close(); }
});
