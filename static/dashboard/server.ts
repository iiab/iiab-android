import express from 'express';
import http from 'http';
import { Server, Socket } from 'socket.io';
import path from 'path';

// Import our modules (event controllers)
import { handleMapsEvents } from './sockets/maps.socket';
import { handleKiwixEvents } from './sockets/kiwix.socket';
import { handleHomeEvents } from './sockets/home.socket';

const app = express();
const server = http.createServer(app);
const io = new Server(server);

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
    console.log(`🚀 IIAB-oA Dashboard active on port ${PORT}`);
    console.log(`===========================================`);
});