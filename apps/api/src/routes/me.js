import { Router } from 'express';
import { requireAuth } from '../middleware/auth.js';

export const meRoutes = Router();

meRoutes.get('/', requireAuth, (req, res) => {
  res.json({ user: req.user });
});
