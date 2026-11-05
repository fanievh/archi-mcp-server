# Pre-built Plugin Artifacts

This directory contains pre-built `.archiplugin` files for the ArchiMate MCP Server.

## Installation

1. Download the `.archiplugin` file from this directory
2. In Archi: **Help > Manage Plug-ins > Install New...**
3. Select the downloaded `.archiplugin` file
4. Restart Archi

Alternatively, copy the `.archiplugin` file directly into Archi's `dropins/` folder and restart.

## Compatibility

- **Archi:** 5.8+ for v1.9.0 and later (enforced from v1.10.0: an older Archi refuses to load the plugin; v1.9.0 loads on 5.7 but fails on some note updates); v1.8.0 and earlier run on 5.7+
- **Java:** 21+
- **MCP SDK:** 0.17.2
