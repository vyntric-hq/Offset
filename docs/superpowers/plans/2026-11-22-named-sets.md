# Named Sets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Name the staged set and save it to Images without a write. Writes save under that name with Finder-style numbering. Add MURS and Marine 16 presets, and multi-select delete in Images.

**Architecture:** A domain use case, `SaveNamedImage`, owns the naming rule over `ImageLibrary`. `WriteRadio` saves its result through it. The staging name lives in a new `staging.name` column (Room 3 to 4).

**Tech Stack:** Kotlin, Compose, Room, JUnit.

**Spec:** docs/superpowers/specs/2026-11-22-named-sets-design.md

## Global Constraints
- No em dashes in UI text, docs or comments. UI copy stays minimal.
- Rule 3 is unchanged: saving a set never touches the radio.
- Run the codex gate before commit.

## Review Focus
- **Two saves at once** (Save tapped twice, or Save during a write's save): no duplicate "(2)". `SaveNamedImage` holds a mutex. Test: two concurrent saves of the same image give one item.
- **Repeated writes of the same set:** identical to "Name (2)" means no "(3)". The match covers the whole numbered family. Test included.
- **Blank name:** saved as "Untitled". Test included.
- **A name that already ends in " (2)":** numbering continues from that base and does not nest ("(2) (2)"). Test included.
- **Factory or backup images with the same name:** ignored, since only USER images count. Test included.

---

### Task 1: SaveNamedImage
**Files:** create `domain/SaveNamedImage.kt`; modify `domain/ImageLibrary.kt`, `data/ImageRepository.kt`, `data/db/OffsetDatabase.kt` (DAO query), `TestDoubles.kt`; test `domain/SaveNamedImageTest.kt`.

**Interfaces:**
- Produces: `ImageLibrary.userImagesNamed(base: String): List<StoredImage>`, returning the USER images named `base` or `base (n)`.
- Produces: `class SaveNamedImage(library: ImageLibrary)`, `suspend operator fun invoke(image: RadioImage, name: String, notes: String = ""): Saved`, with `data class Saved(val id: Long, val name: String, val existing: Boolean)`.

Steps: write tests for new name, identical, different, numbering, blank, suffix-in-name, concurrency, and non-USER ignored. Implement. Run `./gradlew :app:testDebugUnitTest --tests '*SaveNamedImage*'`.

### Task 2: WriteRadio saves through SaveNamedImage
Make `WriteRadio` take `naming: SaveNamedImage`. The result save calls it. The existing WriteRadioTest keeps passing. Add a test: writing twice with the same name stores one result image.

### Task 3: Staging name
- Add `StagingEntity.name` with `@ColumnInfo(defaultValue = "")`, plus MIGRATION_3_4 (ADD COLUMN), version 4, and the 4.json schema.
- `StagingRepository.observeName(): Flow<String>` and `setName(name)`. `put` and `update` keep the name.
- Test in StagingRepositoryTest: the name survives a put, and `setName` works on an empty table.
- Add the 3 to 4 case in DatabaseMigrationTest.

### Task 4: Radio screen
- A "Set" section above staging: a Name row that opens a sheet, and "Save to Images", disabled without a read. Footer: "Read the radio first."
- The VM's `saveSet()` builds the image with `withChannels` and passes it to SaveNamedImage.
- `clearStaging` also clears the name. `write()` uses the set name.
- The confirm sheet shows the name instead of asking for one.
- `ImageDetail.loadIntoStaging` sets the name to the image name.

### Task 5: Presets
- `MURS`: MURS1 to MURS5, LOW power, narrow on 1 to 3 and wide on 4 and 5.
- `MARINE16`: 156.800 MAR16, receive only.
- Add both to ALL, plus tests in PresetsAndFiltersTest.

### Task 6: Images multi-select
- A Select/Done bar action, check marks on rows, and a "Delete N" bottom bar that opens a confirmation sheet.
- `ImageRepository.deleteAll(ids)` uses a DAO `IN` delete, chunked.

### Task 7: Docs, version, verify, gate, commit, release 0.7.5
