# Privacy Policy

Local Voice IME does not collect, transmit or share any data. What you say and what you type
never leave your phone.

- **Voice input.** Audio from the microphone is processed entirely on your device by a speech
  model stored on it. Audio is kept in memory only while an utterance is being recognized;
  it is never written to storage and never sent anywhere. The microphone is only used while you
  hold the space bar or while the voice input panel is open and listening.
- **What you type.** Like any keyboard, the app sees the text you enter. Pinyin user dictionary
  and input history (used to improve predictions) and clipboard history are stored in the app's
  private storage on your device and can be cleared or disabled in the app's settings.
  Nothing is learned from fields that apps mark as private/incognito, and voice input is not
  offered on password fields.
- **The network is used for one thing: downloading a speech model, when you ask for it.**
  Typing works without ever going online; voice input needs a speech model, which is not part
  of the installed app. If you tap *Download* under *Settings → Voice input*, the app fetches
  the model of that row over HTTPS from modelscope.cn, a model hosting service run by Alibaba
  Cloud: the standard model
  ([SenseVoice Small](https://www.modelscope.cn/models/pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue),
  about 240 MB), or the optional high-accuracy model
  ([FireRedASR2](https://www.modelscope.cn/models/csukuangfj/FireRedASR2-AED-onnx), about
  1.2 GB). Once a model is on the phone, using it needs no connection.
  - The request is a plain download of fixed files. It carries no audio, no text, no account
    and no identifier made by this app. As with any download, the server sees your IP address
    and when you downloaded; this project has no access to that.
  - The files are accepted only if they match checksums fixed in the app, so a server or
    network that delivers something else cannot make the app run it.
  - The app never connects on its own: not at start-up, not for updates, not for statistics.
    It contains no analytics, crash reporting or advertising code.
- **Permissions.** `RECORD_AUDIO` (voice input), `INTERNET` (the model download described
  above, nothing else), `VIBRATE` (key press feedback), `POST_NOTIFICATIONS` (status
  notifications, e.g. while importing dictionaries).

You can verify this yourself. All code that opens a connection is in one file,
[`VoiceModelFetch.kt`](../app/src/main/java/org/fcitx/fcitx5/android/input/voice/VoiceModelFetch.kt);
`scripts/check-privacy.sh <apk>` fails if any other source file, permission or dependency could
reach the network. On Android versions and phones that let you deny network access per app, the
keyboard, and dictation with a model that is already on the phone, keep working with it
denied; only the download does not.

Until version 0.3 the app did not hold the `INTERNET` permission and the high-accuracy model
came inside a separate 1.4 GB APK, which was too large to install on some phones. Since 0.4 there is one
APK and that model is a download. Up to 0.4.1 the standard model was still part of the APK
(about 300 MB); since then it is a download as well, for the same reason.
