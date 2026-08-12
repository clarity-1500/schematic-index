import { createApp } from './app.js';
import { config } from './config.js';

if (!config.ownerKey) {
  console.warn('[warn] OWNER_KEY is not set - admin and owner endpoints will be unavailable.');
}

const app = createApp();

app.listen(config.port, () => {
  console.log(`[server] The Schematic Index API on :${config.port}`);
  console.log(`[server] public base ${config.publicBase}`);
});
