import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = __ENV.BASE_URL || 'http://localhost/api/v1';

export const options = {
  scenarios: {
    reads:   { executor: 'constant-arrival-rate', rate: 200, timeUnit: '1s', duration: '15m',
               preAllocatedVUs: 100, maxVUs: 300, exec: 'reads' },
    matches: { executor: 'constant-arrival-rate', rate: 2, timeUnit: '1s', duration: '15m',
               preAllocatedVUs: 40, maxVUs: 80, exec: 'matches' },
  },
  thresholds: {
    'http_req_failed{kind:read}': ['rate<0.001'],
    'http_req_duration{kind:read}': ['p(95)<300', 'p(99)<800'],
    'http_req_duration{kind:match}': ['p(95)<15000'],
    'checks': ['rate>0.999'],
  },
};

// 50 load-test users, each with a resume and a few applications
export function setup() {
  const users = [];
  for (let i = 0; i < 50; i++) {
    const email = `load${Date.now()}-${i}@example.com`;
    const res = http.post(`${BASE}/auth/signup`, JSON.stringify({ name: 'Load', email, password: 'load-test-pw-1' }),
      { headers: { 'Content-Type': 'application/json' } });
    const token = res.json('accessToken');
    const h = { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } };
    http.post(`${BASE}/resumes`, JSON.stringify({ text: 'Java Spring Kafka PostgreSQL' }),
      { headers: { ...h.headers, 'Idempotency-Key': uuidv4() } });
    for (let j = 0; j < 5; j++) {
      http.post(`${BASE}/applications`, JSON.stringify({ company: `Co${j}`, role: 'Engineer' }),
        { headers: { ...h.headers, 'Idempotency-Key': uuidv4() } });
    }
    users.push(token);
  }
  return { users };
}

function auth(data) {
  const token = data.users[Math.floor(Math.random() * data.users.length)];
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
}

export function reads(data) {
  const headers = auth(data);
  const paths = ['/applications', '/dashboard?tz=UTC', '/resumes', '/matches'];
  const res = http.get(BASE + paths[Math.floor(Math.random() * paths.length)], { headers, tags: { kind: 'read' } });
  check(res, { 'read 200': (r) => r.status === 200 });
}

export function matches(data) {
  const headers = { ...auth(data), 'Idempotency-Key': uuidv4() };
  const res = http.post(`${BASE}/matches`, JSON.stringify({ jobDescription: 'Java Kafka engineer at Acme' }),
    { headers, tags: { kind: 'match' }, timeout: '20s' });
  check(res, { 'match 201 or quota 429': (r) => r.status === 201 || r.status === 429 });
}