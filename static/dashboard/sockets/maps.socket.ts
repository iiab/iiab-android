import { Socket } from 'socket.io';
import { spawn, ChildProcess } from 'child_process';
import fs from 'fs';
import path from 'path';

// Critical paths in the backend
const SCRIPTS_DIR = '/opt/iiab/maps/tile-extract/';
const EXTRACT_SCRIPT = path.join(SCRIPTS_DIR, 'tile-extract.py');
const CATALOG_JSON = '/library/www/osm/maps/extracts.json';

// --- FUNCTION: Read existing regions from JSON ---
function getMapsCatalog() {
    try {
        if (!fs.existsSync(CATALOG_JSON)) return [];
        
        console.log('[Maps] Reading extracts.json catalog...');
        const fileContent = fs.readFileSync(CATALOG_JSON, 'utf8');
        const data = JSON.parse(fileContent);
        
        // Transform JSON format {"regions": {"name": [bbox...]}} 
        // to an array manageable by the web: [{name, bbox}]
        const regions = [];
        if (data && data.regions) {
            for (const name in data.regions) {
                // Format coordinates to 4 decimals so they look clean
                const bbox = data.regions[name].map((coord: number) => coord.toFixed(4));
                regions.push({
                    name: name,
                    bbox: bbox.join(', ') // "min_lon, min_lat, max_lon, max_lat"
                });
            }
        }
        return regions;
    } catch (error) {
        console.error('[Maps] Error reading maps catalog:', error);
        return [];
    }
}

// --- FUNCTION: The Security Regex Shield (Stricter) ---
// The region name MUST be lowercase letters, numbers, or underscore ONLY.
const regionNameRegex = /^[a-z0-9_]+$/;

function validateSecureCommand(rawCommand: string): { type: string, region: string, bbox?: string, error?: string } {
    const tokens = rawCommand.trim().split(/\s+/);

    // 1. Must start with sudo and point to the correct script
    if (tokens[0] !== 'sudo' || tokens[1] !== EXTRACT_SCRIPT) {
        return { type: '', region: '', error: 'The command does not start with sudo /opt/iiab/maps/tile-extract/tile-extract.py' };
    }

    const action = tokens[2]; // 'extract' or 'delete'
    const regionName = tokens[3];

    // 2. Validate the format of the region name
    if (!regionName || !regionNameRegex.test(regionName)) {
        return { type: '', region: '', error: 'SECURITY ERROR: The region name MUST contain ONLY lowercase letters, numbers, and underscores (_).' };
    }

    if (action === 'delete' && tokens.length === 4) {
        // Variant 1: DELETE (sudo tile-extract.py delete desert1)
        return { type: 'delete', region: regionName };
    } 
    
    if (action === 'extract' && tokens.length === 5) {
        // Variant 2: DOWNLOAD (sudo tile-extract.py extract desert1 bbox)
        const bbox = tokens[4];
        if (!/^[-0-9.,]+$/.test(bbox)) {
            return { type: '', region: '', error: 'ERROR: The coordinates (Bounding Box) have an invalid format.' };
        }
        return { type: 'extract', region: regionName, bbox: bbox };
    }

    // 3. Anything else is an error
    return { type: '', region: '', error: 'Invalid command format. Only sudo [script] extract {region} {bbox} OR sudo [script] delete {region} are allowed.' };
}

export const handleMapsEvents = (socket: Socket) => {
    let scriptProcess: ChildProcess | null = null;

    // A. Send catalog to the web
    socket.on('request_maps_catalog', () => {
        const catalog = getMapsCatalog();
        socket.emit('maps_catalog_ready', catalog);
    });

    // B. Process raw order (copy-paste) with strict validation
    socket.on('start_command', (config: { rawCommand: string }) => {
        if (scriptProcess) {
            socket.emit('terminal_output', '\n[System] A process is already running.\n');
            return;
        }

        const validation = validateSecureCommand(config.rawCommand);

        if (validation.error) {
            socket.emit('terminal_output', `\n[System] SECURITY ERROR: ${validation.error}\n`);
            return;
        }

        let args = [EXTRACT_SCRIPT, validation.type, validation.region];
        if (validation.type === 'extract' && validation.bbox) args.push(validation.bbox);

        socket.emit('terminal_output', `[System] Valid command. Starting action [${validation.type}] for region: ${validation.region}...\n`);

        scriptProcess = spawn('sudo', args, { env: { ...process.env, PYTHONUNBUFFERED: '1' } });

        socket.emit('process_status', { isRunning: true });

        scriptProcess.stdout?.on('data', (data) => socket.emit('terminal_output', data.toString()));
        scriptProcess.stderr?.on('data', (data) => socket.emit('terminal_output', data.toString()));

        scriptProcess.on('exit', (code) => {
            scriptProcess = null;
            socket.emit('terminal_output', `\n[System] Process [${validation.type}] finished with code ${code}\n`);
            socket.emit('process_status', { isRunning: false });
            // Reload catalog if successful
            if (code === 0) {
                const catalog = getMapsCatalog();
                socket.emit('maps_catalog_ready', catalog);
            }
        });
    });

    // C. Process direct card deletion (Intelligent UI)
    socket.on('delete_map_region', (regionName: string) => {
        if (scriptProcess) return;

        // Double security check just in case
        if (!regionNameRegex.test(regionName)) {
            socket.emit('terminal_output', `\n[System] ERROR: Invalid region name for deletion: ${regionName}\n`);
            return;
        }

        socket.emit('terminal_output', `[System] Starting direct deletion for region: ${regionName}...\n`);
        
        scriptProcess = spawn('sudo', [EXTRACT_SCRIPT, 'delete', regionName], { env: { ...process.env, PYTHONUNBUFFERED: '1' } });
        socket.emit('process_status', { isRunning: true });
        
        scriptProcess.stdout?.on('data', (data) => socket.emit('terminal_output', data.toString()));
        scriptProcess.on('exit', (code) => {
            scriptProcess = null;
            if (code === 0) {
                socket.emit('terminal_output', `\n[System] 🗑️ Region ${regionName} successfully deleted from JSON.\n`);
                // Send updated catalog
                const catalog = getMapsCatalog();
                socket.emit('maps_catalog_ready', catalog);
            } else {
                socket.emit('terminal_output', `\n[System] Error deleting region ${regionName}. Code ${code}\n`);
            }
            socket.emit('process_status', { isRunning: false });
        });
    });

    socket.on('send_input', (input: string) => {
        if (scriptProcess && scriptProcess.stdin) {
            scriptProcess.stdin.write(`${input}\n`); 
        }
    });

    socket.on('disconnect', () => {
        if (scriptProcess) scriptProcess.kill();
    });
};