import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

const baseUrl = required('LOAD_BASE_URL').replace(/\/+$/, '');
const storeId = required('LOAD_STORE_ID');
const day = required('LOAD_DAY');
const payrollMonth = required('LOAD_PAYROLL_MONTH');
const reportId = required('LOAD_REPORT_ID');
const credentialsFile = required('LOAD_TEST_USERS_FILE');

const typicalVus = integer('LOAD_TYPICAL_VUS', 4);
const payrollVus = integer('LOAD_PAYROLL_VUS', 2);
const reportVus = integer('LOAD_REPORT_VUS', 1);
const syncVus = integer('LOAD_SYNC_VUS', 2);
const totalVus = typicalVus + payrollVus + reportVus + syncVus;

const users = new SharedArray('load-test-users', () => {
  const parsed = JSON.parse(open(credentialsFile));
  if (!Array.isArray(parsed)) {
    throw new Error('LOAD_TEST_USERS_FILE must contain a JSON array');
  }
  return parsed;
});

if (users.length < totalVus) {
  throw new Error(
    `Load test needs ${totalVus} unique users because sessions are concurrency-limited`,
  );
}

export const options = {
  scenarios: {
    typical_day: constantScenario(typicalVus, 'typicalDay'),
    monthly_payroll: constantScenario(payrollVus, 'monthlyPayroll'),
    annual_report: constantScenario(reportVus, 'annualReport'),
    sync_storm_control_plane: constantScenario(syncVus, 'syncStormControlPlane'),
  },
  thresholds: {
    'http_req_failed{scenario:typical_day}': ['rate<0.01'],
    'http_req_duration{scenario:typical_day}': ['p(95)<500', 'p(99)<1000'],
    'http_req_failed{scenario:monthly_payroll}': ['rate<0.01'],
    'http_req_duration{scenario:monthly_payroll}': ['p(95)<1000', 'p(99)<2000'],
    'http_req_failed{scenario:annual_report}': ['rate<0.01'],
    'http_req_duration{scenario:annual_report}': ['p(95)<1500', 'p(99)<3000'],
    'http_req_failed{scenario:sync_storm_control_plane}': ['rate<0.01'],
    'http_req_duration{scenario:sync_storm_control_plane}': [
      'p(95)<500',
      'p(99)<1000',
    ],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

let authenticated = false;

export function typicalDay() {
  ensureAuthenticated();
  get(
    `/api/stores/${storeId}/kpi?periodStart=${day}&periodEnd=${day}`,
    'typical-day-kpi',
  );
  get(
    `/api/stores/${storeId}/employees?periodStart=${day}&periodEnd=${day}`,
    'typical-day-employees',
  );
  get(
    `/api/stores/${storeId}/work-schedule?periodStart=${day}&periodEnd=${day}`,
    'typical-day-schedule',
  );
  sleep(1);
}

export function monthlyPayroll() {
  ensureAuthenticated();
  get(`/api/stores/${storeId}/payroll/${payrollMonth}`, 'monthly-payroll');
  sleep(1);
}

export function annualReport() {
  ensureAuthenticated();
  get(`/api/stores/${storeId}/reports/${reportId}`, 'annual-report');
  sleep(1);
}

export function syncStormControlPlane() {
  ensureAuthenticated();
  get('/api/sync/jobs?limit=100', 'sync-storm-control-plane');
  sleep(1);
}

function ensureAuthenticated() {
  if (authenticated) {
    return;
  }
  const user = users[exec.vu.idInTest - 1];
  if (!user || typeof user.email !== 'string' || typeof user.password !== 'string') {
    fail('Each load-test user requires email and password strings');
  }
  const jar = http.cookieJar();
  const csrfResponse = http.get(`${baseUrl}/api/auth/csrf`, {
    jar,
    tags: { name: 'auth-csrf' },
  });
  if (!check(csrfResponse, { 'csrf status is 200': response => response.status === 200 })) {
    fail(`CSRF acquisition failed with status ${csrfResponse.status}`);
  }
  const csrfCookie = jar.cookiesForURL(baseUrl)['XSRF-TOKEN'];
  if (!csrfCookie || csrfCookie.length !== 1) {
    fail('CSRF cookie was not issued');
  }
  const loginResponse = http.post(
    `${baseUrl}/api/auth/login`,
    JSON.stringify({ email: user.email, password: user.password }),
    {
      jar,
      headers: {
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': csrfCookie[0],
      },
      tags: { name: 'auth-login' },
    },
  );
  if (!check(loginResponse, { 'login status is 200': response => response.status === 200 })) {
    fail(`Login failed with status ${loginResponse.status}`);
  }
  authenticated = true;
}

function get(path, name) {
  const response = http.get(`${baseUrl}${path}`, { tags: { name } });
  check(response, {
    [`${name} status is 200`]: current => current.status === 200,
  });
}

function constantScenario(vus, execName) {
  return {
    executor: 'constant-vus',
    exec: execName,
    vus,
    duration: __ENV.LOAD_DURATION || '2m',
    gracefulStop: '15s',
  };
}

function required(name) {
  const value = __ENV[name];
  if (!value) {
    throw new Error(`${name} is required`);
  }
  return value;
}

function integer(name, fallback) {
  const value = Number.parseInt(__ENV[name] || `${fallback}`, 10);
  if (!Number.isSafeInteger(value) || value < 1 || value > 100) {
    throw new Error(`${name} must be an integer between 1 and 100`);
  }
  return value;
}
