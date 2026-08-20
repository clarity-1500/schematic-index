import express from 'express';
import compression from 'compression';
import { db } from './db.js';
import { config } from './config.js';
import { registerReadRoutes } from './read.js';
import { registerInteractionRoutes } from './interactions.js';
import { registerUploadRoutes } from './upload.js';
import { registerAdminRoutes } from './admin.js';
import { registerPresenceRoutes } from './presence.js';

export function createApp() {
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', 1);
  app.use(compression());
  app.use(express.json({ limit: '256kb' }));

  app.get('/', (req, res) => {
    res.json({ ok: true, service: 'schematic-index' });
  });

  app.get('/health', (req, res) => {
    const posts = db.prepare('SELECT COUNT(*) AS n FROM posts').get().n;
    res.json({ ok: true, service: 'schematic-index', version: '0.1.0', posts });
  });

  app.get('/version', (req, res) => {
    res.json({
      minVersion: config.modMinVersion,
      latestVersion: config.modLatestVersion,
      message: config.modUpdateMessage,
      modrinth: config.modrinthProject,
    });
  });

  registerReadRoutes(app);
  registerInteractionRoutes(app);
  registerUploadRoutes(app);
  registerAdminRoutes(app);
  registerPresenceRoutes(app);

  return app;
}
