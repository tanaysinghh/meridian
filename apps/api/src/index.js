import 'dotenv/config';
import express from 'express';
import cors from 'cors';
import cookieParser from 'cookie-parser';
import http from 'node:http';
import { Server as IOServer } from 'socket.io';

import { authRoutes } from './routes/auth.js';
import { meRoutes } from './routes/me.js';
import { prRoutes } from './routes/prs.js';
import { reviewerRoutes } from './routes/reviewers.js';
import { analyticsRoutes } from './routes/analytics.js';
import { repoRoutes } from './routes/repos.js';
import { rulesRoutes } from './routes/rules.js';
import { incidentRoutes } from './routes/incidents.js';
import { settingsRoutes } from './routes/settings.js';
import { webhookRoutes } from './webhooks/github.js';
import { attachIO } from './realtime/io.js';
import { errorHandler } from './middleware/error.js';

const PORT = process.env.PORT || 4000;
const WEB_ORIGIN = process.env.WEB_ORIGIN || 'http://localhost:5173';

const app = express();
app.use(cors({ origin: WEB_ORIGIN, credentials: true }));
app.use(cookieParser());

// GitHub webhooks need raw body for signature verification — mount BEFORE json parser
app.use('/webhooks/github', express.raw({ type: '*/*', limit: '5mb' }), webhookRoutes);

app.use(express.json({ limit: '2mb' }));

app.get('/health', (_, res) => res.json({ ok: true, service: 'meridian-api' }));

app.use('/auth', authRoutes);
app.use('/me', meRoutes);
app.use('/prs', prRoutes);
app.use('/reviewers', reviewerRoutes);
app.use('/analytics', analyticsRoutes);
app.use('/repos', repoRoutes);
app.use('/rules', rulesRoutes);
app.use('/incidents', incidentRoutes);
app.use('/settings', settingsRoutes);

app.use(errorHandler);

const server = http.createServer(app);
const io = new IOServer(server, {
  cors: { origin: WEB_ORIGIN, credentials: true },
  path: '/live'
});
attachIO(io);

server.listen(PORT, () => {
  console.log(`[api] listening on http://localhost:${PORT}`);
});
