export async function signUp(page) {
  const email = `e2e${Date.now()}${Math.floor(Math.random() * 1000)}@example.com`;
  await page.goto('/signup');
  await page.fill('#name', 'E2E User');
  await page.fill('#email', email);
  await page.fill('#password', 'correct-horse-battery');
  await page.click('button:has-text("Sign up")');
  await page.waitForURL('**/dashboard');
  return email;
}

export async function addResume(page) {
  await page.goto('/resume');
  await page.fill('#resume-text', 'Backend engineer: Java, Spring Boot, Kafka, PostgreSQL.');
  await page.click('button:has-text("Save as active resume")');
  await page.locator('.pill:has-text("Active")').waitFor();
}