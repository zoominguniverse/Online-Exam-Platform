import { Pool } from 'pg';

export function database(connectionString: string) {
  const pool = new Pool({ connectionString, connectionTimeoutMillis: 3000, max: 10, options: '-c timezone=UTC' });
  return {
    pool,
    async ready() { await pool.query('SELECT 1'); return true; },
    async close() { await pool.end(); },
  };
}
