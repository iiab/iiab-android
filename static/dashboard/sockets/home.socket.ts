import { Socket } from 'socket.io';
import os from 'os';
import { exec } from 'child_process';
import util from 'util';

const execPromise = util.promisify(exec);

async function getSystemStats() {
    let stats = {
        hostname: os.hostname(),
        ip: 'Unknown',
        uptime: os.uptime(),
        disk: { total: 0, used: 0, free: 0, percent: 0 },
        ram: { total: 0, used: 0, percent: 0 },
        swap: { total: 0, used: 0, percent: 0 }
    };

    // 1. GET IP (Plan A: Native | Plan B: raw ifconfig)
    try {
        const nets = os.networkInterfaces();
        for (const name of Object.keys(nets)) {
            if (nets[name]) {
                for (const net of nets[name]) {
                    if (net.family === 'IPv4' && !net.internal) {
                        stats.ip = net.address; break;
                    }
                }
            }
            if (stats.ip !== 'Unknown') break;
        }
    } catch (e) {}

    // Architect's Plan B: Your ifconfig pipeline silencing the proot error
    if (stats.ip === 'Unknown') {
        try {
            // The 2>/dev/null sends the "Warning: Permission denied" to the abyss
            const { stdout } = await execPromise("ifconfig 2>/dev/null | grep inet | grep -v 127.0.0.1 | awk '{print $2}'");
            
            const ips = stdout.trim().split('\n');
            if (ips.length > 0 && ips[0]) {
                stats.ip = ips[0]; // We take the first valid IP it spits out
            }
        } catch (e) {
            console.log('[Home] Plan B (ifconfig) failed or yielded no results.');
        }
    }

    // 2. GET DISK (df -k /)
    try {
        const { stdout } = await execPromise('df -k /');
        const lines = stdout.trim().split('\n');
        if (lines.length > 1) {
            const parts = lines[1].trim().split(/\s+/);
            const total = parseInt(parts[1]) * 1024;
            const used = parseInt(parts[2]) * 1024;
            stats.disk = {
                total, used, free: total - used,
                percent: Math.round((used / total) * 100)
            };
        }
    } catch (e) {}

    // 3. GET RAM AND SWAP (free -b)
    try {
        const { stdout } = await execPromise('free -b');
        const lines = stdout.trim().split('\n');
        
        // Parse RAM (Line 2) -> Mem: total used free ...
        if (lines.length > 1) {
            const ramParts = lines[1].trim().split(/\s+/);
            const rTotal = parseInt(ramParts[1]);
            const rUsed = parseInt(ramParts[2]);
            stats.ram = { total: rTotal, used: rUsed, percent: Math.round((rUsed / rTotal) * 100) };
        }
        
        // Parse Swap (Line 3) -> Swap: total used free ...
        if (lines.length > 2) {
            const swapParts = lines[2].trim().split(/\s+/);
            const sTotal = parseInt(swapParts[1]);
            const sUsed = parseInt(swapParts[2]);
            // Avoid division by zero if there is no Swap configured
            stats.swap = { total: sTotal, used: sUsed, percent: sTotal > 0 ? Math.round((sUsed / sTotal) * 100) : 0 };
        }
    } catch (e) {
        console.log('[Home] Error reading memory (free -b). Using limited native data.');
        // Ultra-safe Node native fallback (Only gives RAM, no Swap)
        stats.ram.total = os.totalmem();
        const free = os.freemem();
        stats.ram.used = stats.ram.total - free;
        stats.ram.percent = Math.round((stats.ram.used / stats.ram.total) * 100);
    }

    return stats;
}

function formatBytes(bytes: number) {
    if (bytes === 0 || isNaN(bytes)) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

export const handleHomeEvents = (socket: Socket) => {
    socket.on('request_home_stats', async () => {
        const stats = await getSystemStats();
        
        const uiData = {
            hostname: stats.hostname,
            ip: stats.ip,
            uptime: Math.floor(stats.uptime / 60) + ' min',
            diskTotal: formatBytes(stats.disk.total),
            diskUsed: formatBytes(stats.disk.used),
            diskFree: formatBytes(stats.disk.free),
            diskPercent: stats.disk.percent,
            ramUsed: formatBytes(stats.ram.used),
            ramTotal: formatBytes(stats.ram.total),
            ramPercent: stats.ram.percent,
            swapUsed: formatBytes(stats.swap.used),
            swapTotal: formatBytes(stats.swap.total),
            swapPercent: stats.swap.percent
        };
        
        socket.emit('home_stats_ready', uiData);
    });
};