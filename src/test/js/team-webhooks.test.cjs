const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.html'), 'utf8');
const code = html.slice(html.indexOf('// TEAMS WEBHOOK MODAL'), html.indexOf('// ADD TEAM MODAL'));

function harness(fetch) {
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, {
      style: {}, value: '', textContent: '', removeAttribute() {}, focus() {},
      disabled: false, classList: { add() {}, remove() {} },
      addEventListener() {}, checkValidity: () => true,
    });
    return elements.get(id);
  };
  const context = vm.createContext({
    activeTeam: 'first', teams: [{ id: 'first', name: 'First' }, { id: 'second', name: 'Second' }],
    document: { getElementById: element, querySelectorAll: () => [] }, fetch,
    isMonitorTeam: () => false, esc: s => s, b2bMsg() {}, renderTabs() {},
    switchToTeam(id) { context.activeTeam = id; },
    setTimeout: () => 1, clearInterval() {}, setInterval: () => 1,
  });
  vm.runInContext(code, context);
  return { context, element, run: source => vm.runInContext(source, context) };
}

const response = data => ({ ok: true, json: async () => data });
const deferred = () => {
  let resolve;
  const promise = new Promise(r => { resolve = r; });
  return { promise, resolve };
};

test('all inline JavaScript parses', () => {
  for (const match of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)) {
    new vm.Script(match[1]);
  }
});

test('modal shows the team and saves and tests only its own destination', async () => {
  const calls = [];
  const h = harness(async (url, options) => {
    calls.push({ url, options });
    if (url.includes('/scheduler/status')) return response({ nextRunAt: '2026-10-24T08:00:00Z', secondsUntilNext: 30 });
    if (url.includes('/scheduler/handovers')) return response({ handovers: [] });
    if (url.includes('/test?')) return response({ success: true, httpStatus: 202 });
    return response({ configured: true, webhookUrlMasked: 'https://first.example/•••' });
  });
  await h.run('openWebhookModal()');
  assert.equal(h.element('wh-title').textContent, 'Webhook — First');
  assert.equal(h.element('wh-url').value, '');
  await h.run('testWebhookUrl()');
  const testCall = calls.find(c => c.url.includes('/test?'));
  assert.equal(testCall.url, '/api/webhook-settings/test?team=first');
  assert.equal(JSON.parse(testCall.options.body).webhookUrl, '');

  h.run('switchToEditWebhook()');
  h.element('wh-url').value = 'https://new.example/secret';
  await h.run('saveWebhookUrl()');
  const saveCall = calls.find(c => c.options?.method === 'POST' && !c.url.includes('/test?'));
  assert.equal(saveCall.url, '/api/webhook-settings?team=first');
  assert.equal(JSON.parse(saveCall.options.body).webhookUrl, 'https://new.example/secret');
  assert.equal(h.element('wh-url').value, '');
});

test('late settings response cannot reopen a modal after a team switch', async () => {
  const pending = deferred();
  const h = harness(() => pending.promise);
  const opening = h.run('openWebhookModal()');
  h.run("closeWebhookModal(); activeTeam = 'second'");
  pending.resolve(response({ configured: true, webhookUrlMasked: 'First URL' }));
  await opening;
  assert.equal(h.element('webhook-modal').style.display, 'none');
  assert.notEqual(h.element('wh-view-url').textContent, 'First URL');
});

test('late save response cannot change the newly selected teams status', async () => {
  const pending = deferred();
  const h = harness(() => pending.promise);
  h.run("whTeamId = 'first'");
  h.element('wh-url').value = 'https://first.example/secret';
  const saving = h.run('saveWebhookUrl()');
  h.run("closeWebhookModal(); activeTeam = 'second'; whSetStatus(false, '')");
  pending.resolve(response({ configured: true, webhookUrlMasked: 'First URL' }));
  await saving;
  assert.equal(h.element('wh-status-badge').textContent, 'Not configured');
  assert.equal(h.element('wh-view-url').textContent, '');
});

test('late status response cannot overwrite another teams indicator', async () => {
  const pending = deferred();
  const h = harness(() => pending.promise);
  const loading = h.run('loadWebhookStatus()');
  h.run("activeTeam = 'second'; whSetStatus(false, '')");
  pending.resolve(response({ configured: true, webhookUrlMasked: 'First URL' }));
  await loading;
  assert.equal(h.element('wh-status-badge').textContent, 'Not configured');
});

test('upcoming handovers show only the configured team', async () => {
  const h = harness(async () => response({ handovers: [
    { teamId: 'second', teamName: 'Second', email: 'other@example.com', at: '10:00', type: 'shift_end', minutesUntil: 5 },
    { teamId: 'first', teamName: 'First', email: 'own@example.com', at: '10:30', type: 'shift_end', minutesUntil: 35 },
  ] }));
  h.run("whTeamId = 'first'");
  h.element('wh-view').style.display = 'block';
  await h.run('fetchHandovers()');
  assert.match(h.element('wh-handovers-list').innerHTML, /own/);
  assert.doesNotMatch(h.element('wh-handovers-list').innerHTML, /other/);
});

test('B2B panel saves and tests the selected teams destination', async () => {
  const calls = [];
  const h = harness(async (url, options) => {
    calls.push({ url, options });
    return response({ configured: true, webhookUrlMasked: 'https://b2b.example/•••', httpStatus: 202 });
  });
  h.run(html.slice(html.indexOf('let b2bWebhookMasked'), html.indexOf('async function loadB2bMembers')));
  h.context.activeTeam = 'second';
  h.element('b2b-wh-url').value = 'https://b2b.example/secret';
  await h.run('saveB2bWebhook()');
  assert.equal(calls[0].url, '/api/webhook-settings?team=second');
  assert.equal(h.element('b2b-wh-url').value, '');
  await h.run('testB2bWebhook()');
  assert.equal(calls[1].url, '/api/b2b/webhook-settings/test?team=second');
  assert.equal(JSON.parse(calls[1].options.body).webhookUrl, '');
});

function loadTeamForm(h) {
  h.run(html.slice(html.indexOf('// ADD TEAM MODAL'), html.indexOf('// FILE PICK / DRAG-DROP')));
}

test('team form saves the supplied webhook along with the team', async () => {
  const calls = [];
  const h = harness(async (url, options) => {
    calls.push({ url, options });
    return response(options ? { id: 'new-team' } : [{ id: 'new-team', name: 'Support' }]);
  });
  loadTeamForm(h);
  h.element('new-team-name').value = ' Support ';
  h.element('new-team-jql').value = ' project = SUP ';
  h.element('new-team-webhook').value = ' https://support.example/hook ';
  await h.run('doAddTeam()');
  assert.deepEqual(JSON.parse(calls[0].options.body), {
    name: 'Support', jql: 'project = SUP', webhookUrl: 'https://support.example/hook',
  });
  assert.equal(h.context.activeTeam, 'new-team');
  assert.equal(h.element('new-team-webhook').value, '');
  assert.equal(h.element('add-team-save-btn').disabled, false);
});

test('team form accepts an empty webhook for configuration after creation', async () => {
  const calls = [];
  const h = harness(async (url, options) => {
    calls.push({ url, options });
    return response(options ? { id: 'new-team' } : [{ id: 'new-team', name: 'Support' }]);
  });
  loadTeamForm(h);
  h.element('new-team-name').value = 'Support';
  h.element('new-team-jql').value = 'project = SUP';
  await h.run('doAddTeam()');
  assert.equal(JSON.parse(calls[0].options.body).webhookUrl, '');
  h.run("whSetStatus(false, '')");
  assert.equal(h.element('webhook-action-label').textContent, 'Add webhook');
  h.run("whSetStatus(true, 'https://support.example/•••')");
  assert.equal(h.element('webhook-action-label').textContent, 'Edit webhook');
});

test('closing or reopening the team form clears the previous webhook', () => {
  const h = harness(async () => response({}));
  loadTeamForm(h);
  h.element('new-team-webhook').value = 'https://previous.example/secret';
  h.run('closeAddTeam()');
  assert.equal(h.element('new-team-webhook').value, '');
  h.element('new-team-webhook').value = 'https://previous.example/secret';
  h.run('openAddTeam()');
  assert.equal(h.element('new-team-webhook').value, '');
});
