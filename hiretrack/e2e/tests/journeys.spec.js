import { expect, test } from '@playwright/test';
import { addResume, signUp } from './helpers.js';

test('1. sign up → resume → match → save to Wishlist', async ({ page }) => {
  await signUp(page);
  await addResume(page);
  await page.goto('/match');
  await page.fill('#job-description', 'Acme hires a Backend Engineer: Java, Kafka, Kubernetes.');
  await page.click('button:has-text("Run AI match")');
  await expect(page.getByLabel('Fit score')).toContainText('72');
  await expect(page.locator('#save-company')).toHaveValue('Acme');
  await page.click('button:has-text("Save to pipeline")');
  await page.waitForURL('**/board');
  await expect(page.locator('[data-stage="WISHLIST"] .kanban-card')).toContainText('Acme');
});

test('2. drag through every stage → dashboard updates', async ({ page }) => {
  await signUp(page);
  await page.goto('/board');
  await page.fill('#new-company', 'Globex');
  await page.fill('#new-role', 'SRE');
  await page.click('button:has-text("Add to Wishlist")');
  for (const stage of ['APPLIED', 'INTERVIEW']) {
    await page.locator('.kanban-card:has-text("Globex")').dragTo(page.locator(`[data-stage="${stage}"]`));
    await expect(page.locator(`[data-stage="${stage}"] .kanban-card`)).toContainText('Globex');
  }
  await page.goto('/dashboard');
  await expect(page.locator('#response-rate')).toHaveText('100%', { timeout: 20_000 });
});

test('3. job feed: save search, refresh, save a posting', async ({ page }) => {
  await signUp(page);
  await addResume(page);
  await page.goto('/jobs');
  await page.fill('#feed-keywords', 'java developer');
  await page.fill('#feed-location', 'Bangalore');
  await page.click('button:has-text("Save search")');
  await page.click('button:has-text("Refresh now")');
  // the e2e stack runs the submitter/poller on short intervals (see docker-compose.e2e.yml above)
  await expect(page.locator('article.job').first()).toBeVisible({ timeout: 90_000 });
  await page.locator('article.job').first().locator('button:has-text("Save to pipeline")').click();
  await page.goto('/board');
  await expect(page.locator('[data-stage="WISHLIST"] .kanban-card')).toHaveCount(1);
});

test('4. session survives reload; logout ends it', async ({ page }) => {
  await signUp(page);
  await page.reload();
  await expect(page).toHaveURL(/dashboard/);
  expect(await page.evaluate(() => Object.keys(localStorage))).toEqual([]);
  await page.click('button:has-text("Log out")');
  await page.reload();
  await expect(page).toHaveURL(/login/);
});

test('5. conflicting edit in another tab shows the 412 message', async ({ browser }) => {
  const context = await browser.newContext();
  const a = await context.newPage();
  await signUp(a);
  await a.goto('/board');
  await a.fill('#new-company', 'Initech');
  await a.fill('#new-role', 'Dev');
  await a.click('button:has-text("Add to Wishlist")');
  await a.locator('.kanban-card:has-text("Initech")').waitFor();

  const b = await context.newPage();                       // same session cookie
  await b.goto('/board');
  await b.locator('.kanban-card:has-text("Initech")').dragTo(b.locator('[data-stage="APPLIED"]'));
  await expect(b.locator('[data-stage="APPLIED"] .kanban-card')).toContainText('Initech');

  await a.locator('.kanban-card:has-text("Initech")').dragTo(a.locator('[data-stage="REJECTED"]'));
  await expect(a.locator('.banner.warn')).toContainText('changed in another tab');
});