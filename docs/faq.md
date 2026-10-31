# FAQ

**Do I need an account, an API key or a partner agreement to talk to the recorder?**
No. The recorder needs no pairing, bonding or authorization. Connect, subscribe to the two notify characteristics and send commands.

**Can I build my own app without buying anything?**
Yes. The protocol in `docs/` and the sample in `android-sample/` already download (with resume), play, transcribe and delete recordings, and build into an APK.
The sample-order source code is a finished app with a full UI and comes with engineering support; the volume-order edition adds real-time audio, recording control and recovery of audio missed while Bluetooth was down.

**Do recordings go through your servers?**
No. The card talks to your app over Bluetooth. Where the audio goes next — your phone, your server, a transcription API — is your choice.

**Which speech-to-text service does it use?**
None is built in. Our reference app accepts any OpenAI-compatible endpoint; the user enters base URL, key and model, and the key stays on the phone.

**Is there a per-device or per-minute fee?**
No license fee, no royalties, no per-device fee. Brand customization of our finished app is a 10 % revenue share.

**Does it work on iPhone?**
Yes, the protocol is the same. iOS hides the Bluetooth MAC, so use command 0-12 (device ID) to tell cards apart.

**How fast is download?**
About 31 KB/s, set by the card. A 1-hour Opus recording (7.2 MB) takes about 4 minutes.

**Is the audio encrypted on the card?**
No. Recordings are stored as plain Opus packets and Bluetooth access is not authenticated. Delete recordings from the card after your app has stored them safely, and do not describe the device as hardware-encrypted.

**Can I update the firmware over Bluetooth?**
No. The protocol has no firmware update command.
