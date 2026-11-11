import { defineConfig } from '@playwright/test'

if (!process.env.EXAM_E2E_ORIGIN || !process.env.EXAM_E2E_ARTIFACTS) {
  throw new Error('Run node scripts/test-browser.mjs for an isolated API and database')
}
export default defineConfig({
  testDir: './e2e', workers: 1, retries: 0, timeout: 45000,
  reporter: [['list'], ['json', { outputFile: process.env.EXAM_E2E_ARTIFACTS + '/results.json' }]],
  outputDir: process.env.EXAM_E2E_ARTIFACTS,
  use: { baseURL: process.env.EXAM_E2E_ORIGIN, browserName: 'chromium', actionTimeout: 10000,
    viewport: { width: 1280, height: 900 }, screenshot: 'only-on-failure' },
})
