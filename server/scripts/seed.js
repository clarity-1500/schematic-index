import { db } from '../src/db.js';

const AUG = 7;

const NEWS = [
  ['v0.8', 'Onboarding and terms', 'Aug 12, 2026', 12, 8, 1,
    'First-time visitors now get a short guided tour of the layout, and a terms of service you agree to before the online catalogue connects.\n\nDownloads that would replace a file you already have now ask before overwriting.'],
  ['v0.7', 'Report a post', 'Aug 12, 2026', 12, 4, 0,
    'Flag a post that breaks the rules - stolen credit, explicit content, spam - and add a short note for context. It keeps the catalogue clean without a comment section.'],
  ['v0.6.1', 'Sharper previews, endless scroll', 'Aug 11, 2026', 11, 6, 0,
    'Distant blocks in the 3D preview no longer break up into grain; textures are sampled at the right level of detail as you zoom out.\n\nThe browse grid now streams in more posts as you scroll, and Settings gained grid density and a cache control.'],
  ['v0.6', 'Your library sticks around', 'Aug 11, 2026', 11, 2, 0,
    'Likes and saved posts now persist between sessions, so your Saved tab is there when you come back.'],
  ['v0.5.1', 'Feel and sound', 'Aug 10, 2026', 10, 6, 0,
    'Tabs and category chips animate on hover and selection, and navigating, following and liking each have their own sound.'],
  ['v0.5', 'Block entities render', 'Aug 10, 2026', 10, 2, 0,
    'Chests, shulker boxes, beds, signs, banners and decorated pots now show their real textures in the preview instead of falling back to plain blocks.'],
  ['v0.4.1', 'Water you can see through', 'Aug 9, 2026', 9, 6, 0,
    'Water renders translucent in the preview, so submerged and waterlogged builds read correctly instead of hiding behind a solid blue block.'],
  ['v0.4', 'Save, follow, download', 'Aug 9, 2026', 9, 2, 0,
    'Save a post for later, follow a creator to get a toast when they publish, and download straight into your schematics folder with real transfer progress.'],
  ['v0.3.1', 'Layer by layer', 'Aug 8, 2026', 8, 6, 0,
    'A slider peels the build down one block layer at a time, so you can read the inside of a farm or base without moving the camera.'],
  ['v0.3', 'Look inside', 'Aug 8, 2026', 8, 2, 0,
    'The 3D preview gained orbit and zoom, and zooming past the walls drops you inside an enclosed build to inspect it.'],
  ['v0.2', 'Textured previews', 'Aug 7, 2026', 7, 2, 0,
    'Posts render in real block models and textures, with proper tinting for leaves, grass and water, instead of flat placeholder colours.'],
  ['v0.1.1', 'Post pages', 'Aug 6, 2026', 6, 6, 0,
    'Every schematic opens a detail page with its images, dimensions, block count and a credit to the designer.'],
  ['v0.1', 'The Schematic Index opens', 'Aug 6, 2026', 6, 2, 0,
    'Browse a community catalogue of DonutSMP schematics - farms, contraptions, regears, stashes and mega builds - in game, straight from Litematica\'s menu.\n\nFilter by category and search by name. Uploads are invite only for now; ask an existing uploader for an access code.'],
];

const SAMPLE_POSTS = [
  ['sample-iron-farm', 'Iron Farm Mk3 - 300/h, no spawn-proofing', 'Iron Farm Mk3', 'Fudgedy', 'ilmango',
    'FARMS', 34, 22, 41, 18452, 1203, 210],
  ['sample-vault-door', 'Hidden 3x3 Vault Door', 'Vault Door', 'SableCo', 'Chapman',
    'CONTRAPTIONS', 12, 9, 5, 640, 512, 88],
];

const insertNews = db.prepare(
  'INSERT INTO news (badge, title, date_text, body, highlight, posted_at) VALUES (?, ?, ?, ?, ?, ?)',
);
const insertPost = db.prepare(`
  INSERT OR REPLACE INTO posts
    (id, title, thumbnail_name, poster, designer, category, size_x, size_y, size_z, block_count,
     downloads, likes, posted_at, description, visibility)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'visible')
`);

db.exec('DELETE FROM news');
for (const [badge, title, dateText, day, hour, highlight, body] of NEWS) {
  insertNews.run(badge, title, dateText, body, highlight, Date.UTC(2026, AUG, day, hour));
}

for (const [id, title, thumb, poster, designer, cat, x, y, z, blocks, dl, likes] of SAMPLE_POSTS) {
  insertPost.run(id, title, thumb, poster, designer, cat, x, y, z, blocks, dl, likes,
    Date.UTC(2026, AUG, 12, 10), 'A sample post used while building the server. No files attached yet.');
}

console.log(`[seed] ${NEWS.length} news entries, ${SAMPLE_POSTS.length} sample posts`);
