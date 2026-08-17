import zlib from 'node:zlib';
import nbt from 'prismarine-nbt';

const AUTHOR = 'The Schematic Index';

// Stamp a .litematic buffer so every download carries our provenance without editing the
// file on each download: sets the schematic Author to "The Schematic Index" and its Name
// to the post title. Only the Metadata compound is touched; block data is preserved. If
// the file can't be parsed it is returned unchanged so an upload never breaks.
export async function stampLitematic(buffer, name) {
  try {
    const { parsed } = await nbt.parse(buffer);

    if (!parsed?.value?.Metadata?.value) {
      return buffer;
    }

    const meta = parsed.value.Metadata.value;
    meta.Author = { type: 'string', value: AUTHOR };

    if (name) {
      meta.Name = { type: 'string', value: String(name) };
    }

    return zlib.gzipSync(nbt.writeUncompressed(parsed, 'big'));
  } catch (e) {
    console.error('[stamp] leaving file unstamped:', e.message);
    return buffer;
  }
}
