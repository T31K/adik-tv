# ARVIO v1.9.995

This update improves tracking libraries, anime episode identity, playback finalization, Discord integration, IPTV settings, AI subtitles, profile selection, and the ARVIO web experience.

## Compatibility
- Added 16 KB memory-page support to the Play release native libraries.

## Tracking and Library
- Fixed SIMKL libraries appearing empty by accepting the live list response used for Movies, Shows, and Anime while retaining compatibility with wrapped responses.
- Restored Trakt personal and custom list contents in Library and kept list loading isolated to the active profile.
- Improved SIMKL Library and Continue Watching synchronization, including partial-failure recovery and stricter title matching for entries without TMDB IDs.

## Anime and playback
- Added official anime season structures backed by ARM and Kitsu while preserving canonical TMDB and Trakt episode identities for tracking, progress, and source lookup. Contributor: @Leyto59 via #568.
- Finalized playback progress before Next Episode navigation so watched state and scrobbles are not cancelled during destination changes. Contributor: @Aerya via #583.
- Fixed missing subtitle add-on results and upgraded Groq subtitle translation to GPT-OSS 120B. Contributor: @silentbil via #578.
- Fixed mobile AI subtitle Save and Cancel buttons not responding. Contributor: @silentbil via #587.

## Discord, profiles, and settings
- Added native Discord Rich Presence with poster artwork, episode details, elapsed playback time, secure TV authorization, and account controls. Contributor: @Himanth-reddy via #517.
- Fixed selecting the already-active profile sometimes requiring repeated clicks. Contributor: @silentbil via #579.
- Added IPTV sort-order controls and synchronized the selected order across profiles and devices. Contributor: @Himanth-reddy via #581.
- Added monochrome branding for beta builds so beta and production installs are visually distinct. Contributor: @Himanth-reddy via #585.

## Web and membership
- Improved premium trial conversion, entitlement handling, and direct source download visibility.
- Fixed responsive site navigation overlap and renamed the web membership tab for clearer navigation.

## Release contributors
- @Aerya
- @Himanth-reddy
- @Leyto59
- @silentbil
- @ProdigyV21

## Project contributors
Thank you to everyone who has helped build and improve ARVIO:

- @Aaronnn17
- @Aerya
- @Bivek01
- @chillpill244
- @EierKopZA
- @eierkop80-stack
- @foXaCe
- @Himanth-reddy
- @jonahmichael
- @Leyto59
- @mvanhorn
- @NightCorpse
- @nubblyn
- @pika1998
- @pjetrazz
- @ProdigyV21
- @sagedavids-ZA
- @saidai-bhuvanesh
- @silentbil
- @test01203
- @willhowlett

**Full commit history:** [v1.9.994...v1.9.995](https://github.com/ProdigyV21/ARVIO/compare/v1.9.994...v1.9.995)
