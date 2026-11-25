# Brownfield adoption — preliminary report

Generated from filenames and local PATH only. No application code was modified.

## Inventory

```json
{
  "target": "/Users/e.klimov/.t3/projects/offline-voice-input",
  "exists": true,
  "git": true,
  "existing_instructions": [
    "AGENTS.md"
  ],
  "existing_spec": false,
  "existing_ci": true,
  "test_locations": [
    "tests"
  ],
  "detected_stacks": {
    "rust": [
      "Cargo.toml"
    ],
    "android": [
      "settings.gradle.kts"
    ]
  },
  "cli_on_path": {
    "git": true,
    "python3": true,
    "codex": true,
    "claude": false,
    "gemini": false,
    "opencode": true,
    "openspec": true,
    "dsh": true,
    "qwen": true,
    "pi": true
  },
  "harness_cli_candidates": {
    "codex": [
      "codex"
    ],
    "opencode": [
      "opencode"
    ],
    "dsh": [
      "dsh"
    ],
    "qwen-code": [
      "qwen"
    ],
    "pi": [
      "pi"
    ],
    "claude-code": [],
    "gemini-cli": []
  },
  "model_availability": "UNVERIFIED: CLI presence does not establish available models, effort, authorization or permission to send code",
  "warning": "Filenames and PATH only; not a complete code, dependency or security audit."
}
```

## Required manual review before first change

- [ ] Map modules, public API, data formats and migrations.
- [ ] Establish existing test and CI baseline; record pre-existing failures.
- [ ] Identify current UX components, design constraints and user journeys.
- [ ] Review data classification, trust boundaries, secrets and dependency risks.
- [ ] Inspect affected code for implicit state machines and duplicate implementations.
- [ ] Check existing agent instructions, skills, MCP and genuine model access.
- [ ] Agree scope, adoption plan and regression constraints with the user.
- [ ] After review, manually reference .agentic-kit/AGENTS.md in existing AGENTS.md or equivalent.

Do not automatically refactor or replace existing configurations.
