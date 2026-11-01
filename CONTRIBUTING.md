# Contributing

Thanks for helping. Bug reports, protocol findings and pull requests are welcome.

## Before you open an issue

- Read the [FAQ](docs/faq.md) and the [protocol document](docs/ble-protocol.md); most questions are answered there.
- Tell us where your card came from and its firmware version (shown after **Scan and connect**).
  We test on cards we ship (firmware V1.0.0). Cards from other sources may run different firmware, and we cannot debug those.
- For Bluetooth problems, include the phone model, Android version and the log text from the app.

## Pull requests

- Keep the sample dependency-free: plain Java, no AndroidX, no third-party libraries.
- One change per pull request, with a short description of what you tested and on which phone.
- Never commit API keys, keystores or `local.properties`.
- By submitting a pull request you agree that your code is released under the [MIT license](LICENSE)
  and your documentation changes under CC BY 4.0, like the rest of this repository.

## Commercial questions

Sample cards, hardware orders and the developer package (with engineering support, 1,000+ cards): https://aihwstack.com/en/products/recorder/developer/
Please don't use issues for these; we answer them faster through the developer page.
