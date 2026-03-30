// ==========================================
// IIAB-oA Dashboard - Core Logic
// ==========================================

const socket = io();

// --- Theme Manager ---
const themeManager = {
    init() {
        const savedTheme = localStorage.getItem('theme') || 'auto';
        this.setTheme(savedTheme);
    },
    setTheme(theme) {
        const root = document.documentElement;
        if (theme === 'auto') {
            const systemTheme = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
            root.setAttribute('data-bs-theme', systemTheme);
        } else {
            root.setAttribute('data-bs-theme', theme);
        }
        localStorage.setItem('theme', theme);
        this.updateToggleButton(theme);
    },
    cycleTheme() {
        const current = localStorage.getItem('theme') || 'auto';
        const next = current === 'light' ? 'dark' : (current === 'dark' ? 'auto' : 'light');
        this.setTheme(next);
    },
    updateToggleButton(theme) {
        const iconSpan = document.getElementById('theme-toggle-icon');
        if (!iconSpan) return;
        const icons = { light: '☀️', dark: '🌙', auto: '🌓' };
        iconSpan.innerText = icons[theme] || icons.auto;
    }
};

// --- i18n System ---
const applyTranslations = () => {
    if (!window.i18n) return;
    document.querySelectorAll('[data-i18n]').forEach(el => {
        const key = el.getAttribute('data-i18n');
        if (window.i18n[key]) el.innerText = window.i18n[key];
    });
};

const loadLanguage = () => {
    const userLang = (navigator.language || navigator.userLanguage).substring(0, 2).toLowerCase();
    const lang = ['es', 'en'].includes(userLang) ? userLang : 'en';
    const script = document.createElement('script');
    script.src = `lang/${lang}.js`;
    script.onload = applyTranslations;
    document.head.appendChild(script);
};

// --- Tabs Logic ---
function switchTab(tabName) {
    document.querySelectorAll('.nav-link').forEach(el => el.classList.remove('active'));
    document.querySelectorAll('.app-panel').forEach(el => el.classList.add('hidden-section'));

    document.getElementById(`tab-${tabName}`).classList.add('active');
    document.getElementById(`panel-${tabName}`).classList.remove('hidden-section');
}

document.addEventListener("DOMContentLoaded", () => {
    themeManager.init();
    loadLanguage();
    switchTab('home');
});
