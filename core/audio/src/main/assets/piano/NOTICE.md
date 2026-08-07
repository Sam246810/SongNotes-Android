# Piano sample attribution

The 29 `*.mp3` files in this directory are from the **Salamander Grand
Piano V3** sample library, recorded and released by **Alexander Holm**:
<https://archive.org/details/SalamanderGrandPianoV3>

Licensed under **CC BY 3.0**: <http://creativecommons.org/licenses/by/3.0/>

Files are named by MIDI note number (`{midi}.mp3`, e.g. `60.mp3` = C4),
re-packaged for offline bundling from the same source the web app streams
at runtime (`@audio-samples/piano-mp3-velocity13`, MIT-licensed packaging —
see <https://github.com/darosh/samples-piano-mp3> — around the CC-BY-licensed
audio itself; the MIT license covers only that packaging, not the samples).
No changes have been made to the audio content beyond the filename.

See `app/src/main/java/com/songnotes/android/PianoScreen.kt`'s (or wherever
the in-app credit ends up landing) attribution string for the user-facing
copy of this notice.
