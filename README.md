# LocalCam 
<img width="100" height="100" alt="localcam_icon" src="https://github.com/user-attachments/assets/f6893cc6-520c-4307-b6f3-badf9162f649" />


Vibe-coded an Android app to revive a 10+ year old phone as an IP camera. <br>
<img width="300" alt="image" src="https://github.com/user-attachments/assets/9c47147d-0f16-4e74-9edc-a34ba2e6f276" />

---

**Platform:** Should work on any Android 6 and Above!
**Streaming:** RTSP H.264 + AAC defaulted to 15 fps, 1 Mbps  
**UI:** Jetpack Compose + Material 2  
---
## Screenshots of App
<div align="center">
  <img width="300" alt="Screenshot_20250714_124013" src="https://github.com/user-attachments/assets/a2248f76-26f5-45c0-a942-f6557bf69897" />
  <img width="300" alt="Screenshot_20250714_124031" src="https://github.com/user-attachments/assets/df060aa9-f6b4-4f75-9b8a-bd3cf1803327" />
  <img width="300" alt="Screenshot_20250714_124045" src="https://github.com/user-attachments/assets/201fafb8-663b-491e-a5aa-e9e1e9b00dc1" />
</div>
---
**Features:**
- Zero-install: sideload APK → grant camera+mic → Start
- Auto-discovers device IPv4 at runtime
- Sub-4 s latency in `ffplay`
- Settings (resolution, fps, bitrate, rotation, port) are saved in `settings.json`

**Pain points:**
- Learning Android Studio & Gradle
- RTSP/TCP buffering quirks
- JitPack dependency deprecations
- AI suggestions defaulting to Material 3 when I needed Material 2

## How To Connect
<img width="300" alt="How to Connect" src="https://github.com/user-attachments/assets/398a1d17-f1af-40e3-a46a-f035a6a56246" />
