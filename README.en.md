# AE2 Upload Sidebar

Upload encoded patterns to a chosen pattern provider directly from an AE2 Pattern Encoding Terminal. Organize providers with private labels, item icons, favorites and Chinese pinyin search.

## Features

- Upload one encoded pattern to a selected provider and see its available pattern slots.
- Search original names and private labels, including full Chinese pinyin and initials.
- Pin favorite providers with the star button. Favorites still respect the search filter.
- Right-click a provider row to edit its private label. Use a held item or drag an item from JEI to change its display icon without consuming items.
- Middle-click a row to highlight the actual provider in the world for 60 seconds.
- Open a provider directly to manage its label, icon, favorite state and mappings.
- Recommend targets using recipe-category mappings, falling back to the category title when no mapping exists.
- Enable automatic upload per mapping. After a new encoding operation, exactly one available matching target receives one pattern; multiple targets require manual selection.
- Optional ExtendedAE Plus integration shares its mappings and management screen. Standalone mapping management works without it.
- Move, collapse and resize the panel. Choose an AE2 theme or a vanilla theme that follows resource packs.

## Requirements

Minecraft Java 1.21.1, NeoForge 21.1.241 or newer, and AE2 >=19.2.17 and <19.3. This build does not support Forge, Fabric, Minecraft 1.20.1 or 1.12.2.

Install the JAR in your instance's mods folder with the game closed. Remove the previous sidebar JAR when upgrading; keep only one version. Keep AE2's own dependencies installed.

This is not a client-only mod: full multiplayer functionality requires it on both client and server. Testing covers integrated singleplayer servers; dedicated multiplayer servers have not received dedicated validation yet.

Optional integrations: JEI for icon dragging; JEI and AE2 JEI Integration for capturing processing recipe categories; AE2WTLib WET for the tested wireless pattern encoding terminal. ExtendedAE Plus is optional.

## Notes

Labels, icons and favorites are private to your client and scoped by world/server and physical provider location. Rebuilding a provider at the same location can inherit previous annotations. Icons never change the upload destination.

Transfer processing recipes through JEI before encoding to capture their category. Reopened or manually entered processing patterns may require another transfer or manual search.

Client commands: /ae2sidebar off, /ae2sidebar on and /ae2sidebar unhighlight. No operator permissions required.

Version 0.12.1-beta is a Beta. Its predecessor 0.12.0-test passed 107 unit tests and in-game tests with AE2 19.2.18, both without ExtendedAE Plus and with ExtendedAE Plus 1.6.2 plus CozyUI+ v1.10. Other modpack combinations are not guaranteed. The current UI is primarily in Chinese.

Pinyin data is bundled for offline use. Polyphonic characters may match additional readings. Data: mozillazg/pinyin-data, with its MIT license included in the JAR.
## Development and license

[中文](README.md) · [Build and test guide (Chinese)](docs/BUILDING.md) · [Changelog](CHANGELOG.md)

Project code is licensed under [MIT](LICENSE), copyright Echo. Bundled pinyin data retains its original author's license; see [third-party notices](THIRD-PARTY-NOTICES.md).

Version 0.12.1-beta prepares licensing and portable build configuration; gameplay features are unchanged from 0.12.0-test.

See [validation records](docs/VALIDATION.md) for the tested versions and remaining limits.
