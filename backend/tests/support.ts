import { Failure } from '../src/domain/contracts.ts';
import type { Principal } from '../src/domain/contracts.ts';
import type { Security } from '../src/domain/security.ts';
export const student: Principal = { id: '00000000-0000-4000-8000-000000000002', role: 'student', sessionId: '00000000-0000-4000-8000-000000000003' };
export const security: Security = {
  async authenticate(header) { if (header !== 'Bearer synthetic-test-token') throw new Failure('AUTHN_REQUIRED', '请先登录。'); return student; },
  async authorize(actor, roles) { if (!roles.includes(actor.role)) throw new Failure('AUTHZ_FORBIDDEN', '无权执行。'); },
  async revokeUser() {},
};
export const headers = { authorization: 'Bearer synthetic-test-token' };
export const env = { DATABASE_URL: 'postgresql://synthetic@127.0.0.1:5432/exam_test', APP_ORIGIN: 'https://exam.test', CORS_ALLOWED_ORIGINS: 'https://exam.test', PRIVATE_STORAGE_ROOT: '/tmp/exam-test-private', OUTBOX_ENABLED: 'true', ACCESS_TOKEN_SECRET: 'synthetic-test-signing-key-no-real-secret', LOG_LEVEL: 'silent', RATE_LIMIT_MAX: '1000', HOST: '127.0.0.1', PORT: '3001' };
