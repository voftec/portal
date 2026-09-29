# Vendored from UxPlay

Source: https://github.com/FDH2/UxPlay
Upstream commit: `b3202dfbf32d28e0cb4db4e87379e61ad43456ea` (v1.74)
License: GPL-3.0-or-later (upstream license headers preserved).

Contents: `lib/` with its `playfair`, `llhttp` and `mdnsd` subdirectories.
Omitted: `lib/dns_sd` (Bonjour/Avahi backend — we use the internal `mdnsd`
backend, same as upstream's `USE_MDNS` build), `renderers/` and `uxplay.cpp`
(replaced by our Android pipeline in `../airplay_jni.cpp`).

## Local patches

Keep this list current; patches must stay minimal.

1. `lib/mdnsd/mdnsd.c`: (TBD — none yet)
