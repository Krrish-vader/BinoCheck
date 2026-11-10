// ==========================================================================
// Binocheck Frontend Logic - Vanilla ES6 Javascript
// ==========================================================================

document.addEventListener('DOMContentLoaded', () => {
    // State Variables
    let currentRepoId = null;
    let currentlyLoading = false;
    let isSignUp = false;

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

    // Auth Elements
    const authOverlay = document.getElementById('auth-overlay');
    const authForm = document.getElementById('auth-form');
    const authUsername = document.getElementById('auth-username');
    const authPassword = document.getElementById('auth-password');
    const authError = document.getElementById('auth-error');
    const authSubmitBtn = document.getElementById('auth-submit-btn');
    const authToggleLink = document.getElementById('auth-toggle-link');
    const authTitle = document.getElementById('auth-title');
    const authSubtitle = document.getElementById('auth-subtitle');
    const authToggleText = document.getElementById('auth-toggle-text');
    const usernameDisplay = document.getElementById('username-display');
    const logoutBtn = document.getElementById('logout-btn');

    // ==========================================================================
    // Core Initialization
    // ==========================================================================
    checkAuthentication();
    setupTabSwitching();

    // ==========================================================================
    // Authentication Logic
    // ==========================================================================
    async function checkAuthentication() {
        try {
            const response = await fetch('/api/auth/me');
            if (response.ok) {
                const data = await response.json();
                if (data.authenticated) {
                    usernameDisplay.textContent = data.username;
                    authOverlay.classList.add('hidden');
                    loadRecentRepositories();
                } else {
                    handleAuthRequired();
                }
            } else {
                handleAuthRequired();
            }
        } catch (error) {
            console.error("Auth check failed", error);
            handleAuthRequired();
        }
    }

    function handleAuthRequired() {
        authOverlay.classList.remove('hidden');
        usernameDisplay.textContent = "Guest";
        currentRepoId = null;
        welcomeView.classList.remove('hidden');
        workspaceView.classList.add('hidden');
        chatMessages.innerHTML = '';
        recentReposList.innerHTML = '<div class="empty-state">No repositories analyzed yet.</div>';
    }

    // Toggle Login/Signup Mode
    authToggleLink.addEventListener('click', (e) => {
        e.preventDefault();
        isSignUp = !isSignUp;
        authError.classList.add('hidden');
        authUsername.value = '';
        authPassword.value = '';
        
        if (isSignUp) {
            authTitle.textContent = "Create Account";
            authSubtitle.textContent = "Sign up to start analyzing repositories";
            authSubmitBtn.querySelector('span').textContent = "Sign Up";
            authSubmitBtn.querySelector('i').className = "fa-solid fa-user-plus";
            authToggleText.textContent = "Already have an account?";
            authToggleLink.textContent = "Log In";
        } else {
            authTitle.textContent = "Welcome Back";
            authSubtitle.textContent = "Log in to analyze repositories and manage history";
            authSubmitBtn.querySelector('span').textContent = "Log In";
            authSubmitBtn.querySelector('i').className = "fa-solid fa-right-to-bracket";
            authToggleText.textContent = "Don't have an account?";
            authToggleLink.textContent = "Sign Up";
        }
    });

    // Login/Signup Submit
    authForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const username = authUsername.value.trim();
        const password = authPassword.value;
        if (!username || !password) return;

        authError.classList.add('hidden');
        authSubmitBtn.disabled = true;

        const url = isSignUp ? '/api/auth/signup' : '/api/auth/login';
        try {
            const response = await fetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password })
            });

            if (!response.ok) {
                const data = await response.json();
                throw new Error(data.message || 'Authentication failed');
            }

            if (isSignUp) {
                // Automatically log in on signup success
                isSignUp = false;
                const loginResponse = await fetch('/api/auth/login', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username, password })
                });
                if (!loginResponse.ok) {
                    throw new Error('Sign up successful, but auto-login failed. Please log in manually.');
                }
            }

            authUsername.value = '';
            authPassword.value = '';
            await checkAuthentication();
        } catch (error) {
            console.error(error);
            authError.querySelector('span').textContent = error.message;
            authError.classList.remove('hidden');
        } finally {
            authSubmitBtn.disabled = false;
        }
    });

    // Logout Click
    logoutBtn.addEventListener('click', async (e) => {
        e.preventDefault();
        try {
            await fetch('/api/auth/logout', { method: 'POST' });
        } catch (error) {
            console.error("Logout request failed", error);
        }
        handleAuthRequired();
    });

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

            if (response.status === 401) {
                handleAuthRequired();
                return;
            }

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
        // The reply belongs to the repository that was open when the question was sent.
        const repoId = currentRepoId;

        chatInput.value = '';
        appendChatMessage('USER', message);
        
        // Show typing indicator
        const typingIndicator = appendTypingIndicator();
        
        try {
            const response = await fetch('/api/chat', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ repoId, message })
            });

            if (response.status === 401) {
                typingIndicator.remove();
                handleAuthRequired();
                return;
            }

            if (!response.ok) {
                const errorData = await response.json().catch(() => ({}));
                throw new Error(errorData.message || 'Unable to fetch response from the architect.');
            }

            const data = await response.json();
            typingIndicator.remove();
            // If another repository was opened meanwhile, its chat must not receive this reply.
            // The reply is stored server-side and appears when this repository is opened again.
            if (repoId !== currentRepoId) return;
            appendChatMessage('MODEL', data.message, true);
        } catch (error) {
            console.error(error);
            typingIndicator.remove();
            if (repoId !== currentRepoId) return;
            appendChatMessage('SYSTEM', `Error: ${error.message}`);
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
        errBox.style.margin = '0';
        errBox.style.position = 'absolute';
        errBox.style.top = '16px';
        errBox.style.width = 'max-content';
        errBox.style.maxWidth = 'min(600px, calc(100% - 32px))';
        errBox.style.left = '50%';
        errBox.style.transform = 'translateX(-50%)';
        errBox.style.zIndex = '100';
        errBox.innerHTML = `<i class="fa-solid fa-triangle-exclamation" style="color: #EF4444;"></i> <span>${escapeHtml(message)}</span>`;
        
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
        repoLink.href = safeUrl(data.repoUrl);

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
                const lowerName = String(mod.name || '').toLowerCase();
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
                        <div class="module-name">${escapeHtml(mod.name)}</div>
                        <div class="module-purpose">${escapeHtml(mod.purpose)}</div>
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
            if (response.status === 401) {
                handleAuthRequired();
                return;
            }

            if (response.ok) {
                const history = await response.json();
                // A slow response for a repository that is no longer open must not fill the current chat.
                if (repoId !== currentRepoId) return;
                chatMessages.innerHTML = '';
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
            msgDiv.innerHTML = `<i class="fa-solid fa-triangle-exclamation"></i> <span>${escapeHtml(text)}</span>`;
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
            if (response.status === 401) {
                handleAuthRequired();
                return;
            }

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
                    <div class="repo-name" title="${escapeHtml(repo.repoName)}">${escapeHtml(repo.repoName)}</div>
                    <div class="repo-owner">${escapeHtml(repo.owner)}</div>
                    <div class="meta-row">
                        <div class="lang-indicator">
                            <span class="lang-dot"></span>
                            <span>${escapeHtml(repo.primaryLanguage || 'Unknown')}</span>
                        </div>
                        <div class="stars"><i class="fa-solid fa-star"></i> ${Number(repo.starsCount) || 0}</div>
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
    // Escape every character that is significant in HTML text or attribute context.
    function escapeHtml(value) {
        return String(value ?? '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    // Only allow http(s) links; anything else (javascript:, data:, ...) becomes inert.
    function safeUrl(url) {
        return /^https?:\/\//i.test(String(url ?? '').trim()) ? url : '#';
    }

    function renderMarkdown(md) {
        if (!md) return '';

        // Escape first (including quotes) so model output can never open a tag or break out of an attribute.
        // NUL is reserved below as the placeholder marker for protected code, so it is removed from the input.
        let html = escapeHtml(md).replace(/\u0000/g, '');

        // Code is taken out first and put back at the end, so the rules below (headers, bold, lists)
        // never rewrite code: a "# comment" in a shell snippet must not become a heading.
        const protectedBlocks = [];
        const protect = (blockHtml) => {
            protectedBlocks.push(blockHtml);
            return `\u0000${protectedBlocks.length - 1}\u0000`;
        };

        // Code blocks
        html = html.replace(/```([\s\S]*?)```/g, (match, code) => {
            let lines = code.trim().split('\n');
            let firstLine = lines[0].trim();
            // Remove common language classes from start of code block
            const langs = ['json', 'java', 'javascript', 'python', 'yaml', 'html', 'css', 'xml', 'bash', 'sh'];
            if (langs.includes(firstLine.toLowerCase())) {
                lines.shift();
            }
            return `\n\n${protect(`<pre><code>${lines.join('\n')}</code></pre>`)}\n\n`;
        });

        // Inline code
        html = html.replace(/`([^`\n]+)`/g, (match, code) => protect(`<code>${code}</code>`));

        // Links: [Text](URL)
        html = html.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (match, text, url) => {
            if (!/^https?:\/\//i.test(url)) return text; // drop non-http(s) links, keep their text
            return `<a href="${url}" target="_blank" rel="noopener noreferrer">${text}</a>`;
        });

        // Headers
        // Each heading becomes its own block (blank line before and after), so text on the
        // following line is still treated as a paragraph or list instead of being glued to it.
        html = html.replace(/^### (.*?)$/gm, '\n\n<h3>$1</h3>\n\n');
        html = html.replace(/^## (.*?)$/gm, '\n\n<h2>$1</h2>\n\n');
        html = html.replace(/^# (.*?)$/gm, '\n\n<h1>$1</h1>\n\n');

        // Bold text
        html = html.replace(/\*\*([\s\S]*?)\*\*/g, '<strong>$1</strong>');

        // Numbered lists ("1. item"). <oli> is a temporary tag; the input is already escaped, so it
        // cannot come from the text itself. The original number is kept through the value attribute.
        html = html.replace(/^[ \t]*(\d{1,9})[.)][ \t]+(.*?)$/gm, '<oli value="$1">$2</oli>');
        html = html.replace(/(?:<oli .*<\/oli>\n?)+/g,
            (items) => `\n\n<ol>${items.trim().replace(/<oli /g, '<li ').replace(/<\/oli>/g, '</li>')}</ol>\n\n`);

        // Bullet lists
        html = html.replace(/^[ \t]*[-*+]\s+(.*?)$/gm, '<li>$1</li>');
        
        // Wrap each run of consecutive list items (one per line) in a single <ul>
        html = html.replace(/(?:<li>.*<\/li>\n?)+/g, (items) => `\n\n<ul>${items.trim()}</ul>\n\n`);

        // Paragraphs - split by double newline and wrap in <p> if not already wrapped
        let blocks = html.split(/\n\n+/);
        html = blocks.map(block => {
            let trimmed = block.trim();
            if (!trimmed) return '';
            
            // Check if it's already structured block
            const wholeBlockIsCode = /^\u0000\d+\u0000$/.test(trimmed)
                && protectedBlocks[Number(trimmed.slice(1, -1))].startsWith('<pre');
            if (trimmed.startsWith('<h') || trimmed.startsWith('<ul') || trimmed.startsWith('<ol') || wholeBlockIsCode) {
                return trimmed;
            }
            return `<p>${trimmed.replace(/\n/g, '<br>')}</p>`;
        }).join('\n');

        return html.replace(/\u0000(\d+)\u0000/g, (match, index) => protectedBlocks[Number(index)]);
    }
});
