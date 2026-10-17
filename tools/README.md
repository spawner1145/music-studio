# Music Studio audio codec

The Windows codec is a separate FFmpeg executable, invoked without a shell for local audio processing.

- FFmpeg 7.1 essentials build from Gyan.dev, supplied by the installed imageio-ffmpeg package.
- Upstream: https://ffmpeg.org/
- Version source: https://ffmpeg.org/releases/ffmpeg-7.1.tar.xz
- Build project and dependency information: https://github.com/GyanD/codexffmpeg
- Distribution: https://www.gyan.dev/ffmpeg/builds/
- License: GNU GPL v3 or later; see FFMPEG-LICENSE.txt and the upstream source for full terms.

The mod JAR does not embed the executable. installMod copies this executable and notices to the instance musicstudio/tools/ folder. Development runs use the copy in this folder. On other systems install an appropriate FFmpeg binary at musicstudio/tools/ffmpeg, or set the musicstudio.ffmpeg JVM property to its absolute path.
