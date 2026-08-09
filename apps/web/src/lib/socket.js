import { io } from 'socket.io-client';

let socket = null;
export function getSocket() {
  if (socket) return socket;
  // In prod, Socket.IO must connect to the API origin (VITE_API_BASE).
  // In dev, no origin → connects to Vite's origin and lets its proxy pass through.
  const base = (import.meta.env?.VITE_API_BASE || '').replace(/\/$/, '');
  socket = io(base ? `${base}/live` : '/live', {
    path: '/live',
    withCredentials: true,
    autoConnect: true
  });
  return socket;
}
