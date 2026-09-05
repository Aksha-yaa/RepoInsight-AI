const $ = s => document.querySelector(s);
const escapeHtml = value => String(value ?? '').replace(/[&<>'"]/g, c => ({'&':'&lt;','>':'&gt;','&':'&amp;',"'":'&#39;','"':'&quot;'}[c]));
const text = value => escapeHtml(value).replace(/\n/g, '<br>');

$('#analysis-form').addEventListener('submit', analyze);
document.querySelectorAll('[data-example]').forEach(button => button.addEventListener('click', () => { $('#repository-url').value = button.dataset.example; analyze(new Event('submit')); }));
document.querySelectorAll('.tab').forEach(button => button.addEventListener('click', () => selectTab(button.dataset.tab)));
$('#refresh-history').addEventListener('click', loadHistory);

async function analyze(event) {
  event.preventDefault(); const repositoryUrl = $('#repository-url').value.trim(); if (!repositoryUrl) return;
  $('#dashboard').classList.add('hidden'); $('#loading').classList.remove('hidden'); $('#load-message').textContent = 'Reading GitHub repository data…';
  try {
    const repository = await post('/api/repositories/analyze', {repositoryUrl});
    $('#load-message').textContent = 'Generating AI analysis…';
    const result = await post('/api/insights/generate', repository);
    render(repository, result.insight); $('#dashboard').classList.remove('hidden'); loadHistory();
  } catch (error) { alert(error.message); } finally { $('#loading').classList.add('hidden'); }
}
async function post(url, body) { const response = await fetch(url, {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)}); const data = await response.json(); if (!response.ok) throw new Error(data.error || 'Analysis could not be completed.'); return data; }
function render(repo, insight) {
  $('#repo-name').textContent = `${repo.owner}/${repo.name} ↗`; $('#repo-url').href = repo.url; $('#repo-description').textContent = repo.description;
  $('#stars').textContent = number(repo.stars); $('#forks').textContent = number(repo.forks); $('#watchers').textContent = number(repo.watchers); $('#issues').textContent = number(repo.openIssues);
  const languages = Object.entries(repo.languages || {}).sort((a,b) => b[1] - a[1]); const total = languages.reduce((sum, [,amount]) => sum + amount, 0) || 1;
  $('#primary-language').textContent = languages[0]?.[0] || '—'; $('#branch').textContent = repo.defaultBranch || '—'; $('#updated').textContent = repo.updatedAt ? new Date(repo.updatedAt).toLocaleDateString('en-GB') : '—';
  $('#summary').innerHTML = text(insight.summary); $('#architecture').innerHTML = text(insight.architecture); $('#recommendations').innerHTML = text(insight.recommendations); $('#readme').innerHTML = text((repo.readme || 'No README available.').slice(0, 12000));
  const detected = [...languages.map(([name]) => name), ...(repo.topics || [])].slice(0, 12); $('#topics').innerHTML = detected.map(item => `<span class="pill">${escapeHtml(item)}</span>`).join('') || '<span>No detected technologies.</span>';
  $('#languages').innerHTML = languages.map(([name, amount]) => `<div class="language"><label>${escapeHtml(name)} ${Math.round(amount / total * 100)}%</label><div class="bar"><i style="width:${amount / total * 100}%"></i></div></div>`).join('') || '<span>No language data.</span>';
  const strengths = recommendationLines(insight.recommendations); $('#strengths').innerHTML = strengths.map(item => `<li>${escapeHtml(item)}</li>`).join('') || '<li>Repository data has been collected for review.</li>';
  $('#files').innerHTML = (repo.files || []).slice(0, 100).map(file => `<li>${escapeHtml(file)}</li>`).join('') || '<li>No files available.</li>';
  selectTab('tech');
}
function recommendationLines(value) { return String(value || '').split(/\n|(?<=[.!?])\s+/).map(x => x.replace(/^[-*•\d.\s]+/, '').trim()).filter(x => x.length > 10).slice(0, 5); }
function selectTab(tab) { document.querySelectorAll('.tab').forEach(button => button.classList.toggle('active', button.dataset.tab === tab)); document.querySelectorAll('.tab-panel').forEach(panel => panel.classList.toggle('hidden', panel.id !== `panel-${tab}`)); }
function number(value) { return Number(value || 0).toLocaleString(); }
async function loadHistory() { try { const response = await fetch('/api/reports/'); const reports = await response.json(); if (!response.ok) throw new Error(); $('#history-list').innerHTML = reports.length ? reports.slice(0, 5).map(report => `<article class="history-item"><div><h3>${escapeHtml(report.repositoryName)}</h3><p>${escapeHtml(report.summary)}</p></div><button data-report="${report.id}">Open</button></article>`).join('') : ''; document.querySelectorAll('[data-report]').forEach(button => button.addEventListener('click', () => openReport(button.dataset.report))); } catch { $('#history-list').innerHTML = ''; } }
async function openReport(id) { const response = await fetch(`/api/reports/${id}`); const report = await response.json(); if (!response.ok) return alert(report.error || 'Report unavailable.'); $('#repo-name').textContent = report.repositoryName; $('#repo-url').href = report.repositoryUrl; $('#summary').innerHTML = text(report.summary); $('#architecture').innerHTML = text(report.architectureDetails); $('#recommendations').innerHTML = text(report.recommendations); $('#dashboard').classList.remove('hidden'); selectTab('tech'); window.scrollTo({top: 0, behavior:'smooth'}); }
loadHistory();
