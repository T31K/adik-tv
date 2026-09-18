"use client";

import { useTranslation } from "@/lib/i18n";
import { defaultCatalogs } from "@/lib/catalogs";
import type { Category, MediaItem } from "@/lib/types";
import { useApp } from "@/lib/store";
import { MediaCard } from "./MediaCard";
import { RailScroller } from "./RailScroller";

export function MediaRail({ category, onOpen, onFocus, posterMode }: {
  category: Category;
  onOpen: (item: MediaItem) => void;
  onFocus?: (item: MediaItem) => void;
  posterMode?: boolean;
}) {
  const { settings } = useApp();
  const translateUi = useTranslation();
  const title = category.id === "continue_watching" || defaultCatalogs.some(catalog => catalog.id === category.id && catalog.name === category.title) ? translateUi(category.title) : category.title;
  const effectivePosterMode = posterMode ?? (category.layout ? category.layout === "poster" : settings.cardLayoutMode === "poster");
  if (!category.items.length) return null;
  return (
    <section className={`rail ${effectivePosterMode ? "is-poster" : ""}`}>
      <div className="rail-head">
        <h3>{title}</h3>
      </div>
      <RailScroller className="rail-strip" ariaLabel={title}>
        {category.items.map((item) => (
          <MediaCard
            key={`${category.id}-${item.mediaType}-${item.id}-${item.title}`}
            item={item}
            onOpen={onOpen}
            onFocus={onFocus}
            posterMode={effectivePosterMode}
          />
        ))}
      </RailScroller>
    </section>
  );
}
