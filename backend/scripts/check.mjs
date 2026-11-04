import { architecture } from './architecture.mjs';
const errors = await architecture(new URL('../src', import.meta.url).pathname);
if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1; }
else console.log('CT01 architecture: passed');
if (!errors.length) await import('./openapi.mjs');
