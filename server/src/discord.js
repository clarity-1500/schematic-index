import { config } from './config.js';

export function reportToDiscord({ post, reason, note, count }) {
  if (!config.discordWebhook) {
    return;
  }

  const payload = {
    username: 'Schematic Index',
    embeds: [
      {
        title: `Report: ${post.title}`.slice(0, 240),
        description: `Review in the admin panel: ${config.publicBase}/admin`,
        color: 0x2a7a5b,
        fields: [
          { name: 'Reason', value: reason, inline: true },
          { name: 'Total reports', value: String(count), inline: true },
          { name: 'Poster', value: post.poster || '(unknown)', inline: true },
          { name: 'Post ID', value: `\`${post.id}\`` },
          { name: 'Note', value: (note || '(none)').slice(0, 900) },
        ],
        timestamp: new Date().toISOString(),
      },
    ],
  };

  fetch(config.discordWebhook, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  }).catch((e) => console.warn('[discord] report webhook failed:', e.message));
}
