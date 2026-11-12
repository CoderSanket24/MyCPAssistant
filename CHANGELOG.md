<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MyCPAssistant Changelog

## [Unreleased]

## [1.0.0] - 2026-11-11
### Added
- Initial public release of **MyCPAssistant** on JetBrains Marketplace.
- Full Competitive Companion integration listening on port 27121 (Codeforces, AtCoder, LeetCode, CodeChef, CSES, and 60+ platforms).
- Interactive CPH / KHelper style right sidebar with auto-sizing test cards, live execution timing, and status pills (PASS, FAIL, ERROR, TLE).
- In-memory live editor compilation (no need to save before running tests).
- Clean directory architecture: solution files in `src/`, all test cases stored in a dedicated `.cptests/` directory.
- Auto-sync: automatically detects and displays test cases for the problem active in the editor tab.
- Custom test cases support with automatic disk synchronization.
- Configurable code templates, listener port, output and test case directories under Settings -> Tools -> Custom CP Companion.
- Keyboard shortcuts: `Ctrl+Shift+F10` to run tests and `Ctrl+Alt+N` to manually create a CP file.
