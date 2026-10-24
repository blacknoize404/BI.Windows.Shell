package bi.windows.shell;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Tema oscuro para la barra de título y para los menús clásicos que dibuja la shell: usa los ordinales
 * no documentados de {@code uxtheme.dll} que usan las propias aplicaciones de Microsoft.
 * <p>Es de mejor esfuerzo y lo declara: si el sistema no lo obedece, todo queda como estaba y el
 * resultado lo dice. Dos detalles probados en Windows 11: la función 135 ({@code SetPreferredAppMode})
 * devuelve el modo anterior, no un HRESULT, y la 136 ({@code FlushMenuThemes}) hay que llamarla justo
 * antes de cada menú propio porque la shell invalida la caché del tema.
 */
public final class WindowsDarkMode {

    private static final int SET_PREFERRED_APP_MODE = 135;
    private static final int FLUSH_MENU_THEMES = 136;
    private static final int FORCE_DARK = 2;
    private static final int FORCE_LIGHT = 3;

    private WindowsDarkMode() {
    }

    /** Si el anfitrión quiere evidencia (una línea JSON por llamada), la engancha aquí; {@code null} no escribe nada. */
    public static void setTelemetry(Consumer<String> sink) {
        Native.telemetry = sink;
    }

    /** Oscuro para todo lo que la shell dibuje a partir de ahora en este proceso, y barra de título oscura. */
    public static DarkModeResult apply(long hwnd) {
        return set(hwnd, true, true);
    }

    /**
     * Fuerza el tema oscuro o el claro.
     *
     * @param hwnd     la ventana cuya barra de título cambia; 0 si solo importan los menús
     * @param dark     oscuro o claro
     * @param titleBar si se cambia también la barra de título (atributo de DWM)
     */
    public static DarkModeResult set(long hwnd, boolean dark, boolean titleBar) {
        if (!Native.WINDOWS) return new DarkModeResult(false, 0, 0, "no es Windows");
        try (Arena arena = Arena.ofConfined()) {
            if (hwnd != 0 && titleBar) setTitleBar(arena, hwnd, dark);
            long function = ordinal(SET_PREFERRED_APP_MODE);
            if (function == 0) return new DarkModeResult(false, 0, 0, "el ordinal 135 no existe en este Windows");
            MethodHandle setMode = Native.Win.LINKER.downcallHandle(MemorySegment.ofAddress(function),
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT));
            int previous = (int) setMode.invokeExact(dark ? FORCE_DARK : FORCE_LIGHT);
            flushMenus();
            Native.emit("DarkMode.Apply", "os", System.getProperty("os.version", ""), "dark", dark,
                    "applied", previous >= 0, "previousMode", previous, "ordinal", SET_PREFERRED_APP_MODE);
            return new DarkModeResult(previous >= 0, previous, SET_PREFERRED_APP_MODE, null);
        } catch (Throwable t) {
            return new DarkModeResult(false, 0, 0, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Llamar justo antes de cada menú propio ({@code TrackPopupMenu}): la shell invalida la caché del tema a veces. */
    public static void flushMenus() {
        if (!Native.WINDOWS) return;
        try {
            long function = ordinal(FLUSH_MENU_THEMES);
            if (function == 0) return;
            MethodHandle flush = Native.Win.LINKER.downcallHandle(MemorySegment.ofAddress(function), FunctionDescriptor.ofVoid());
            flush.invokeExact();
        } catch (Throwable ignored) {
            // mejor esfuerzo
        }
    }

    private static void setTitleBar(Arena arena, long hwnd, boolean dark) throws Throwable {
        MemorySegment value = arena.allocate(JAVA_INT);
        value.set(JAVA_INT, 0, dark ? 1 : 0);
        int hr = (int) Native.Win.DWM_SET_WINDOW_ATTRIBUTE.invokeExact(hwnd, 20, value, 4);   // 20 en las versiones actuales
        if (hr != 0) {
            int ignored = (int) Native.Win.DWM_SET_WINDOW_ATTRIBUTE.invokeExact(hwnd, 19, value, 4);   // Windows 10 antiguo
        }
    }

    /** La dirección de una función de {@code uxtheme.dll} por su ordinal, o 0. */
    private static long ordinal(int ordinal) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            long module = (long) Native.Win.LOAD_LIBRARY.invokeExact(Native.wide(arena, "uxtheme.dll"));
            if (module == 0) return 0;
            return (long) Native.Win.GET_PROC_ADDRESS.invokeExact(module, (long) ordinal);
        }
    }
}
