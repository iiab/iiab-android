import express from 'express';
import http from 'http';
import { Server, Socket } from 'socket.io';
import path from 'path';
import helmet from 'helmet';

// Import our modules (event controllers)
import { handleMapsEvents } from './sockets/maps.socket';
import { handleKiwixEvents } from './sockets/kiwix.socket';
import { handleHomeEvents } from './sockets/home.socket';

const app = express();
const server = http.createServer(app);
const io = new Server(server);

// 🛡️ SECURITY SHIELD (HELMET)
// ==========================================
app.use(helmet({
    // No https, no SSL, no HSTS.
    hsts: false,
    // Check websockets works without restrictions
    contentSecurityPolicy: false
}));

// EJS views and static files configuration
app.set('view engine', 'ejs');
app.set('views', path.join(__dirname, 'views'));
app.use(express.static(path.join(__dirname, 'public')));

// Main route
app.get('/', (req, res) => {
    res.render('index'); 
});

// Main Socket Connection Handler
io.on('connection', (socket: Socket) => {
    console.log(`A client has connected (ID: ${socket.id}).`);
    
    // Connect the wires to the modules
    handleMapsEvents(socket);
    handleKiwixEvents(socket);
    handleHomeEvents(socket);
    
    socket.on('disconnect', () => {
        console.log(`Client disconnected (ID: ${socket.id}).`);
    });
});

const PORT = 4000;
server.listen(PORT, () => {
    console.log(`===========================================`);
    console.log(`IIAB-oA Dashboard active on port ${PORT}`);
    console.log(`===========================================`);
});

// ==========================================
// Graceful Shutdown
// ==========================================

// Function to handle the shutdown process
const gracefulShutdown = (signal: string) => {
    console.log(`\n[System] Received ${signal}. Starting graceful shutdown...`);

    // Stop accepting new connections
    server.close(() => {
        console.log('[System] HTTP server closed. No longer accepting connections.');

        // TODO: Add cleanly stop aria2c or python scripts if they are running

        console.log('[System] Cleanup complete. Exiting safely.');
        process.exit(0);
    });

    // If the server takes too long to close (e.g., stuck websockets), force it after 5 seconds
    setTimeout(() => {
        console.error('[System] Could not close connections in time. Forcing shutdown.');
        process.exit(1);
    }, 5000);
};

// Listen for PDSM pkill
process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));

// Listen for CTRL+C in the terminal
process.on('SIGINT', () => gracefulShutdown('SIGINT'));
