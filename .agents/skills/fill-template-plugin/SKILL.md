---
name: fill-template-plugin
description: Customize this PaperMC/Spigot plugin template for a real plugin. Use when asked to fill in, adapt, rename, fork, or customize the template project.
---

Read `docs/customization.md` before editing. This project is now EpicSmpPlugin for Paper 26.2,
implementing Strength SMP Season Four features. Preserve its identity and gameplay unless a rename or redesign
is requested. Resolve routine choices from task context; clarify only material missing details.

# Checklist

1. Set `rootProject.name`; keep the entry point class name aligned with project identity.
2. Set the Gradle `group`; rename main and test package paths, declarations, and imports.
3. Adapt the existing gameplay and update its behavioral tests.
4. Update `plugin.yml` metadata and permissions. Do not declare commands there.
5. Align the Paper API, `api-version`, Java toolchain, CI Java versions, dependencies, shading,
   and supported-version docs.
6. Update README content and links, project docs, `AGENTS.md`, and `.agents/skills/`.
7. Update GitHub ownership, funding, conduct contact, templates, policies, workflows, variables,
   and secrets as applicable.
8. Search for template leftovers, then run `./gradlew clean build`.
