document.addEventListener("DOMContentLoaded", () => {

    // 1. Start language system
    let userLang = (navigator.language || navigator.userLanguage).substring(0, 2).toLowerCase();
    
    // ==========================================
    // MONITORING AND DISCOVERY LOGIC
    // ==========================================

    const services = {
        'kiwix': '/kiwix/',
        'kolibri': '/kolibri/',
        'books': '/books/',
        'matomo': '/matomo/',
        'maps': '/maps/'
    };

    const statusBanner = document.getElementById("backend-status");
    const appButtons = document.querySelectorAll(".btn");

    // 2. Intelligent Discovery
    const discoverApps = async () => {
        for (const [appName, url] of Object.entries(services)) {
            const btn = document.querySelector(`.btn-${appName}`);
            if (!btn) continue;

            try {
                const response = await fetch(url, { method: "HEAD", cache: "no-store" });

                if (response.status === 404) {
                    // Not installed (hidden)
                    btn.style.display = "none";
                    btn.classList.add("not-installed");
                    delete services[appName];
                }
            } catch (error) {
                // Silent network failure, continuous monitoring will display update
            }
        }
    };

    // 3. Smart Monitor (Adaptive Polling)
    const MIN_INTERVAL = 5000;
    const MAX_INTERVAL = 60000;
    const MULTIPLIER = 1.5;
    let currentInterval = MIN_INTERVAL;

    const checkBackendStatus = async () => {
        let isServerTotallyDown = true;

        for (const [appName, url] of Object.entries(services)) {
            const btn = document.querySelector(`.btn-${appName}`);
            if (!btn || btn.classList.contains("not-installed")) continue;

            try {
                const response = await fetch(url, { method: "HEAD", cache: "no-store" });

                if (response.ok) {
                    btn.classList.remove("disabled");
                    isServerTotallyDown = false;
                } else {
                    btn.classList.add("disabled");
                }
            } catch (error) {
                btn.classList.add("disabled");
            }
        }

        // Red Banner Logic and Time Control
        if (isServerTotallyDown) {
            statusBanner.style.display = "block";
            statusBanner.classList.remove("hidden");
            currentInterval = MIN_INTERVAL; 
        } else {
            statusBanner.style.display = "none";
            statusBanner.classList.add("hidden");
            currentInterval = Math.min(currentInterval * MULTIPLIER, MAX_INTERVAL); 
        }

        setTimeout(checkBackendStatus, currentInterval);
    };

    // 4. BOOT SEQUENCE
    const init = async () => {
        await discoverApps();    
        checkBackendStatus();    
    };

    init(); 

    // ==========================================
    // 5. INTERFACE LOGIC (OVERLAY)
    // ==========================================
    const overlay = document.getElementById('loadingOverlay');
    const textLabel = document.getElementById('loadingText');

    appButtons.forEach(btn => {
        btn.addEventListener('click', function(e) {
            if (this.classList.contains('disabled') || this.classList.contains('not-installed')) {
                e.preventDefault();
                return;
            }

            const appName = this.innerText.replace(/[\uD800-\uDBFF][\uDC00-\uDFFF]|\uD83C[\uDF00-\uDFFF]|\uD83D[\uDC00-\uDE4F]/g, '').trim();
            textLabel.innerText = 'Opening ' + appName + '...';
            overlay.style.display = 'flex';
        });
    });

    window.addEventListener('pageshow', function(event) {
        overlay.style.display = 'none';
    });
});
