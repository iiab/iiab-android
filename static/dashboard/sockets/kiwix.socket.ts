import { Socket } from 'socket.io';
import { spawn, ChildProcess } from 'child_process';
import fs from 'fs';
import path from 'path';

const ZIMS_DIR = '/library/zims/content/';

async function getKiwixCatalog() {
    try {
        console.log('[Kiwix] Querying server and cross-referencing with local disk...');
        const response = await fetch('https://download.kiwix.org/zim/wikipedia/');
        const html = await response.text();
        
        let localFiles = new Set<string>();
        if (fs.existsSync(ZIMS_DIR)) {
            localFiles = new Set(fs.readdirSync(ZIMS_DIR));
        }
        
        const regex = /<a href="([^"]+\.zim)">.*?<\/a>\s+([\d-]+ \d{2}:\d{2})\s+([0-9.]+[KMG]?)/g;
        const results = [];
        let match;
        
        while ((match = regex.exec(html)) !== null) {
            const file = match[1];
            const fullDate = match[2];
            const size = match[3];
            
            const cleanTitle = file.replace('.zim', '').split(/[-_]/).map((p, index) => {
                if (index === 1) return p.toUpperCase();
                return p.charAt(0).toUpperCase() + p.slice(1);
            }).join(' ');

            const isDownloaded = localFiles.has(file);
            
            results.push({
                id: file,
                title: cleanTitle,
                date: fullDate.split(' ')[0],
                size: size,
                isDownloaded: isDownloaded
            });
        }
        return results;
    } catch (error) {
        console.error('[Kiwix] Error querying the catalog:', error);
        return [];
    }
}

export const handleKiwixEvents = (socket: Socket) => {
    let downloadProcess: ChildProcess | null = null;
    let indexProcess: ChildProcess | null = null;
    let currentDownloads: string[] = [];

    socket.on('request_kiwix_catalog', async () => {
        socket.emit('kiwix_status', { message: 'Synchronizing catalog with local disk...' });
        const catalog = await getKiwixCatalog();
        socket.emit('kiwix_catalog_ready', catalog);
    });

    socket.on('check_kiwix_tools', () => {
        const hasAria2 = fs.existsSync('/usr/bin/aria2c');
        const hasIndexer = fs.existsSync('/usr/bin/iiab-make-kiwix-lib');
        socket.emit('kiwix_tools_status', { hasAria2, hasIndexer });
    });

    socket.on('start_kiwix_download', (zims: string[]) => {
        if (downloadProcess || indexProcess) {
            socket.emit('kiwix_terminal_output', '\n[System] A process is already running.\n');
            return;
        }
        if (zims.length === 0) return;
        
        currentDownloads = zims; // Save what we are downloading
        const baseUrl = 'https://download.kiwix.org/zim/wikipedia/';
        const urls = zims.map(zim => baseUrl + zim);
        
        socket.emit('kiwix_terminal_output', `\n[System] Starting download...\n`);
        socket.emit('kiwix_process_status', { isRunning: true });
        
        const args = ['-d', ZIMS_DIR, '-c', '-Z', '-x', '4', '-s', '4', '-j', '5', '--async-dns=false', ...urls];
        downloadProcess = spawn('/usr/bin/aria2c', args);
        
        downloadProcess.stdout?.on('data', (data) => socket.emit('kiwix_terminal_output', data.toString()));
        downloadProcess.stderr?.on('data', (data) => socket.emit('kiwix_terminal_output', data.toString()));
        
        // Handle natural process exit
        downloadProcess.on('exit', (code, signal) => {
            downloadProcess = null;
            
            // If killed manually (Cancellation), ignore this block
            if (signal === 'SIGKILL') return;
            
            if (code === 0) {
                currentDownloads = []; 
                socket.emit('kiwix_terminal_output', `\n[System] 🟢 Downloads finished. Cleaning metadata...\n`);

                // Sweep left-over metadata
                try {
                    const files = fs.readdirSync(ZIMS_DIR);
                    files.forEach(file => {
                        if (file.endsWith('.meta4') || file.endsWith('.aria2') || file.endsWith('.torrent')) {
                            fs.unlinkSync(path.join(ZIMS_DIR, file));
                        }
                    });
                } catch (err) {
                    console.error('[System] Minor error cleaning metadata files:', err);
                }

                socket.emit('kiwix_terminal_output', `[System] 🟢 Starting indexing...\n`);
                if (fs.existsSync('/usr/bin/iiab-make-kiwix-lib')) {
                    indexProcess = spawn('/usr/bin/iiab-make-kiwix-lib');
                    indexProcess.stdout?.on('data', (data) => socket.emit('kiwix_terminal_output', data.toString()));
                    
                    indexProcess.on('exit', (idxCode) => {
                        socket.emit('kiwix_terminal_output', `\n[System] 🏁 Indexing complete.\n`);
                        indexProcess = null;
                        socket.emit('kiwix_process_status', { isRunning: false });
                        socket.emit('refresh_kiwix_catalog'); 
                    });
                } else {
                    socket.emit('kiwix_process_status', { isRunning: false });
                    socket.emit('refresh_kiwix_catalog');
                }
            } else {
                currentDownloads = [];
                socket.emit('kiwix_process_status', { isRunning: false });
            }
        });
    });

    // CANCEL DOWNLOAD BUTTON
    socket.on('cancel_kiwix_download', () => {
        if (downloadProcess) {
            socket.emit('kiwix_terminal_output', '\n[System] 🛑 ABORTING: Killing Aria2c process...\n');
            
            // 1. Kill the process (Ctrl+C equivalent)
            downloadProcess.kill('SIGKILL');
            downloadProcess = null;
            
            // 2. Sweep the trash (Incomplete files)
            socket.emit('kiwix_terminal_output', '[System] 🧹 Cleaning temporary and incomplete files...\n');
            currentDownloads.forEach(zim => {
                const filePath = path.join(ZIMS_DIR, zim);
                const ariaPath = filePath + '.aria2';
                
                if (fs.existsSync(filePath)) fs.unlinkSync(filePath); // Delete the .zim
                if (fs.existsSync(ariaPath)) fs.unlinkSync(ariaPath); // Delete the .aria2 map
            });
            
            currentDownloads = []; // Clear memory
            
            socket.emit('kiwix_terminal_output', '[System] ✔️ Cancellation complete. System ready.\n');
            socket.emit('kiwix_process_status', { isRunning: false });
            socket.emit('refresh_kiwix_catalog');
        }
    });

    // Delete ZIMs from UI
    socket.on('delete_zim', (zimId: string) => {
        const filePath = path.join(ZIMS_DIR, zimId);
        if (fs.existsSync(filePath)) {
            socket.emit('kiwix_terminal_output', `\n[System] 🗑️ Deleting file ${zimId}...\n`);
            fs.unlinkSync(filePath); 
            
            if (fs.existsSync('/usr/bin/iiab-make-kiwix-lib')) {
                const idx = spawn('/usr/bin/iiab-make-kiwix-lib');
                idx.stdout?.on('data', (data) => socket.emit('kiwix_terminal_output', data.toString()));
                idx.stderr?.on('data', (data) => socket.emit('kiwix_terminal_output', data.toString()));
                
                idx.on('exit', () => {
                    socket.emit('kiwix_terminal_output', `\n[System] 🏁 Index updated after deletion. Reloading interface...\n`);
                    socket.emit('refresh_kiwix_catalog'); 
                });
            } else {
                socket.emit('kiwix_terminal_output', `\n[System] ✅ File deleted (no indexer). Reloading interface...\n`);
                socket.emit('refresh_kiwix_catalog');
            }
        }
    });

    socket.on('disconnect', () => {
        if (downloadProcess) downloadProcess.kill('SIGKILL');
        if (indexProcess) indexProcess.kill('SIGKILL');
    });
};