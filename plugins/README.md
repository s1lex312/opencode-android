# Plugins

Declarative plugins for opencode-android (Settings, Plugins). A plugin is one JSON file:

```json
{
  "format": "opencode-android-plugin",
  "version": 1,
  "id": "my-plugin",
  "name": "My plugin",
  "description": "What it adds",
  "pluginVersion": "1.0.0",
  "author": "you",
  "requires": ["node"],
  "mcp": { "name": { "type": "local", "command": ["npx", "-y", "package"] } },
  "agent": { "name": { "description": "...", "mode": "subagent", "prompt": "..." } },
  "command": { "name": { "description": "...", "template": "... $ARGUMENTS" } },
  "systemPrompts": [ { "name": "...", "text": "..." } ],
  "snippets": [ { "name": "short", "body": "... $ARGUMENTS", "description": "...", "send": true } ],
  "hooks": [ { "name": "...", "event": "file_edited", "command": "...", "matcher": "*.kt", "timeoutSeconds": 60 } ]
}
```

`mcp`, `agent` and `command` use opencode's own `opencode.json` format. Hook events: `prompt_submit`,
`before_tool`, `file_edited`, `turn_end`. Hooks are installed switched off unless the user enables
them in the install dialog. Nothing runs at install time.

Install from a link (`https://.../plugin.json`, optionally `#sha256=<hex>` to pin the content) or
from a file. To list a plugin in the catalogue, add it to `index.json` with its SHA-256.
