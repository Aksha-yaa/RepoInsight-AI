const $ = selector => document.querySelector(selector);
const escapeHtml = value => String(value ?? '').replace(/[&<>'"]/g, character => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[character]));
const cleanText = value => String(value ?? 'No information was provided.').replace(/\*\*/g, '').replace(/__/g, '').replace(/^#{1,6}\s*/gm, '').replace(/[\u2013\u2014]/g, '-').trim();
const text = value => escapeHtml(cleanText(value)).replace(/\n/g, '<br>');
let registerMode = false;

$('#auth-form').addEventListener('submit', submitAuth);
$('#auth-toggle').addEventListener('click', toggleAuthMode);
$('#analysis-form').addEventListener('submit', analyze);
$('#refresh-history').addEventListener('click', loadHistory);
$('#close-report').addEventListener('click', () => $('#dashboard').classList.add('hidden'));
$('#logout').addEventListener('click', async () => { await fetch('/api/auth/logout', {method: 'POST'}); window.location.reload(); });
document.querySelectorAll('[data-example]').forEach(button => button.addEventListener('click', () => { $('#repository-url').value = button.dataset.example; analyze(new Event('submit')); }));

async function submitAuth(event) {
  event.preventDefault(); $('#auth-error').textContent = '';
  const body = {email: $('#auth-email').value.trim(), password: $('#auth-password').value};
  if (registerMode) body.username = $('#auth-username').value.trim();
  try { showApp(await post(registerMode ? '/api/auth/register' : '/api/auth/login', body)); }
  catch (error) { $('#auth-error').textContent = error.message; }
}
function toggleAuthMode() {
  registerMode = !registerMode;
  $('#auth-title').textContent = registerMode ? 'Create your private archive.' : 'Sign in to your repository archive.';
  $('#auth-copy').textContent = registerMode ? 'Your account includes one free repository analysis. Reports remain private to your account.' : 'Sign in to access your private reports and continue your analysis.';
  $('#username-label').classList.toggle('hidden', !registerMode); $('#auth-username').classList.toggle('hidden', !registerMode);
  $('#auth-submit').textContent = registerMode ? 'Create account' : 'Sign in'; $('#auth-toggle').textContent = registerMode ? 'I already have an account' : 'Create a new account';
}
async function loadAuth() { try { const response = await fetch('/api/auth'); if (response.ok) showApp(await response.json()); } catch { } }
function showApp(account) { $('#auth-gate').classList.add('hidden'); $('#app-content').classList.remove('hidden'); $('#account-name').textContent = account.username; $('#demo-status').textContent = account.demoUsed ? 'Free demo used' : '1 free demo available'; loadHistory(); }

async function analyze(event) {
  event.preventDefault(); const repositoryUrl = $('#repository-url').value.trim(); if (!repositoryUrl) return;
  $('#dashboard').classList.add('hidden'); $('#loading').classList.remove('hidden'); $('#load-message').textContent = 'Reading repository evidence...';
  try {
    const repository = await post('/api/repositories/analyze', {repositoryUrl});
    $('#load-message').textContent = 'Queued. The analysis agent is gathering evidence...';
    const queued = await post('/api/insights/generate', repository);
    const result = await pollTask(queued.taskId);
    renderRepository({...result.repository, readme: result.insight.readme, files: result.insight.files, recentCommits: result.insight.recentCommits, fetchedFiles: result.insight.fetchedFiles});
    renderReport(result.insight); $('#demo-status').textContent = 'Free demo used'; $('#dashboard').classList.remove('hidden'); window.scrollTo({top: 0, behavior: 'smooth'}); loadHistory();
  }
  catch (error) { alert(error.message); } finally { $('#loading').classList.add('hidden'); }
}
async function post(url, body) { const response = await fetch(url, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)}); const data = await response.json(); if (!response.ok) throw new Error(data.error || 'Request could not be completed.'); return data; }
async function pollTask(taskId) {
  for (;;) {
    const response = await fetch(`/api/insights/tasks/${encodeURIComponent(taskId)}`);
    const task = await response.json();
    if (!response.ok) throw new Error(task.error || task.message || 'The analysis task failed.');
    $('#load-message').textContent = task.message || 'The analysis agent is working...';
    if (task.state === 'SUCCEEDED') return task;
    if (task.state === 'FAILED') throw new Error(task.message || 'The analysis could not be completed.');
    await new Promise(resolve => setTimeout(resolve, 1200));
  }
}
function renderRepository(repo) {
  $('#repo-name').textContent = `${repo.owner || ''}/${repo.name || ''}`; $('#repo-url').href = repo.url || '#'; $('#repo-description').textContent = repo.description || 'No description provided.';
  $('#stars').textContent = number(repo.stars); $('#forks').textContent = number(repo.forks); $('#watchers').textContent = number(repo.watchers); $('#issues').textContent = number(repo.openIssues);
  const languages = Object.entries(repo.languages || {}).sort((a, b) => b[1] - a[1]); const total = languages.reduce((sum, [, amount]) => sum + amount, 0) || 1;
  $('#primary-language').textContent = languages[0]?.[0] || '-'; $('#primary-language-badge').textContent = languages[0]?.[0] || 'No language data'; $('#branch').textContent = repo.defaultBranch || '-'; $('#updated').textContent = repo.updatedAt ? new Date(repo.updatedAt).toLocaleDateString('en-GB') : '-';
  const detected = [...languages.map(([name]) => name), ...(repo.topics || [])].slice(0, 16); $('#topics').innerHTML = detected.map(item => `<span class="pill">${escapeHtml(item)}</span>`).join('') || '<span>No detected technologies.</span>';
  $('#languages').innerHTML = languages.map(([name, amount]) => `<div class="language"><label><span>${escapeHtml(name)}</span><span>${Math.round(amount / total * 100)}%</span></label><div class="bar"><i style="width:${amount / total * 100}%"></i></div></div>`).join('') || '<span>No language data.</span>';
  const files = repo.files || []; $('#file-count').textContent = `${files.length} files`; $('#files').innerHTML = files.slice(0, 200).map(file => `<li>${escapeHtml(file)}</li>`).join('') || '<li>No file tree was saved.</li>'; const commits = repo.recentCommits || []; $('#commit-count').textContent = `${commits.length} commits`; $('#commits').innerHTML = commits.map(commit => `<li>${escapeHtml(commit)}</li>`).join('') || '<li>No commit history was saved.</li>'; const fetched = repo.fetchedFiles || {}; const fetchedEntries = Object.entries(fetched); $('#fetched-file-count').textContent = `${fetchedEntries.length} files`; $('#fetched-files').innerHTML = fetchedEntries.map(([path, contents]) => `<details open><summary>${escapeHtml(path)}</summary><pre>${escapeHtml(contents)}</pre></details>`).join('') || '<span>No individual source files were fetched.</span>'; $('#readme').innerHTML = text(repo.readme || 'README was not saved for this report.');
}
function renderReport(insight) { $('#summary').innerHTML = text(insight.summary); $('#architecture').innerHTML = text(insight.architecture); $('#technology-insights').innerHTML = text(insight.technologyInsights); $('#recommendations').innerHTML = text(insight.recommendations); }
function number(value) { return Number(value || 0).toLocaleString(); }
async function loadHistory() {
  try { const response = await fetch('/api/reports/'); const reports = await response.json(); if (!response.ok) throw new Error(); $('#history-list').innerHTML = reports.map(report => `<article class="history-item"><div><h3>${escapeHtml(report.repositoryName)}</h3><p>${escapeHtml(cleanText(report.summary))}</p><time>${formatDate(report.generatedDate)}</time></div><button data-report="${report.id}" type="button">Open full report</button></article>`).join(''); document.querySelectorAll('[data-report]').forEach(button => button.addEventListener('click', () => openReport(button.dataset.report))); } catch { $('#history-list').innerHTML = ''; }
}
async function openReport(id) {
  const response = await fetch(`/api/reports/${id}`); const report = await response.json(); if (!response.ok) return alert(report.error || 'Report unavailable.');
  const parts = report.repositoryName.split('/'); renderRepository({owner: parts[0], name: parts.slice(1).join('/'), url: report.repositoryUrl, description: 'Saved analysis report', files: report.files, readme: report.readme, recentCommits: report.recentCommits, fetchedFiles: report.fetchedFiles, languages: {}});
  renderReport({summary: report.summary, architecture: report.architectureDetails, technologyInsights: report.technologyInsights, recommendations: report.recommendations}); $('#dashboard').classList.remove('hidden'); window.scrollTo({top: 0, behavior: 'smooth'});
}
function formatDate(value) { if (!value) return 'Date unavailable'; const date = new Date(value); return Number.isNaN(date.valueOf()) ? value : date.toLocaleString('en-GB', {dateStyle: 'medium', timeStyle: 'short'}); }
loadAuth();
