"use client";

import { ArrowDown, ArrowUp, Eye, EyeOff, GripVertical, RotateCcw } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "@/lib/i18n";
import { movePlaylistGroup, playlistGroups, setPlaylistGroupsVisible } from "@/lib/iptvGroups";
import type { AppSettings, IptvChannel } from "@/lib/types";

export function IptvGroupSettings({ channels, settings, updateSettings }: {
  channels: IptvChannel[];
  settings: AppSettings;
  updateSettings: (patch: Partial<AppSettings>) => void;
}) {
  const t = useTranslation();
  const [selected, setSelected] = useState("");
  const [held, setHeld] = useState<string | null>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const playlists = settings.iptvPlaylists;
  const playlistId = playlists.some(p => p.id === selected) ? selected : playlists[0]?.id ?? "";
  const groups = useMemo(() => playlistGroups(channels, playlistId, settings.groupOrder), [channels, playlistId, settings.groupOrder]);
  useEffect(() => {
    if (!held) return;
    const list = listRef.current;
    const row = list?.children[groups.findIndex(group => group.key === held)] as HTMLElement | undefined;
    if (!list || !row) { setHeld(null); return; }
    list.scrollTop += row.getBoundingClientRect().top - list.getBoundingClientRect().top - (list.clientHeight - row.clientHeight) / 2;
  }, [groups, held]);
  const move = (key: string, direction: -1 | 1) => updateSettings({ groupOrder: movePlaylistGroup(settings.groupOrder, groups, key, direction) });
  if (!playlists.length) return null;
  return <section className="iptv-group-settings" aria-label={t("Categories")}
    onKeyDown={event => {
      if (!held) return;
      if (event.key === "ArrowUp" || event.key === "ArrowDown") {
        event.preventDefault(); event.stopPropagation();
        move(held, event.key === "ArrowUp" ? -1 : 1);
      } else if (["Escape", "ArrowLeft", "ArrowRight", "Tab"].includes(event.key)) {
        setHeld(null);
        if (event.key !== "Tab") { event.preventDefault(); event.stopPropagation(); }
      }
    }}>
    <h3>{t("Categories")}</h3>
    <select aria-label={t("Playlist")} value={playlistId} onChange={event => { setSelected(event.target.value); setHeld(null); }}>
      {playlists.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
    </select>
    <div className="iptv-group-actions">
      <button type="button" className="secondary" disabled={!groups.length} onClick={() => updateSettings({ hiddenGroupIds: setPlaylistGroupsVisible(settings.hiddenGroupIds, groups, true) })}><Eye size={18} />{t("Show all")}</button>
      <button type="button" className="secondary" disabled={!groups.length} onClick={() => updateSettings({ hiddenGroupIds: setPlaylistGroupsVisible(settings.hiddenGroupIds, groups, false) })}><EyeOff size={18} />{t("Hide all")}</button>
      <button type="button" className="secondary" title={t("Reset order")} aria-label={t("Reset order")} onClick={() => { setHeld(null); updateSettings({ groupOrder: settings.groupOrder.filter(key => !key.startsWith(`${playlistId}|`)) }); }}><RotateCcw size={18} /></button>
    </div>
    <div className="iptv-group-list" ref={listRef}>
      {groups.map((group, index) => {
        const hidden = settings.hiddenGroupIds.includes(group.key);
        return <div key={group.key} className={`iptv-group-row${held === group.key ? " is-held" : ""}`}>
          <span className="iptv-group-name">{group.name}<small>{group.count}</small></span>
          <button type="button" className="secondary" aria-label={`${t("Move up")}: ${group.name}`} title={t("Move up")} disabled={index === 0} onClick={() => move(group.key, -1)}><ArrowUp size={18} /></button>
          <button type="button" className="secondary" aria-label={`${t("Move down")}: ${group.name}`} title={t("Move down")} disabled={index === groups.length - 1} onClick={() => move(group.key, 1)}><ArrowDown size={18} /></button>
          <button type="button" className="secondary" aria-label={`${t("Hold to move")}: ${group.name}`} title={t("Hold to move")} aria-pressed={held === group.key}
            onClick={() => setHeld(held === group.key ? null : group.key)} onBlur={() => setHeld(null)}><GripVertical size={18} /></button>
          <button type="button" className="secondary" aria-label={`${t(hidden ? "Show" : "Hide")}: ${group.name}`} title={t(hidden ? "Show" : "Hide")}
            onClick={() => updateSettings({ hiddenGroupIds: setPlaylistGroupsVisible(settings.hiddenGroupIds, [group], hidden) })}>{hidden ? <EyeOff size={18} /> : <Eye size={18} />}</button>
        </div>;
      })}
    </div>
  </section>;
}
