# BI.Windows.Shell

![License](https://img.shields.io/badge/License-CC--BY--NC--SA%204.0-lightgrey.svg)

Windows shell integration from Java, with no native code of its own (it uses the Foreign Function & Memory API of Java 25):

- **Dark mode** for the title bar and for the classic menus the shell draws.
- **The native context menu of Explorer** for any file, with every system verb and every third-party extension (7-Zip and
  friends), plus your own items at the end.

It is a port of `Blacknoize.Windows.Shell`, the same library written for .NET.

> **Documentación en español**: [README.es.md](README.es.md)

## Install

Published through [JitPack](https://jitpack.io/#blacknoize404/BI.Windows.Shell) from each release tag:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.blacknoize404</groupId>
  <artifactId>BI.Windows.Shell</artifactId>
  <version>v0.1.0</version>
</dependency>
```

It is the Java module `bi.windows.shell` and needs Java 25. Run with `--enable-native-access=bi.windows.shell` (or
`ALL-UNNAMED` on the class path) to avoid the restricted-method warning.

## Dark mode

```java
DarkModeResult result = WindowsDarkMode.apply(hwnd);      // title bar and menus
WindowsDarkMode.set(hwnd, false, true);                   // back to light
```

The result says what happened (`applied`, the previous mode, the ordinal used, the error) instead of promising. It is best
effort: on a Windows that does not obey, everything stays as it was.

## Explorer context menu

```java
ShellMenuResult result = ShellContextMenu.showAtCursor("C:\\Users\\me\\picture.png", hwnd, List.of(
        new ShellMenuExtra("Copy path", () -> copy(path))));
```

Call it from a single-threaded-apartment thread (the JavaFX thread and the AWT event thread are). No failure of the
interop reaches the host as an exception: `ShellMenuResult` is `SHOWN`, `NO_FILE`, `NOT_STA`, `INTEROP_FAILED` or
`UNSUPPORTED` (not Windows). While the menu is open the window is subclassed so the extensions can paint their items;
pass `subclass = false` and forward messages with `ShellContextMenu.tryHandleMenuMessage` if the host already hooks its
window.

Java windows do not expose their handle: `WindowHandles.findOwnWindow(title)` finds the top-level window of the current
process by its title.

`setTelemetry(Consumer<String>)` on both classes receives one JSON line per call, if you want evidence of what ran.

## License

This project is distributed under the [CC BY-NC-SA 4.0](LICENSE) license.
