import { Failure } from './contracts.ts';
import type { Principal } from './contracts.ts';

export interface Security {
  authenticate(authorization: string | undefined): Promise<Principal>;
  authorize(principal: Principal, roles: readonly string[]): Promise<void>;
  revokeUser(userId: string): Promise<void>;
}
export interface Credentials {
  hash(password: string): Promise<string>;
  verify(password: string, hash: string): Promise<boolean>;
  dummy(): string;
  fingerprint(value: string): string;
  access(claims: Record<string, unknown>): string;
  parse(token: string): Record<string, any> | null;
  refresh(sessionId: string, tokenId: string): string;
  csrf(sessionId: string): string;
}
// Fail closed while the secure-exam-platform implementation is not connected.
export class DenySecurity implements Security {
  async authenticate(): Promise<Principal> { throw new Failure('AUTHN_REQUIRED', '请先登录后重试。'); }
  async authorize(): Promise<void> { throw new Failure('AUTHZ_FORBIDDEN', '当前账号无权执行此操作。'); }
  async revokeUser(): Promise<void> { throw new Failure('DEP_UNAVAILABLE', '身份服务未接入，请联系管理员。'); }
}
