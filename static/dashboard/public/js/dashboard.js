// ==========================================
// IIAB-oA Dashboard - Core Logic
// ==========================================

const socket = io();

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
    loadLanguage();
    switchTab('home');
});
