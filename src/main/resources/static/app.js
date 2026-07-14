// ==========================================================================
// Binocheck Frontend Logic - Vanilla ES6 Javascript
// ==========================================================================

document.addEventListener('DOMContentLoaded', () => {
    // State Variables
    let currentRepoId = null;
    let currentlyLoading = false;

    // DOM Elements
    const welcomeView = document.getElementById('welcome-view');
    const loadingView = document.getElementById('loading-view');
    const workspaceView = document.getElementById('workspace-view');
    const analyzeForm = document.getElementById('analyze-form');
    const repoUrlInput = document.getElementById('repo-url-input');
    const analyzeBtn = document.getElementById('analyze-btn');
    const recentReposList = document.getElementById('recent-repos-list');
    
    // Workspace Elements
    const repoTitle = document.getElementById('repo-title');
    const repoDescription = document.getElementById('repo-description');
    const repoLangBadge = document.getElementById('repo-lang-badge');
    const repoStarsBadge = document.getElementById('repo-stars-badge');
    const repoLink = document.getElementById('repo-link');
    
    // Overview Tab
    const techStackBadges = document.getElementById('tech-stack-badges');
    const architectureSummaryText = document.getElementById('architecture-summary-text');
    
    // Modules Tab
    const moduleStructureList = document.getElementById('module-structure-list');
    
    // Deep Dive Tab
    const detailedAnalysisText = document.getElementById('detailed-analysis-text');
    
    // Chat Elements
    const chatMessages = document.getElementById('chat-messages');
    const chatForm = document.getElementById('chat-form');
    const chatInput = document.getElementById('chat-input');
    const chatSendBtn = document.getElementById('chat-send-btn');

    // ==========================================================================
    // Core Initialization
    // ==========================================================================
    loadRecentRepositories();
    setupTabSwitching();

    // ==========================================================================
    // Event Listeners
    // ==========================================================================

    // Analyze Submit
    analyzeForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const repoUrl = repoUrlInput.value.trim();
        if (!repoUrl || currentlyLoading) return;

        showLoading(true, "Scanning GitHub repository metadata...");
        
        try {
            const response = await fetch('/api/analyze', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ repoUrl })
            });

            if (!response.ok) {
                const errorData = await response.json();
                throw new Error(errorData.message || 'Failed to analyze repository');
            }

            const data = await response.json();
            loadWorkspace(data);
            loadRecentRepositories(); // Refresh list
        } catch (error) {
            console.error(error);
            showError(error.message);
        } finally {
            showLoading(false);
        }
    });

    // Chat Submit
    chatForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const message = chatInput.value.trim();
        if (!message || !currentRepoId) return;

        chatInput.value = '';
        appendChatMessage('USER', message);
        
        // Show typing indicator
        const typingIndicator = appendTypingIndicator();
        
        try {
            const response = await fetch('/api/chat', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ repoId: currentRepoId, message })
            });

            if (!response.ok) {
                throw new Error('Failed to get chat response');
            }

            const data = await response.json();
            typingIndicator.remove();
            appendChatMessage('MODEL', data.message, true);
        } catch (error) {
            console.error(error);
            typingIndicator.remove();
            appendChatMessage('SYSTEM', 'Error: Unable to fetch response from the architect.');
        }
    });

    // ==========================================================================
    // UI Render Helpers
    // ==========================================================================

    function showLoading(isLoading, message = "Analyzing...") {
        currentlyLoading = isLoading;
        analyzeBtn.disabled = isLoading;
        repoUrlInput.disabled = isLoading;

        if (isLoading) {
            document.getElementById('loading-status-text').textContent = message;
            welcomeView.classList.add('hidden');
            workspaceView.classList.add('hidden');
            loadingView.classList.remove('hidden');
        } else {
            loadingView.classList.add('hidden');
        }
    }

    function showError(message) {
        welcomeView.classList.remove('hidden');
        workspaceView.classList.add('hidden');
        
        // Render a clean error box temporarily above welcome card
        let errBox = document.createElement('div');
        errBox.className = 'system-message';
        errBox.style.borderColor = '#EF4444';
        errBox.style.background = 'rgba(239, 68, 68, 0.08)';
        errBox.style.color = '#FCA5A5';
        errBox.style.margin = '20px auto';
        errBox.style.maxWidth = '600px';
        errBox.style.position = 'absolute';
        errBox.style.top = '100px';
        errBox.style.left = '50%';
        errBox.style.transform = 'translateX(-50%)';
        errBox.style.zIndex = '100';
        errBox.innerHTML = `<i class="fa-solid fa-triangle-exclamation" style="color: #EF4444;"></i> <span>${message}</span>`;
        
        document.querySelector('.dashboard-body').appendChild(errBox);
        setTimeout(() => errBox.remove(), 6000);
    }

    function loadWorkspace(data) {
        currentRepoId = data.id;
        
        // Populate inputs and search box if selected from sidebar
        repoUrlInput.value = data.repoUrl;

        // Header Metadata
        repoTitle.textContent = `${data.owner} / ${data.repoName}`;
        repoDescription.textContent = data.description || 'No description available for this repository.';
        
        if (data.primaryLanguage) {
            repoLangBadge.querySelector('span').textContent = data.primaryLanguage;
            repoLangBadge.classList.remove('hidden');
        } else {
            repoLangBadge.classList.add('hidden');
        }
        
        repoStarsBadge.querySelector('span').textContent = data.starsCount || 0;
        repoLink.href = data.repoUrl;

        // Parse Analysis result
        let analysis;
        try {
            analysis = JSON.parse(data.analysisResult);
        } catch (e) {
            console.error('Error parsing analysis JSON', e);
            // Fallback object in case Gemini fails strict json parsing
            analysis = {
                techStack: [data.primaryLanguage || 'Unknown'],
                architectureSummary: 'Detailed analysis JSON structure is malformed, showing raw detailed text.',
                moduleStructure: [],
                detailedAnalysis: data.analysisResult
            };
        }

        // 1. Tech Stack Badges
        techStackBadges.innerHTML = '';
        if (analysis.techStack && analysis.techStack.length > 0) {
            analysis.techStack.forEach(tech => {
                const span = document.createElement('span');
                span.className = 'tech-badge';
                span.textContent = tech;
                techStackBadges.appendChild(span);
            });
        } else {
            techStackBadges.innerHTML = '<span class="text-muted">No explicit technologies identified.</span>';
        }

        // 2. Architecture Summary
        architectureSummaryText.textContent = analysis.architectureSummary || 'No summary provided.';

        // 3. Module Structure
        moduleStructureList.innerHTML = '';
        if (analysis.moduleStructure && analysis.moduleStructure.length > 0) {
            analysis.moduleStructure.forEach(mod => {
                const item = document.createElement('div');
                item.className = 'module-item';
                
                // Determine icon based on name (e.g. source, test, config, package, etc.)
                let iconClass = 'fa-folder';
                const lowerName = mod.name.toLowerCase();
                if (lowerName.includes('test')) iconClass = 'fa-vial';
                else if (lowerName.includes('config') || lowerName.includes('properties') || lowerName.endsWith('.yml') || lowerName.endsWith('.xml')) iconClass = 'fa-gears';
                else if (lowerName.includes('controller') || lowerName.includes('api') || lowerName.includes('rest')) iconClass = 'fa-network-wired';
                else if (lowerName.includes('service') || lowerName.includes('impl')) iconClass = 'fa-server';
                else if (lowerName.includes('entity') || lowerName.includes('model') || lowerName.includes('repo')) iconClass = 'fa-database';
                else if (lowerName.endsWith('readme.md')) iconClass = 'fa-file-lines';
                else if (lowerName.includes('static') || lowerName.includes('public') || lowerName.includes('css') || lowerName.includes('js')) iconClass = 'fa-pager';

                item.innerHTML = `
                    <div class="module-icon-wrap">
                        <i class="fa-solid ${iconClass}"></i>
                    </div>
                    <div class="module-info">
                        <div class="module-name">${mod.name}</div>
                        <div class="module-purpose">${mod.purpose}</div>
                    </div>
                `;
                moduleStructureList.appendChild(item);
            });
        } else {
            moduleStructureList.innerHTML = '<div class="empty-state">No directory components documented.</div>';
        }

        // 4. Detailed Analysis
        detailedAnalysisText.innerHTML = renderMarkdown(analysis.detailedAnalysis || 'No detailed analysis provided.');

        // Load Chat history
        loadChatHistory(data.id);

        // Switch to Workspace
        welcomeView.classList.add('hidden');
        workspaceView.classList.remove('hidden');

        // Reset to first tab
        document.querySelector('.tab-btn[data-tab="tab-overview"]').click();
    }

    async function loadChatHistory(repoId) {
        chatMessages.innerHTML = '';
        try {
            const response = await fetch(`/api/repos/${repoId}/chat`);
            if (response.ok) {
                const history = await response.json();
                if (history.length > 0) {
                    history.forEach(msg => {
                        appendChatMessage(msg.role, msg.message);
                    });
                } else {
                    // Default system prompt
                    chatMessages.innerHTML = `
                        <div class="system-message">
                            <i class="fa-solid fa-robot"></i>
                            <span>I've mapped the repository's files and packages. Ask me anything about how components interact, where specific modules are, or how to set it up.</span>
                        </div>
                    `;
                }
            }
        } catch (error) {
            console.error('Failed to load history', error);
        }
    }

    function appendChatMessage(role, text, alignTop = false) {
        const msgDiv = document.createElement('div');
        msgDiv.className = `chat-message ${role.toLowerCase()}`;
        
        if (role === 'MODEL') {
            msgDiv.innerHTML = `<div class="markdown-body">${renderMarkdown(text)}</div>`;
        } else if (role === 'SYSTEM') {
            msgDiv.className = 'system-message';
            msgDiv.innerHTML = `<i class="fa-solid fa-triangle-exclamation"></i> <span>${text}</span>`;
        } else {
            msgDiv.textContent = text;
        }

        chatMessages.appendChild(msgDiv);
        
        if (alignTop) {
            chatMessages.scrollTo({
                top: msgDiv.offsetTop - 16,
                behavior: 'smooth'
            });
        } else {
            chatMessages.scrollTop = chatMessages.scrollHeight;
        }
    }

    function appendTypingIndicator() {
        const indDiv = document.createElement('div');
        indDiv.className = 'chat-message model';
        indDiv.innerHTML = `
            <div class="typing-dots">
                <span></span><span></span><span></span>
            </div>
        `;
        chatMessages.appendChild(indDiv);
        chatMessages.scrollTop = chatMessages.scrollHeight;
        return indDiv;
    }

    async function loadRecentRepositories() {
        try {
            const response = await fetch('/api/repos');
            if (!response.ok) return;
            const repos = await response.json();

            if (repos.length === 0) {
                recentReposList.innerHTML = '<div class="empty-state">No repositories analyzed yet.</div>';
                return;
            }

            recentReposList.innerHTML = '';
            repos.forEach(repo => {
                const card = document.createElement('div');
                card.className = `history-card ${repo.id === currentRepoId ? 'active' : ''}`;
                
                card.innerHTML = `
                    <div class="repo-name" title="${repo.repoName}">${repo.repoName}</div>
                    <div class="repo-owner">${repo.owner}</div>
                    <div class="meta-row">
                        <div class="lang-indicator">
                            <span class="lang-dot"></span>
                            <span>${repo.primaryLanguage || 'Unknown'}</span>
                        </div>
                        <div class="stars"><i class="fa-solid fa-star"></i> ${repo.starsCount || 0}</div>
                    </div>
                `;

                card.addEventListener('click', () => {
                    // Update active styling
                    document.querySelectorAll('.history-card').forEach(c => c.classList.remove('active'));
                    card.classList.add('active');
                    loadWorkspace(repo);
                });

                recentReposList.appendChild(card);
            });
        } catch (error) {
            console.error('Failed to load recent repos', error);
        }
    }

    function setupTabSwitching() {
        document.querySelectorAll('.tab-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const targetTab = btn.getAttribute('data-tab');
                
                // Toggle active buttons
                document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');

                // Toggle active contents
                document.querySelectorAll('.tab-content').forEach(content => {
                    content.classList.remove('active');
                });
                document.getElementById(targetTab).classList.add('active');
            });
        });
    }

    // ==========================================================================
    // Simple Markdown Renderer
    // ==========================================================================
    function renderMarkdown(md) {
        if (!md) return '';
        let html = md;
        
        // Escape HTML to prevent XSS (keep basic tag rendering intact if we use it)
        html = html
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;");
            
        // Code blocks
        html = html.replace(/```([\s\S]*?)```/g, (match, code) => {
            let lines = code.trim().split('\n');
            let firstLine = lines[0].trim();
            // Remove common language classes from start of code block
            const langs = ['json', 'java', 'javascript', 'python', 'yaml', 'html', 'css', 'xml', 'bash', 'sh'];
            if (langs.includes(firstLine.toLowerCase())) {
                lines.shift();
            }
            return `<pre><code>${lines.join('\n')}</code></pre>`;
        });

        // Links: [Text](URL)
        html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank">$1</a>');

        // Inline code
        html = html.replace(/`([^`\n]+)`/g, '<code>$1</code>');

        // Headers
        html = html.replace(/^### (.*?)$/gm, '<h3>$1</h3>');
        html = html.replace(/^## (.*?)$/gm, '<h2>$1</h2>');
        html = html.replace(/^# (.*?)$/gm, '<h1>$1</h1>');

        // Bold text
        html = html.replace(/\*\*([\s\S]*?)\*\*/g, '<strong>$1</strong>');

        // Bullet lists
        html = html.replace(/^\s*[-*+]\s+(.*?)$/gm, '<li>$1</li>');
        
        // Wrap contiguous list items in <ul>
        // We look for sequences of <li>...</li> and wrap them in <ul>
        html = html.replace(/(<li>.*?<\/li>)+/gs, '<ul>$&</ul>');

        // Paragraphs - split by double newline and wrap in <p> if not already wrapped
        let blocks = html.split(/\n\n+/);
        html = blocks.map(block => {
            let trimmed = block.trim();
            if (!trimmed) return '';
            
            // Check if it's already structured block
            if (trimmed.startsWith('<h') || trimmed.startsWith('<pre') || trimmed.startsWith('<ul') || trimmed.startsWith('<li>')) {
                return trimmed;
            }
            return `<p>${trimmed.replace(/\n/g, '<br>')}</p>`;
        }).join('\n');

        return html;
    }
});
