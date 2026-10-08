# Mi-FreeForm (Flyme Style Edition)

![GitHub release (latest by date)](https://img.shields.io/github/v/release/DtHnAme/Mi-FreeForm)

<img src="https://github.com/Agus-style/Mi-FreeFormn/actions/runs/22878710863/artifacts/5839899973" width="100"/>

Mi-FreeForm is an APP that is activated through Shizuku/Sui and can display most apps in the form of freeform.

This build keeps the Flyme-style base and supports three capability levels: Shizuku/Sui for the main freeform implementation, optional LSPosed/Xposed hooks for the system-process freeform bridge, and a standalone accessibility/overlay taskbar when neither is available. See [FEATURE_AUDIT.md](FEATURE_AUDIT.md) and [MERGE_NOTES.md](MERGE_NOTES.md) for the exact scope and compatibility notes.

Current support:
- Open the favorites app in small window mode through the global sidebar
- Open the favorites app with resident notifications
- Open the favorites app with a tile
- Make the APP that sends notifications open in freeform mode
- Reset all active freeform windows through a Settings action or an optional Quick Settings tile
- Start a validated package/activity through the explicit launch API; without Shizuku it falls back to normal fullscreen launching

## Download
[Release](https://github.com/Agus-style/Mi-FreeFormn/releases/tag/freefrom)

## Library
[AppIconLoader](https://github.com/zhanghai/AppIconLoader)

[Glide](https://github.com/bumptech/glide)

[RikkaX](https://github.com/RikkaApps/RikkaX)

[Shizuku](https://github.com/RikkaApps/Shizuku)

[TinyPinyin](https://github.com/promeG/TinyPinyin)

## License
```
Copyright (C) 2021-2022  sunshine0523
Copyright (C) 2023  DtHnAme

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
```
