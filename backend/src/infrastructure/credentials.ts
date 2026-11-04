import bcrypt from 'bcryptjs';
import { createHmac, timingSafeEqual, randomBytes } from 'node:crypto';
import type { Credentials } from '../domain/security.ts';

export async function credentials(secret: string): Promise<Credentials> {
  const dummyHash = await bcrypt.hash(randomBytes(32).toString('hex'),12);
  const mac = (value: string) => createHmac('sha256',secret).update(value).digest('base64url');
  return {
    hash: password => bcrypt.hash(password,12), verify: (password,hash) => bcrypt.compare(password,hash), dummy: () => dummyHash,
    fingerprint: value => mac(`fingerprint:${value}`),
    access(claims) { const body = Buffer.from(JSON.stringify(claims)).toString('base64url'); return `${body}.${mac(`access:${body}`)}`; },
    parse(token) {
      try {
        const parts = token.split('.'); if (parts.length!==2) return null;
        const expected = Buffer.from(mac(`access:${parts[0]}`)); const actual = Buffer.from(parts[1]);
        if (expected.length!==actual.length || !timingSafeEqual(expected,actual)) return null;
        return JSON.parse(Buffer.from(parts[0],'base64url').toString());
      } catch { return null; }
    },
    refresh: (sessionId,tokenId) => `${tokenId}.${mac(`refresh:${sessionId}:${tokenId}`)}`,
    csrf: sessionId => mac(`csrf:${sessionId}`),
  };
}
