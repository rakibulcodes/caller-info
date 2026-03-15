# CallerInfo

CallerInfo is an Android application that provides real-time Caller ID functionalities by seamlessly integrating with the Telegram network using TDLib. Instead of relying on expensive third-party APIs, it leverages a Telegram Caller ID bot (`@TrueCalleRobot`) behind the scenes to fetch caller information for incoming calls and displays it in a floating overlay.

## ✨ Features

- **Real-Time Caller ID**: Intercepts incoming phone calls and instantly looks up the phone number.
- **Floating Overlay**: Displays the caller's name, carrier, and country in a non-intrusive floating window over the incoming call screen.
- **Headless Telegram Client**: Built on **TDLib (Telegram Database Library)** to act as a silent Telegram client, sending messages to the bot and parsing results entirely in the background.
- **Auto Telegram Setup**: Automatically joins the required Telegram group (`@true_caller`), unblocks, and starts the lookup bot (`@TrueCalleRobot`) before making queries, without requiring any manual setup from the user.
- **Smart Result Caching**: Uses **Room Database** to cache previously searched numbers locally. If a known number calls while you are offline, it instantly retrieves the saved data.
- **Resilient Network Handling**: Automatically recovers and reconnects the TDLib instance when switching between Wi-Fi and Cellular networks or restoring internet connections.

## 🛠️ Technology Stack

- **Kotlin**
- **Android SDK** (Min 29, Target 35)
- **TDLib** (Telegram JNI Wrapper)
- **Room Database** (Local caching)
- **Coroutines & SharedFlow** (Asynchronous operations and state management)

## 🔐 Required Permissions

The app requires the following system permissions to operate effectively:

1. **Phone State & Call Log (`READ_PHONE_STATE`, `READ_CALL_LOG`)**: Required to detect when the phone is ringing and extract the incoming phone number.
2. **Draw Over Other Apps (`SYSTEM_ALERT_WINDOW`)**: Required to display the Caller ID popup overlay seamlessly on top of the default phone dialer.
3. **Internet & Network State**: Required to communicate with the Telegram servers and monitor connection status.

## 🚀 How It Works

1. **Authentication**: The user logs into their Telegram account via the app's initial setup screen.
2. **Incoming Call Hook**: An Android `BroadcastReceiver` listens for incoming network calls.
3. **Pre-flight Checks**: The app guarantees the user has securely joined the necessary backend Telegram groups.
4. **Data Fetching**: The app sends a background message to the bot with the caller's number and listens to the incoming TDLib message streams for the typed HTML response (e.g., Name, Carrier, Country limit messages).
5. **Local Database**: Parsed information is saved to Room SQLite so repeated calls do not consume network or bot search limits.

## ⚙️ Setup & Build

1. Clone this repository and open the project in **Android Studio**.
2. Make sure you have the required `tdjni` native libraries properly linked in your `app/src/main/jniLibs` directory or included via dependencies.
3. Configure your Telegram `api_id` and `api_hash` (these can be obtained from [my.telegram.org](https://my.telegram.org)).
4. Sync Gradle files and run `assembleDebug`. Ensure a physical device is used, as telephony functions are best tested outside an emulator.

## 📝 License

This project is for educational purposes and internal use. Make sure to comply with Telegram's API Terms of Service and local privacy laws when handling caller datasets.
