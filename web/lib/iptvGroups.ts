import type { IptvChannel } from "./types";

export interface PlaylistGroup { key: string; name: string; count: number }

export function playlistGroups(channels: IptvChannel[], playlistId: string, order: string[]): PlaylistGroup[] {
  const groups = new Map<string, PlaylistGroup>();
  for (const channel of channels) {
    if (channel.id.split(":")[0] !== playlistId) continue;
    const name = channel.group?.trim() || "Uncategorized";
    const key = `${playlistId}|${name}`;
    const group = groups.get(key);
    if (group) group.count++;
    else groups.set(key, { key, name, count: 1 });
  }
  const ranks = new Map(order.map((key, index) => [key, index]));
  return [...groups.values()].sort((a, b) =>
    (ranks.get(a.key) ?? Number.MAX_SAFE_INTEGER) - (ranks.get(b.key) ?? Number.MAX_SAFE_INTEGER));
}

export function movePlaylistGroup(order: string[], groups: PlaylistGroup[], key: string, direction: -1 | 1): string[] {
  const keys = groups.map(group => group.key);
  const from = keys.indexOf(key);
  const to = from + direction;
  if (from < 0 || to < 0 || to >= keys.length) return order;
  [keys[from], keys[to]] = [keys[to], keys[from]];
  // Replace only this playlist's known slots; retain unloaded groups and other playlists.
  const known = new Set(keys);
  let index = 0;
  const next = [...new Set(order)].map(item => known.has(item) ? keys[index++] : item);
  return [...next, ...keys.slice(index)];
}

export function setPlaylistGroupsVisible(hidden: string[], groups: PlaylistGroup[], visible: boolean): string[] {
  const keys = new Set(groups.map(group => group.key));
  return visible ? hidden.filter(key => !keys.has(key)) : [...new Set([...hidden, ...keys])];
}
