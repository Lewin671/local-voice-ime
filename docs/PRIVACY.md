# Privacy Policy

Local Voice IME does not collect, transmit or share any data.

- **No network access.** The app does not request the Android `INTERNET` permission (nor any other
  network-capable permission). It cannot connect to any server, and it contains no analytics,
  crash reporting or advertising code.
- **Voice input.** Audio from the microphone is processed entirely on your device by a speech
  model bundled with the app. Audio is kept in memory only while an utterance is being recognized;
  it is never written to storage. The microphone is only used while you hold the space bar or
  while the voice input panel is open and listening.
- **What you type.** Like any keyboard, the app sees the text you enter. Pinyin user dictionary
  and input history (used to improve predictions) and clipboard history are stored in the app's
  private storage on your device and can be cleared or disabled in the app's settings.
  Nothing is learned from fields that apps mark as private/incognito, and voice input is not
  offered on password fields.
- **Permissions.** `RECORD_AUDIO` (voice input), `VIBRATE` (key press feedback),
  `POST_NOTIFICATIONS` (status notifications, e.g. while importing dictionaries).

You can verify the first point yourself: `scripts/check-privacy.sh <apk>` or
`aapt2 dump permissions <apk>`.
