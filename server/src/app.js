import express from 'express';
import { db } from './db.js';
import { registerReadRoutes } from './read.js';
import { registerInteractionRoutes } from './interactions.js';
import { registerUploadRoutes } from './upload.js';
import { registerAdminRoutes } from './admin.js';

/** Builds the Express app. Endpoints are added section by section. */
export function createApp() {
  const app = express();
  app.disable('x-powered-by');
  app.use(express.json({ limit: '256kb' }));

  app.get('/health', (req, res) => {
    const posts = db.prepare('SELECT COUNT(*) AS n FROM posts').get().n;
    res.json({ ok: true, service: 'schematic-index', version: '0.1.0', posts });
  });

  registerReadRoutes(app);
  registerInteractionRoutes(app);
  registerUploadRoutes(app);
  registerAdminRoutes(app);

  return app;
}
