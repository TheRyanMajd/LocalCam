# LocalCam 
<img width="100" height="100" alt="localcam_icon" src="https://github.com/user-attachments/assets/f6893cc6-520c-4307-b6f3-badf9162f649" />


Vibe-coded an Android app to revive a 10+ year old phone as an IP camera.

---
<img width="300" height="300" alt="image" src="https://github.com/user-attachments/assets/9c47147d-0f16-4e74-9edc-a34ba2e6f276" />

**Platform:** Should work on any Android 6 and Above!
**Streaming:** RTSP H.264 + AAC defaulted to 15 fps, 1 Mbps  
**UI:** Jetpack Compose + Material 2  

**Features:**
- Zero-install: sideload APK → grant camera+mic → Start
- Auto-discovers device IPv4 at runtime
- Sub-4 s latency in `ffplay`
- Settings (resolution, fps, bitrate, rotation, port) auto saved in `settings.json`

**Pain points:**
- Learning Android Studio & Gradle
- RTSP/TCP buffering quirks
- JitPack dependency deprecations
- AI suggestions defaulting to Material 3 when I needed Material 2
