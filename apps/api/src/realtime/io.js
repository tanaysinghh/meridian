// Socket.IO namespace. Clients join their org room after auth handshake.
// The dashboard subscribes to receive live PR updates as webhooks land.

import jwt from 'jsonwebtoken';

let ioRef = null;

export function attachIO(io) {
  ioRef = io;
  const live = io.of('/live');

  live.use((socket, next) => {
    try {
      // token can be passed via auth handshake payload or cookie header
      const token =
        socket.handshake.auth?.token ||
        (socket.handshake.headers?.cookie || '')
          .split(';').map(s => s.trim())
          .find(c => c.startsWith('mrd_at='))
          ?.slice('mrd_at='.length);
      if (!token) return next(new Error('unauth'));
      const decoded = jwt.verify(token, process.env.JWT_SECRET || 'dev-secret');
      socket.data.userId = decoded.sub;
      socket.data.orgId = decoded.org;
      next();
    } catch {
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
