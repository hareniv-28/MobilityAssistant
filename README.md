# 🚶‍♀️ Mobility Assistant

**Mobility Assistant** is an Android app that uses on-device AI to detect nearby **people, bicycles, and vehicles**, helping visually impaired users navigate safely.  
It gives **audio and haptic feedback**, runs **offline**, and stays active **even with the screen off**.

---

## 🌟 Key Features
- 👁️ Real-time object detection (on-device TensorFlow Lite)
- 🔊 Bilingual voice alerts (English + Indian language)
- 📳 Haptic feedback for distance and direction
- 🌙 Context awareness (adapts to light, motion, and noise)
- 📴 Screen-off detection mode
- ⚙️ Runs fully offline — no internet needed

---

## 🧠 Tech Stack
| Area | Technology |
|-------|-------------|
| Language | Kotlin |
| ML Framework | TensorFlow Lite |
| Camera | CameraX |
| UI | ViewBinding |
| Feedback | Text-to-Speech + Haptics |

---

## 🏗️ Current Progress
✅ App structure, CameraX integration, and permissions done  
🚧 Model integration in progress (TensorFlow Lite setup)  
🎯 Next: object detection overlay + audio/haptic responses

---

## 🚀 How to Run
1. Clone the repo:
   ```bash
   git clone https://github.com/hareniv-28/MobilityAssistant.git

Open in Android Studio.

2. Sync Gradle files.

3. Run on a real Android device (Android 9.0 or higher).

4. Grant camera permissions when prompted.

🧩 Future Roadmap

-> Add pothole and road hazard detection

-> Integrate GPS-based guidance

-> Optimize inference for low-end devices

-> Add gesture-based controls

👩‍💻 Author

Hareni V
Computer Science Student | Android & AI Developer
