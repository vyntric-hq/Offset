# Named staged sets, more presets, multi-delete in Images

Approved 2026-11-22.

## Intent

The user builds a channel set in staging and wants to keep it by name, with or without writing it to a radio. They also want MURS and marine channel 16 as presets, and a faster way to clear out the Images list.

## Approaches

- **A, chosen: an image built from the last read.** The staged channels are laid over the image of the radio last read, and the result is saved as an ordinary user image. It can be exported, restored and loaded back like any other image. Saving needs a read in this session.
- **B: a new "channel set" item.** It would work without a read, but it could not be exported as .img or restored, and the library would hold two kinds of item. Rejected.

## Design

- **Staging name.**
  - Editable in the staging header and stored with staging, in a new `name` column (migration 3 to 4, ADD COLUMN with default '').
  - Clear resets it. "Load into staging" from an image takes the image's name.
- **Save to Images.** Builds `snapshot.image.withChannels(staged slots)` and saves it under the set name, using the naming rule below. If there is no read yet, the action is disabled and says to read the radio first.
- **Write.** The write saves its verified read-back under the set name, using the same rule. The confirmation sheet no longer asks for a name.
- **Naming rule.** Applies to user images only.
  - If no user image has the name, save it under that name.
  - If one with the name is identical, meaning the same radio type and the same channels in the same slots, save nothing and report the existing one.
  - Otherwise, save it under the first free "Name (n)", with n starting at 2.
  - Settings bytes are not compared, because the radio changes some of them on its own.
  - A blank name is saved as "Untitled".
- **Presets.**
  - MURS 1 to 5: low power, 1 to 3 narrow, 4 and 5 wide.
  - Marine 16 (156.800): receive only, like NOAA.
  - Calling and GMRS/FRS already exist.
- **Images.** A Select mode with check marks, then "Delete N" with a confirmation sheet. Swipe to delete stays.

## Left to the builder

- Where the naming rule lives: a use case over `ImageLibrary`, so the domain stays free of Room.
- Message wording, kept to one short line.
