const $ = selector => document.querySelector(selector);
const escapeHtml = value => String(value ?? '').replace(/[&<>'"]/g, character => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[character]));
const cleanText = value => String(value ?? 'No information was provided.').replace(/\*\*/g, '').replace(/__/g, '').replace(/^#{1,6}\s*/gm, '').replace(/[\u2013\u2014]/g, '-').trim();
const text = value => escapeHtml(cleanText(value)).replace(/\n/g, '<br>');

$('#analysis-form').addEventListener('submit', analyze);
$('#refresh-history').addEventListener('click', loadHistory);
$('#close-report').addEventListener('click', () => $('#dashboard').classList.add('hidden'));
document.querySelectorAll('[data-example]').forEach(button => button.addEventListener('click', () => { $('#repository-url').value = button.dataset.example; analyze(new Event('submit')); }));

async function analyze(event) {
  event.preventDefault(); const repositoryUrl = $('#repository-url').value.trim(); if (!repositoryUrl) return;
  $('#dashboard').classList.add('hidden'); $('#loading').classList.remove('hidden'); $('#load-message').textContent = 'Reading repository evidence...';
  try { const repository = await post('/api/repositories/analyze', {repositoryUrl}); $('#load-message').textContent = 'Generating evidence-based report...'; const result = await post('/api/insights/generate', repository); renderRepository(repository); renderReport(result.insight); $('#dashboard').classList.remove('hidden'); window.scrollTo({top: 0, behavior: 'smooth'}); loadHistory(); }
  catch (error) { alert(error.message); } finally { $('#loading').classList.add('hidden'); }
}
async function post(url, body) { const response = await fetch(url, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)}); const data = await response.json(); if (!response.ok) throw new Error(data.error || 'Analysis could not be completed.'); return data; }
function renderRepository(repo) {
  $('#repo-name').textContent = `${repo.owner}/${repo.name}`; $('#repo-url').href = repo.url; $('#repo-description').textContent = repo.description || 'No description provided.';
  $('#stars').textContent = number(repo.stars); $('#forks').textContent = number(repo.forks); $('#watchers').textContent = number(repo.watchers); $('#issues').textContent = number(repo.openIssues);
  const languages = Object.entries(repo.languages || {}).sort((a, b) => b[1] - a[1]); const total = languages.reduce((sum, [, amount]) => sum + amount, 0) || 1;
  $('#primary-language').textContent = languages[0]?.[0] || '-'; $('#primary-language-badge').textContent = languages[0]?.[0] || 'No language data'; $('#branch').textContent = repo.defaultBranch || '-'; $('#updated').textContent = repo.updatedAt ? new Date(repo.updatedAt).toLocaleDateString('en-GB') : '-';
  const detected = [...languages.map(([name]) => name), ...(repo.topics || [])].slice(0, 16); $('#topics').innerHTML = detected.map(item => `<span class="pill">${escapeHtml(item)}</span>`).join('') || '<span>No detected technologies.</span>';
  $('#languages').innerHTML = languages.map(([name, amount]) => `<div class="language"><label><span>${escapeHtml(name)}</span><span>${Math.round(amount / total * 100)}%</span></label><div class="bar"><i style="width:${amount / total * 100}%"></i></div></div>`).join('') || '<span>No language data.</span>';
  const files = repo.files || []; $('#file-count').textContent = `${files.length} files`; $('#files').innerHTML = files.slice(0, 200).map(file => `<li>${escapeHtml(file)}</li>`).join('') || '<li>File tree was not requested for this report.</li>'; $('#readme').innerHTML = text(repo.readme || 'README was not requested for this report.');
}
function renderReport(insight) { $('#summary').innerHTML = text(insight.summary); $('#architecture').innerHTML = text(insight.architecture); $('#technology-insights').innerHTML = text(insight.technologyInsights); $('#recommendations').innerHTML = text(insight.recommendations); }
function number(value) { return Number(value || 0).toLocaleString(); }
async function loadHistory() {
  try { const response = await fetch('/api/reports/'); const reports = await response.json(); if (!response.ok) throw new Error(); $('#history-list').innerHTML = reports.map(report => `<article class="history-item"><div><h3>${escapeHtml(report.repositoryName)}</h3><p>${escapeHtml(cleanText(report.summary))}</p><time>${formatDate(report.generatedDate)}</time></div><button data-report="${report.id}" type="button">Open full report</button></article>`).join(''); document.querySelectorAll('[data-report]').forEach(button => button.addEventListener('click', () => openReport(button.dataset.report))); } catch { $('#history-list').innerHTML = ''; }
}
async function openReport(id) { const response = await fetch(`/api/reports/${id}`); const report = await response.json(); if (!response.ok) return alert(report.error || 'Report unavailable.'); $('#repo-name').textContent = report.repositoryName; $('#repo-url').href = report.repositoryUrl; $('#repo-description').textContent = 'Saved analysis report'; $('#summary').innerHTML = text(report.summary); $('#architecture').innerHTML = text(report.architectureDetails); $('#technology-insights').innerHTML = text(report.technologyInsights); $('#recommendations').innerHTML = text(report.recommendations); $('#dashboard').classList.remove('hidden'); window.scrollTo({top: 0, behavior: 'smooth'}); }
function formatDate(value) { if (!value) return 'Date unavailable'; const date = new Date(value); return Number.isNaN(date.valueOf()) ? value : date.toLocaleString('en-GB', {dateStyle: 'medium', timeStyle: 'short'}); }
loadHistory();