# Farlands (Fabric 1.21.11)

Vanilla world up to 12,550,820 blocks (x or z). Beyond that, on all four sides:

| Distance past the line | What is there |
|---|---|
| 0 - 12,400 | plain Farlands: solid stone, y -64 to 256 |
| 12,400 - 12,600 | void gap |
| 12,600 - 17,600 | Farlands with vanilla structures embedded in the stone |
| 17,600 - 20,100 | chessboard: alternating stone chunks and void chunks |
| 20,100 - 20,600 | corruption: more and more blocks missing |
| beyond | void |

Stone includes granite, andesite, diorite, gravel and tuff. No deepslate, obsidian, caves, ores,
grass, trees or water. Only chunks generated after installing the mod are changed.
All numbers are constants at the top of `FarlandsMod.java`.

Build: JDK 21, `gradle build`, jar in `build/libs/farlands-1.0.0.jar`, or push to GitHub and use the
included workflow. Test with `/tp @s 12551000 300 0` in creative.
