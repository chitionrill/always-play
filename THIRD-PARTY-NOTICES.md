# Third-Party Notices

This project's own source code is licensed under **CC BY-NC-SA 4.0** (see [LICENSE](LICENSE)).
That license applies only to code written for this mod. It does **not** apply to the
third-party libraries listed below, which are bundled unmodified into the built jar
(via Fabric Loom's `include`, i.e. as nested jar-in-jar files, not merged/shaded bytecode)
to provide MP3/OGG/FLAC decoding for the Custom Music feature. Each one keeps its own
original license, and none of them are restricted by, or restrict, the CC BY-NC-SA terms
that cover this project's own code.

| Library | Version | License | Homepage |
|---|---|---|---|
| mp3spi | 1.9.5.4 | GNU LGPL 2.1 | http://www.javazoom.net/mp3spi/mp3spi.html |
| jlayer | 1.0.1.4 | GNU LGPL 2.1 | http://www.javazoom.net/javalayer/sources.html |
| tritonus-share | 0.3.7.4 | GNU LGPL 2.1 | http://tritonus.org/ |
| vorbisspi | 1.0.3.3 | GNU LGPL 2.1 | http://www.javazoom.net/vorbisspi/vorbisspi.html |
| jflac-codec | 1.5.2 | GNU LGPL 2.1 | http://jflac.org/ |

All five are distributed under the Maven coordinates `com.googlecode.soundlibs:*`
(mp3spi, jlayer, tritonus-share, vorbisspi) and `org.jflac:jflac-codec`, unmodified
from their published upstream releases. No changes were made to their source.

## What this means in practice

- These libraries are **not** modified — only used as-is via the standard
  `javax.sound.sampled` SPI mechanism.
- Because they're included as separate nested jars (Fabric's jar-in-jar), rather than
  merged class-by-class into this mod's own jar, anyone can still extract and replace
  them independently, which satisfies the LGPL's relinking requirement.
- Full license text for LGPL 2.1: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

**Note:** This file is provided for transparency and good-faith compliance, not as a
legal opinion. If you plan to redistribute this mod commercially or in a way that goes
beyond typical Fabric mod distribution, consider having the licensing reviewed properly.
