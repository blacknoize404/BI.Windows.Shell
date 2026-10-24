# BI.Windows.Shell

![License](https://img.shields.io/badge/License-CC--BY--NC--SA%204.0-lightgrey.svg)

Integración con la shell de Windows desde Java, sin código nativo propio (usa la API de funciones externas, FFM, de Java 25):

- **Modo oscuro** para la barra de título y para los menús clásicos que dibuja la shell.
- **El menú contextual nativo del Explorador** para cualquier archivo, con todos los verbos del sistema y todas las
  extensiones de terceros (7-Zip y compañía), y elementos propios al final.

Es el port de `Blacknoize.Windows.Shell`, la misma biblioteca escrita para .NET.

> **English documentation**: [README.md](README.md)

## Instalación

Se publica con [JitPack](https://jitpack.io/#blacknoize404/BI.Windows.Shell) a partir de cada etiqueta de versión:

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

Es el módulo Java `bi.windows.shell` y necesita Java 25. Ejecuta con `--enable-native-access=bi.windows.shell` (o
`ALL-UNNAMED` en el classpath) para evitar el aviso de métodos restringidos.

## Modo oscuro

```java
DarkModeResult result = WindowsDarkMode.apply(hwnd);      // barra de título y menús
WindowsDarkMode.set(hwnd, false, true);                   // de vuelta al claro
```

El resultado dice lo que pasó (`applied`, el modo anterior, el ordinal usado, el error) en vez de prometerlo. Es de mejor
esfuerzo: en un Windows que no obedece, todo queda como estaba.

## Menú contextual del Explorador

```java
ShellMenuResult result = ShellContextMenu.showAtCursor("C:\\Users\\yo\\imagen.png", hwnd, List.of(
        new ShellMenuExtra("Copiar ruta", () -> copiar(ruta))));
```

Llámalo desde un hilo de apartamento COM monohilo (el hilo de JavaFX y el de eventos de AWT lo son). Ningún fallo del
interop llega al anfitrión como excepción: `ShellMenuResult` es `SHOWN`, `NO_FILE`, `NOT_STA`, `INTEROP_FAILED` o
`UNSUPPORTED` (no es Windows). Mientras el menú está abierto la ventana se subclasifica para que las extensiones pinten sus
elementos; con `subclass = false` y reenviando los mensajes con `ShellContextMenu.tryHandleMenuMessage` se evita si el
anfitrión ya engancha su ventana.

Las ventanas de Java no exponen su identificador: `WindowHandles.findOwnWindow(título)` encuentra la ventana de nivel
superior del proceso actual por su título.

`setTelemetry(Consumer<String>)` en las dos clases recibe una línea JSON por llamada, por si quieres constancia de lo que se ejecutó.

## Licencia

Este proyecto se distribuye bajo la licencia [CC BY-NC-SA 4.0](LICENSE).
