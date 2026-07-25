# 🤫 QuietHours

**QuietHours** is an intelligent Android application built with Jetpack Compose that automates your device's ringer mode and media volume based on your weekly schedules. 

No more embarrassing phone rings during a quiet lecture, or missing important calls because you forgot to unmute your phone after class! Designed perfectly for college students—just snap a picture of your class timetable, and QuietHours will automatically mute your phone during classes and restore the volume when you're done.

---

## ✨ Features

- **🕒 Automated Volume Control**: Automatically mutes your phone when an event starts, and restores the previous volume when it ends.
- **📅 Weekly Schedules & Timetables**: Organize your routines by day of the week.
- **📸 Smart OCR Import**: Snap a photo or upload an image of your schedule/timetable. QuietHours uses ML Kit to parse the image and automatically import your events!
- **🎨 Material You Design**: A beautiful, modern, and intuitive user interface built entirely with Jetpack Compose, following Material 3 design guidelines.
- **🔋 Battery Efficient**: Uses Android's modern `WorkManager` and `AlarmManager` for precise scheduling without draining your battery in the background.

## 🛠️ Tech Stack

- **Language:** [Kotlin](https://kotlinlang.org/)
- **UI Framework:** [Jetpack Compose](https://developer.android.com/jetpack/compose)
- **Local Storage:** [Room Database](https://developer.android.com/training/data-storage/room)
- **Background Work:** [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) & `AlarmManager`
- **Machine Learning:** [Google ML Kit](https://developers.google.com/ml-kit) (Text Recognition / OCR)

## 📱 Permissions Explained

QuietHours requires the following permissions to function correctly:
- `Do Not Disturb Access (ACCESS_NOTIFICATION_POLICY)` & `MODIFY_AUDIO_SETTINGS`: To change your phone's ringer and media volumes.
- `Alarms & Reminders (SCHEDULE_EXACT_ALARM)`: To ensure your schedules trigger at the exact right minute.
- `Camera` & `Storage`: Used exclusively for the OCR timetable scanning feature (importing schedules from images).

## 🚀 Getting Started

To build and run this project locally:

1. **Clone the repository:**
   ```bash
   git clone https://github.com/Shushant17711/QuietHours.git
   ```
2. **Open the project in Android Studio.**
3. **Sync the Gradle files.**
4. **Run the app** on an emulator or a physical device running Android 8.0 (API level 26) or higher.

## 📸 Screenshots

| Home | Add Schedule | OCR Scanner |
| :---: | :---: | :---: |
| <img src="https://via.placeholder.com/250x500.png?text=Home+Screen" width="200" /> | <img src="https://via.placeholder.com/250x500.png?text=Add+Schedule" width="200" /> | <img src="https://via.placeholder.com/250x500.png?text=OCR+Import" width="200" /> |

*(Note: Add your actual app screenshots to the repository and update these paths!)*

## 🤝 Contributing

Contributions, issues, and feature requests are welcome! 
Feel free to check out the [issues page](https://github.com/Shushant17711/QuietHours/issues).

## 📄 License

This project is licensed under the [MIT License](LICENSE).
