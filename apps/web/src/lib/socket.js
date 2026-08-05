import { io } from 'socket.io-client';

let socket = null;
export function getSocket() {
  if (socket) return socket;
  socket = io('/live', { path: '/live', withCredentials: true, autoConnect: true });
  return socket;
}
