# MyCPAssistant ⚡

**A powerful, modern, and lightweight Competitive Programming companion plugin for IntelliJ IDEA.**

Inspired by CPH (VS Code) and KHelper, **MyCPAssistant** integrates with the [Competitive Companion](https://github.com/jmerle/competitive-companion) browser extension to streamline your competitive programming workflow in IntelliJ IDEA.

---

## ✨ Features

- **⚡ One-Click Problem Parsing**: Parse problems directly from Codeforces, AtCoder, LeetCode, CodeChef, CSES, HackerRank, and 60+ online judges.
- **🖥️ CPH-Style Interactive Test Runner**: Dedicated right-sidebar tool window with auto-expanding test cards, live execution timers, and clear status badges (`PASS`, `FAIL`, `ERROR`, `TLE`).
- **🔄 Live In-Memory Compilation**: Runs your tests directly from the editor buffer &mdash; no need to save (`Ctrl+S`) before running!
- **📂 Zero-Clutter File Organization**: Solutions stay cleanly in `src/`, while sample inputs/outputs are isolated in `.cptests/`.
- **🔍 Active Tab Auto-Sync**: The test runner automatically switches and displays test cases whenever you select or open a problem file in the editor.
- **✏️ Custom Test Cases**: Add and edit custom test cases with automatic disk synchronization.
- **🛠️ Fully Customizable**: Custom code templates (with placeholders `${PROBLEM_NAME}`, `${DATE}`, `${URL}`, etc.), output directories, and ports configurable in **Settings &rarr; Tools &rarr; Custom CP Companion**.

---

## ⌨️ Shortcuts

| Shortcut | Action |
|---|---|
| `Ctrl + Shift + F10` | Run all test cases for the active CP solution |
| `Ctrl + Alt + N` | Manually create a CP solution file without browser extension |

---

## 🚀 Quick Start

1. Install the **Competitive Companion** extension in your browser ([Chrome](https://chromewebstore.google.com/detail/competitive-companion/cjnmckjndlpiamhfimnnachnppdelkej) / [Firefox](https://addons.mozilla.org/en-US/firefox/addon/competitive-companion/)).
2. Open your competitive programming project in IntelliJ IDEA.
3. Open any problem page on Codeforces, AtCoder, etc., and click the green **+** Competitive Companion icon in your browser.
4. Your solution file will automatically generate and open, and all sample test cases will appear in the **CP Tests** sidebar on the right!
5. Write your solution and press **▶ Run All** (or `Ctrl+Shift+F10`) to test!

---

## 📦 Installation

### From JetBrains Marketplace
Search for **"MyCPAssistant"** in:
`Settings` &rarr; `Plugins` &rarr; `Marketplace` &rarr; Install

### From Disk (ZIP)
1. Download `MyCPAssistant-1.0.0.zip` from [Releases](https://github.com/CoderSanket24/MyCPAssistant/releases).
2. In IntelliJ IDEA: `Settings` &rarr; `Plugins` &rarr; `⚙️` &rarr; `Install Plugin from Disk...`
3. Select the `.zip` file and restart IntelliJ.

---

## 📄 License
This project is licensed under the [Apache License 2.0](LICENSE).
