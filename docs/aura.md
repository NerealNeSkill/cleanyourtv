# AURA home screen

An alternative Home with a dark side rail, a search bar, and three pages: Home, Apps and Settings. It is plain
views (no blur, video, service or network) and adds one small activity to the launcher.

## What it has

- **Home**: clock, greeting, your shortcut apps, and quick actions (launcher settings, Android settings, Inputs).
- **Apps**: a grid with 4 to 7 columns, sorted by use, by name, or by last opened; typing in the search bar filters it.
  Long-press OK on a tile for Open, Add to Home, Hide and App info.
- **Settings**: two cards ("Launcher settings", "Android settings") and a Personalisation page with six sections:
  Appearance (Orbit, Graphite or Light theme, five focus colours, control hints, corner roundness, wallpaper,
  animations), Home screen (clock format, greeting, quick actions, shortcut apps), Apps (grid density, order,
  hidden apps, non-TV apps, long-press menu), Voice and search (the system speech recogniser, if the TV has one),
  Remote and buttons (Inputs, and the classic launcher settings for the remote's keys) and About (back to the
  classic look, reset).
- The text is in Russian when the TV's language is Russian, English otherwise.

## Switching it on and off

`aura_ui` in `res/values/bools.xml` (`true` in this fork) or in a pushed `config.json`. "Classic look" in AURA's
About section turns it off on the TV; `adb shell am start -n com.gathin.tvhome/.AuraActivity` opens it again.
If AURA crashes before its first frame, Home opens the classic screen until AURA is started by hand, so a fault
here cannot leave the TV without a Home.

## Limits

- The Inputs menu is the classic Home's: AURA's "Inputs" cards open it, and Home brings AURA back.
- Choices are stored in the launcher's `launcher_settings` preferences under `aura_*` keys. The classic
  "Follow theme" reset does not touch them; "Reset AURA settings" does.
- Not built or tried on a TV yet: see the pull request.
