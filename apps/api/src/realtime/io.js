import { verifyAccess } from '../middleware/auth.js';
import { logger } from '../utils/logger.js';

// Socket.IO namespace. Clients join their org room after auth handshake.
// The dashboard subscribes to receive live PR updates as webhooks land.
let ioRef = null;

export function attachIO(io) {
  ioRef = io;
  const live = io.of('/live');

  live.use((socket, next) => {
    try {
      const token =
        socket.handshake.auth?.token ||
        (socket.handshake.headers?.cookie || '')
          .split(';').map(s => s.trim())
          .find(c => c.startsWith('mrd_at='))
          ?.slice('mrd_at='.length);
      if (!token) return next(new Error('unauth'));
      const decoded = verifyAccess(token);
      socket.data.userId = decoded.sub;
      socket.data.orgId = decoded.org;
      next();
    } catch (err) {
      logger.debug({ err: err.message }, 'socket_auth_failed');
      next(new Error('unauth'));
    }
  });

  live.on('connection', socket => {
    const room = `org:${socket.data.orgId}`;
    socket.join(room);
    socket.emit('hello', { room });
  });
}

export function emitToOrg(orgId, event, payload) {
  if (!ioRef) return;
  ioRef.of('/live').to(`org:${orgId}`).emit(event, payload);
}
