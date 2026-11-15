import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  timeout: 120_000,
  retries: process.env.CI ? 1 : 0,
  use: { baseURL: process.env.BASE_URL ?? 'http://localhost', trace: 'retain-on-failure' },
});