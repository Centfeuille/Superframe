Superframe / Inbetween — AI Working Agreement

Applies to every AI assistant, coding agent and human contributor using AI assistance.

This is the canonical development rulebook. "PROJECT_MEMORY.md" and "CHANGELOG.md" are mandatory companion documents.

1. Mandatory reading

Before modifying anything:

1. Read this entire document.
2. Read "PROJECT_MEMORY.md" completely.
3. Read the latest relevant entries in "CHANGELOG.md".
4. Inspect the relevant source files and their callers.
5. Inspect existing configuration before making build-related changes.

Do not rely solely on conversation summaries when repository files are available.

If documentation and code disagree, inspect the code, identify the discrepancy and update the documentation appropriately. Do not silently assume either source is correct.

2. Protected infrastructure

The following are protected:

- ".github/workflows/**"
- Gradle wrapper and Gradle build scripts.
- "gradle.properties" and dependency version catalogs.
- Android SDK and Java configuration.
- Signing and release configuration.
- GitHub Actions secrets and repository variables.

Never rewrite, replace, simplify or modify protected infrastructure during an ordinary application feature or bug fix.

Only change protected infrastructure when:

- The user explicitly requests it; or
- An actual build log demonstrates that the infrastructure itself is responsible.

Before making such a change:

1. Identify the exact failing step.
2. Explain why the existing configuration caused the failure.
3. Describe the smallest proposed correction.
4. Obtain user approval unless already explicitly authorized.

Never replace a working workflow with a generic template.

3. Minimal-change policy

- Modify only files required by the requested task.
- Do not perform unrelated refactoring, renaming, cleanup or dependency upgrades.
- Do not change package names, namespaces, application IDs or folder layouts without a clear reason and migration plan.
- Preserve existing architecture and functionality.
- Preserve document compatibility and user data.
- Do not remove code just because it appears unused without checking references and behavior.
- Never silently change established project decisions.

4. Inbetween-specific constraints

When working on the animation editor:

- Preserve per-layer cel timing.
- Consider conflicts between timeline dragging, stretching, scrubbing, panning and zooming.
- Preserve S Pen and finger input.
- Preserve undo/redo behavior.
- Keep canvas rendering, playback, thumbnails and exports consistent.
- Maintain correct cache invalidation.
- Preserve existing ".inb" document compatibility.
- Consider Android tablet performance and memory usage.

Changes to one interaction must not unintentionally break another.

5. Build failure procedure

When a build fails:

1. Read the actual log.
2. Identify the first meaningful error and failing task.
3. Identify the responsible file or configuration.
4. Classify the problem: source code, dependency, Gradle, SDK, workflow, signing or environment.
5. Fix only the demonstrated cause.
6. Inspect the next build log independently.

Do not modify unrelated source code to fix a workflow error, or modify the workflow to fix a Kotlin error.

A valid ZIP is not proof of compilation. Compilation is not proof of runtime correctness.

6. Mandatory diff review

Before delivering any modification:

1. Inspect the complete Git diff against the original project.
2. List every added, changed, renamed and deleted file.
3. Verify that each modification is necessary.
4. Specifically check protected infrastructure.
5. Check for accidental deletion, unrelated changes, secrets and build artifacts.
6. Verify the ZIP's root structure if delivering an archive.

If an unrelated change appears, revert it or obtain approval before delivering.

7. Validation and reporting

Every delivery must state:

- What changed.
- Exact files modified.
- Why the changes were necessary.
- Which protected files were left untouched.
- What validation was actually performed.
- What remains untested.

Never claim a successful build unless the build actually completed. Never claim an interaction works unless it was tested.

8. Documentation requirements

After every meaningful accepted project change:

- Update "PROJECT_MEMORY.md" if current architecture, behavior, features or technical constraints changed.
- Add a dated entry to "CHANGELOG.md".
- Add a plain-language `ReleaseNote` (next id, at the top of the list) to `WhatsNew.kt` for every user-visible change, so the in-app "What's new" window shows it after the update. Do not claim fixes that were not verified.
- Preserve all existing historical entries and attributions.
- Record the actual AI model responsible, if known.
- Record the exact files changed.
- Record actual validation, not intended validation.
- Record known issues and follow-up work.

Do not duplicate the complete historical changelog inside project memory.

9. User authority

The user owns the project and makes architectural decisions.

Ask before:

- Destructive changes.
- Broad refactoring.
- Data migrations.
- Changes to protected infrastructure.
- Changes that could break compatibility.

When an instruction conflicts with established project documentation, explain the conflict rather than silently choosing.

10. Default principle

Preserve working code and infrastructure. Make the smallest justified change. Inspect the complete diff. Update documentation. Report exactly what was and was not verified. Never surprise the user with unrelated modifications.