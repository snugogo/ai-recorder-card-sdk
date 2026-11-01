# WB-RC100 (CB08) recorder card — BLE protocol, sample edition

Version 1.1-S · 2026-10-20 · free edition for developers evaluating the recorder

This edition covers connecting, device information, the device ID, the file list, downloading and deleting.
That is enough to build your own Android or iOS app that pulls recordings off the card.

Real-time audio, remote recording control, reconnect handling, resuming downloads, segment download and
microphone gain are in the order edition, delivered with a volume order. Section numbers are the same in both editions.

| Item | Content |
|---|---|
| Device | WB-RC100 recorder card (module name CB08), firmware V1.0.0 |
| Roles | Your app is the BLE Central; the recorder is the Peripheral |
| Test environment | Xiaomi MI 6, Android 9; firmware V1.0.0. The same protocol is used by our own Android and iOS apps |

## 1. Capability statement

All items were tested on a real card with firmware V1.0.0.

| Capability | Supported | Where |
|---|---|---|
| Download saved recordings | Yes, tested | About 30 KB/s; a 60-minute recording (6.9 MB) downloads in 3 min 49 s. Section 8 |
| File list, delete, battery, storage, firmware, clock | Yes, tested | Sections 5 and 7 |
| Read the device ID (MAC), also on iPhone | Yes, tested | Command 0-12. Section 5 |
| Automatic switch-off | Firmware behaviour | Idle and not connected: off after about 5 minutes. Section 5.1 |
| Real-time audio stream while recording | Yes, tested | Order edition |
| Control recording from the app (start, pause, resume, stop) | Yes, tested | Order edition |
| Keep recording when Bluetooth drops, and recover the missed audio | Yes, tested | Order edition |
| Resume an interrupted download | Yes, tested | Order edition |
| Download only part of a file | Yes, tested | Order edition |
| Microphone gain | Yes, tested | Order edition |
| Firmware update over Bluetooth | No | The protocol has no update command |
| Authorization / pairing code | Not required | No function needs an authorization step |

## 2. BLE transport

### 2.1 Advertising

- Complete local name `CB08` (some units `QS668`).
- Advertised 16-bit service UUID `0xAE20`. You can scan by name or by service.
- Manufacturer data: company ID `0xE434` followed by 5 bytes. Its meaning is not documented; to identify a card, use 0-12 (section 5).
- The card keeps advertising while it records, and while no phone is connected, until the idle switch-off about 5 minutes after the last disconnect (section 5.1).

### 2.2 GATT

| Object | UUID | Properties | Use |
|---|---|---|---|
| Service | `0xAE20` (`0000ae20-0000-1000-8000-00805f9b34fb`) | Primary | Recorder service |
| Characteristic | `0xAE21` | Write Without Response | App → recorder: command frames |
| Characteristic | `0xAE22` | Notify | Recorder → app: replies, file list, file data |
| Characteristic | `0xAE23` | Notify | Recorder → app: button events and some replies |

Subscribe to both AE22 and AE23 and send both to the same command handler. Some replies arrive on AE23 (battery 0-4 was seen there).
Keep a separate reassembly buffer per characteristic so that bytes from the two never interleave.

### 2.3 MTU and writes

- The longest command, download request 2-2, is a 36-byte frame and **must be written in one GATT write**. Split into 20 + 16 bytes, the card answers "file does not exist".
- Request a larger MTU on Android (we request 247). iOS negotiates automatically (typically 185).
- Notifications may split a frame across packets or carry several frames in one packet. Reassemble by the length field.
- No pairing, bonding or authorization is needed.

## 3. Frame format

| Offset | Length | Field | Byte order | Description |
|---|---|---|---|---|
| 0 | 1 | MAGIC | - | `0x5A` |
| 1 | 1 | SEQ | - | Sequence number 0~255, wraps. Not checked by the card |
| 2 | 2 | CRC | LE | CRC-16/XMODEM over the 2 LEN bytes + DATA |
| 4 | 2 | LEN | LE | Number of DATA bytes |
| 6 | LEN | DATA | - | `[TYPE 1B][CMD 1B][parameters]` |

CRC-16/XMODEM: polynomial `0x1021`, initial value `0x0000`, no reflection, no final XOR. Check value: ASCII `123456789` → `0x31C3`.
MAGIC, SEQ and the CRC field itself are not included.

| TYPE | Group |
|---|---|
| 0 | Control: clock, storage, battery, firmware, device ID |
| 1 | Real-time audio and remote recording (order edition) |
| 2 | Files: list, download, delete |
| 3 | Recording state, gain, button events (order edition) |

Commands are written TYPE-CMD below, e.g. 2-2 = TYPE 2, CMD 2. Numbers are decimal unless written 0x.

Example: the app asks for battery (SEQ 1)
```
5A 01 0B DD 02 00 00 03
   SEQ=01  CRC=0xDD0B (LE)  LEN=0002 (LE)  TYPE=00  CMD=03
```
The recorder replies 85 %: `5A 01 46 28 03 00 00 04 55`

## 4. Audio format

| Parameter | Value |
|---|---|
| Codec | Opus, mono, 20 ms frames |
| Bit rate | 16 kbit/s constant: every frame is exactly 40 bytes |
| Data rate | 2000 bytes per second; duration (s) = file size ÷ 2000 |
| On the card | Bare Opus packets back to back: no header, no container, no length prefix |
| Storage use | 7,200,000 bytes per hour. Tested: a 3,601 s recording is exactly 7,202,000 bytes |
| Long recordings | The card closes the file after 3,601 s and continues in a new file. Join them in your app if you need one file |
| Capacity | A 32 GB card reports 30,523,392 KB total: about 255 hours of recording with firmware V1.0.0, which stores Opus + WAV; about 4,341 hours with Opus-only firmware (available for volume orders) |

To play or upload a file, wrap the packets in Ogg Opus (RFC 7845): split every 40 bytes, one packet per frame, 960 samples per frame at 48 kHz granule rate.

Most packets use Opus "code 3" with zero padding. Android 9's built-in decoder rejects these; rewriting them as plain single-frame packets is lossless (decoded PCM is bit-identical).

The card can also return WAV (16 kHz, 16-bit PCM, mono) if you request the file name with the extension `.wav`. WAV is 16 times larger than Opus and downloads slower than real time at about 30 KB/s. We recommend Opus.

## 5. Control commands (TYPE 0)

| CMD | Direction | Name | Parameters / reply | Tested |
|---|---|---|---|---|
| 0 | App→Dev | Set clock | year 2B LE + month + day + hour + minute + second (7 bytes), local time. No reply | Yes |
| 1 | App→Dev | Get storage | None | Yes |
| 2 | Dev→App | Storage reply | free 4B LE + total 4B LE, unit 1 KB | Yes |
| 3 | App→Dev | Get battery | None | Yes |
| 4 | Dev→App | Battery reply | 1 byte: 0~100 %; 110 = charging. May arrive on AE23 | Yes |
| 10 | App→Dev | Get firmware | None | Yes |
| 11 | Dev→App | Firmware reply | ASCII, e.g. `V1.0.0` | Yes |
| 12 | App→Dev | Get device ID | None | Yes |
| 13 | Dev→App | Device ID reply | 13 ASCII bytes: `s` + the BLE MAC as 12 upper-case hex digits, e.g. `sD1A1C201018E` = D1:A1:C2:01:01:8E | Yes |

Example: set clock to 2026-10-19 09:30:15 → `5A 07 FC D0 09 00 00 00 EA 07 0A 13 09 1E 0F`

Recording dates come from the file names (section 8.1), which use the card's clock. Set the clock with 0-0 when your app connects.
On iOS, where the system hides the MAC, 0-12 is the way to tell cards apart. The reply needs no authorization and arrives within 3 s.

### 5.1 Power behaviour

- Connected to a phone: the card stays on, also when idle.
- Not connected and not recording: the card switches itself off after about 5 minutes. It then stops advertising and must be switched on by hand.
- Not connected but recording: the card stays on and keeps recording.
- Battery: 400 mAh, reported by 0-4 in 1 % steps (110 = charging).

## 6. Real-time audio and remote recording (TYPE 1)

Order edition.

## 7. File commands (TYPE 2)

| CMD | Direction | Name | Parameters / reply | Tested |
|---|---|---|---|---|
| 0 | App→Dev | Get file list | None | Yes |
| 1 | Dev→App | List data | count 4B BE + count × 28-byte entries; may come in several frames | Yes |
| 18 | Dev→App | List complete | 1 byte: 0 | Yes |
| 2 | App→Dev | Download | offset 4B LE (0 = from the start) + file name 24 bytes (NUL padded) | Yes |
| 3 | Dev→App | Download starts | File name | Yes |
| 4 | Dev→App | File data | Raw file bytes from the offset, in order, any chunk size | Yes |
| 5 | Dev→App | Download ends | 1 byte: 0 complete, 1 no such file, 2 offset too large, 3 stopped | Yes |
| 7 / 11 | App→Dev / Dev→App | Abort download / reply | None / none | Yes |
| 8 | App→Dev | Delete one file | offset 4B (0) + file name 24 bytes, the same layout as 2-2 | Yes |
| 13 | Dev→App | Delete reply | 1 byte: 0 success, 1 failure | Yes |
| 9 / 10 | App→Dev / Dev→App | Delete all / reply | None / 1 byte: 0 success | No |

If a future firmware rejects the 2-2 layout for 2-8, send the original 28-byte list entry instead.

## 8. File list and download

### 8.1 List entry (28 bytes)

| Offset | Length | Byte order | Field |
|---|---|---|---|
| 0 | 4 | BE | Duration in seconds |
| 4 | 4 | BE | File size in bytes |
| 8 | 20 | - | File name, ASCII, NUL padded, truncated to 20 bytes |

- **Byte order is mixed**: the frame header (LEN, CRC) is little-endian, but count, duration and size in the list are big-endian.
- `count` is the number of entries in this frame. Collect all 2-1 frames until 2-18 arrives; if it never comes, finish about 1.2 s after the last 2-1.
- File names are `noteYYYYMMDD-hhmmss.opus` (24 bytes); recordings made in call mode start with `call`. The list cuts them to 20 bytes, e.g. `note20261019-093015.` — remove the trailing dot and append `.opus` before downloading or deleting.
- The card sends no time field: take the date and time from the name.

### 8.2 Download

1. Send 2-2 with offset 0 and the full name, as one 36-byte write.
2. After 2-3, append the payload of every 2-4 to the file.
3. 2-5 code 0 means done; the byte count equals the size from the list.

Example: download `note20261019-093015.opus` from the start
```
5A 05 F6 5D 1E 00 02 02 00 00 00 00
6E 6F 74 65 32 30 32 36 31 30 31 39 2D 30 39 33 30 31 35 2E 6F 70 75 73
```

### 8.3 Resume

Basic resume: send 2-2 again with offset = the number of bytes you already have, rounded down to a whole
40-byte packet, and append what arrives. The Android sample does this.
The order edition adds the tested parameters for long or repeated interruptions.

### 8.4 Segment download

Order edition.

## 9. Recording state, gain and button events (TYPE 3)

Order edition.

## 10. Parsing and timeouts

| Situation | Handling |
|---|---|
| Fragmented or merged notifications | Reassemble by the length field |
| AE22 and AE23 | Separate buffers, one handler |
| CRC error | Drop one byte and look for the next `0x5A`; log the raw bytes |
| List without 2-18 | Finish about 1.2 s after the last 2-1 |
| No data during download | About 12 s idle = stalled; start the download again |
| Cancel | Send 2-7, wait for 2-11 |
| Same name twice | Store downloads under name + size so they never overwrite each other |

## 11. Acceptance checklist

| # | Check | Pass |
|---|---|---|
| 1 | Scan | CB08 found; advertised services include AE20 |
| 2 | Connect | AE21, AE22, AE23 found; both notifications enabled |
| 3 | Battery / firmware / storage | 0-4, 0-11, 0-2 replies with plausible values |
| 4 | Device ID | 0-12 answered by 0-13 with `s` + 12 hex digits |
| 5 | File list | All entries received, ended by 2-18 |
| 6 | Download | 2-3 … 2-4 … 2-5(0); bytes = list size; Ogg-wrapped file plays |
| 7 | Delete | On a test recording: 2-13(0) and the file is gone from the list |

## Appendix: measured download speed (Xiaomi MI 6, Android 9, firmware V1.0.0)

| Recording | Time | Speed |
|---|---|---|
| 1:47 (214,760 bytes) | 7.1 s | 29.5 KB/s |
| 10:27 (1,255,920 bytes) | 40.0 s | 30.7 KB/s |
| 31:50 (3,821,760 bytes) | 121.7 s | 30.7 KB/s |
| 1:00:01 (7,202,000 bytes) | 229.0 s | 30.7 KB/s, 15.7× faster than real time |

The card sends at most about 200 notifications of 160 bytes per second (about 31 KB/s).

> ⚠️ Deleting files is irreversible. Test 2-8 only on recordings made for testing.
