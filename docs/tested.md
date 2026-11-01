# Tested with a real card

Card: WB-RC100 (CB08), firmware V1.0.0. Tests run on 1 November 2026.
The recording is a 58-second phone call the card recorded in call mode (116,320 bytes). Its transcript is in Chinese: a bank's automated phone menu.

## Android sample 1.1.0 (Xiaomi Mi 6, Android 9)

| Step | Result | Screenshot |
|---|---|---|
| Connect, device info | Battery, firmware, storage and device ID read | [android-1-connect](tested/android-1-connect.webp) |
| List | 1 recording, marked `(call)` | [android-2-list](tested/android-2-list.webp) |
| Resume after an interrupted download | The app was closed 0.3 s into the download, leaving a 4,640-byte `.part` file. After reconnecting, Download logged "Resuming … from byte 4640" and finished; the file has the same SHA-256 as a full download | [android-3-resume](tested/android-3-resume.webp) |
| Play, transcribe | Plays; transcribed with OpenAI speech-to-text | [android-4-play-transcribe](tested/android-4-play-transcribe.webp) |
| Share | The share sheet offers the `.ogg` file; the transcript arrives as text in the receiving app (here a new SMS, which was then discarded; SMS itself does not accept `.ogg` attachments) | [android-5-share](tested/android-5-share.webp), [android-6-share-text](tested/android-6-share-text.webp) |
| Delete from the card | Confirmation, "Deleted.", list shows 0 recordings | [android-7-delete-confirm](tested/android-7-delete-confirm.webp), [android-8-deleted](tested/android-8-deleted.webp) |

## Reference app, sample edition 1.0.5 (Xiaomi Mi 6, Android 9)

This is the app that comes with a sample order, not the free sample in this repository. Tested on 2 November 2026 with recordings of one hour each.

| Step | Result | Screenshot |
|---|---|---|
| Download a 1-hour recording | 6.9 MB at a steady 30.4 KB/s (30.5 KB/s measured separately on the saved file), about 4 minutes. The saved Ogg file is complete: 3,603 pages, end-of-stream set, 60 min 01 s, the same length as on the card | [sample-1-download-speed](tested/sample-1-download-speed.webp) |
| Long recordings | The card splits a long recording into 1-hour files (1:00:01 each) | [sample-2-hour-segments](tested/sample-2-hour-segments.webp) |

## Web demo (Chrome on Windows 11)

| Step | Result | Screenshot |
|---|---|---|
| Pair | Chrome's device chooser lists the card | [web-1-pair](tested/web-1-pair.webp) |
| Connect, device info, list | Same values as the Android app | [web-2-connect-list](tested/web-2-connect-list.webp) |
| Download | Completes; slower than the phone app, because the browser cannot ask for a faster Bluetooth connection | [web-3-download](tested/web-3-download.webp) |
| Play, save `.ogg` | Plays with pause; the saved file is 58.16 s | [web-4-play](tested/web-4-play.webp) |
| Pause and resume | Paused at 21 KB, resumed from byte 22,080 (a whole 40-byte packet), finished at 113 KB | [web-5-pause](tested/web-5-pause.webp), [web-6-resume](tested/web-6-resume.webp), [web-7-resume-done](tested/web-7-resume-done.webp) |

## Card as a USB drive

Connected with a USB data cable, the card shows up as an ordinary USB drive.

| Device | Result | Screenshot |
|---|---|---|
| Windows 11 PC | Shows up in File Explorer as a USB drive (here F:) | [usb-windows](tested/usb-windows.webp) |
| iPhone (Files app) | Shows up as an external drive named NO NAME; files open | [usb-iphone](tested/usb-iphone.webp) |

## Not tested yet

- Android sample: **Import audio file (USB) and transcribe**.
- iOS: the protocol is the same, but there is no iOS sample in this repository and it has not been tested here.
