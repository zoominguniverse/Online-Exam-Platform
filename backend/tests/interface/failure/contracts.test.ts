import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildServer } from '../../../src/interfaces/http.ts';
import type { Exams } from '../../../src/application/exams.ts';
import { Failure } from '../../../src/domain/contracts.ts';
import { security, headers } from '../../support.ts';

test('CT03-N/F, CT04-F: envelopes, parameter rejection before business calls, registered errors and hidden faults', async () => {
  let calls = 0;
  const exams = { async answers() { calls++; return { saved: true }; }, async list() { throw new Failure('BIZ_VERSION_CONFLICT', '请刷新。'); }, async paper() { throw new Error('private stack or password'); } } as unknown as Exams;
  const app = buildServer({ origins: ['https://exam.test'], rateLimit: 1000, security, exams });
  try {
    const path = '/api/v1/attempts/00000000-0000-4000-8000-000000000004/answers';
    const h = { ...headers, 'idempotency-key': 'test-1' };
    for (const body of [{}, { version: '1', answers: [] }, { version: 1, answers: [], status: 'completed' }, { version: 1, answers: [{ itemId: 'bad', text: 'x' }] }, { version: 1, answers: [{ itemId: '00000000-0000-4000-8000-000000000005', text: 'x'.repeat(10001) }] }]) {
      const response = await app.inject({ method: 'PUT', url: path, headers: h, payload: body });
      assert.equal(response.statusCode, 400); assert.equal(response.json().code, 'PARAM_INVALID'); assert.ok(response.json().requestId);
    }
    assert.equal(calls, 0);
    const success = await app.inject({ method: 'PUT', url: path, headers: h, payload: { version: 1, answers: [] } });
    assert.equal(success.json().code, 'OK'); assert.deepEqual(success.json().data, { saved: true }); assert.equal(calls, 1);
    const conflict = await app.inject({ url: '/api/v1/papers', headers });
    assert.equal(conflict.statusCode, 409); assert.equal(conflict.json().code, 'BIZ_VERSION_CONFLICT');
    const fault = await app.inject({ url: '/api/v1/papers/00000000-0000-4000-8000-000000000004', headers });
    assert.equal(fault.statusCode, 500); assert.equal(fault.json().code, 'INTERNAL_FAILURE'); assert.doesNotMatch(fault.body, /password|stack/);
    const unready = await app.inject({ url: '/health/ready' });
    assert.equal(unready.statusCode, 503); assert.equal(unready.json().code, 'DEP_UNAVAILABLE');
  } finally { await app.close(); }
});

test('IA01-F1/F2, CT03-N: unknown policy, anonymous and wrong role are denied before business execution', async () => {
  let calls = 0;
  const app = buildServer({ origins: ['https://exam.test'], rateLimit: 100, security, exams: { async list() { calls++; return {}; } } as unknown as Exams });
  app.get('/api/v1/unregistered', async () => { calls++; return {}; });
  app.get('/api/v1/admin-only', { config: { access: ['admin'] } }, async () => { calls++; return {}; });
  try {
    for (const [url, h, code] of [['/api/v1/unregistered', headers, 'AUTHZ_FORBIDDEN'], ['/api/v1/admin-only', headers, 'AUTHZ_FORBIDDEN'], ['/api/v1/papers', {}, 'AUTHN_REQUIRED']] as const) {
      const response = await app.inject({ url, headers: h });
      assert.equal(response.json().code, code);
    }
    assert.equal(calls, 0);
  } finally { await app.close(); }
  const deny = buildServer({ origins: ['https://exam.test'], rateLimit: 100 });
  try { assert.equal((await deny.inject({ url: '/api/v1/papers', headers })).json().code, 'AUTHN_REQUIRED'); }
  finally { await deny.close(); }
});
