package bi.windows.shell;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Cómo obtener el HWND de una ventana de Java: ni Swing ni JavaFX lo exponen, así que se busca por título
 * entre las ventanas de este mismo proceso.
 */
public final class WindowHandles {

    private WindowHandles() {
    }

    /**
     * La ventana de nivel superior de este proceso con ese título exacto.
     *
     * @return su HWND, o 0 si no hay ninguna (todavía no se mostró, o no es Windows)
     */
    public static long findOwnWindow(String title) {
        if (!Native.WINDOWS || title == null) return 0;
        long self = ProcessHandle.current().pid();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment wide = Native.wide(arena, title);
            MemorySegment pid = arena.allocate(JAVA_INT);
            long previous = 0;
            while (true) {
                long hwnd = (long) Native.Win.FIND_WINDOW_EX.invokeExact(0L, previous, 0L, wide);
                if (hwnd == 0) return 0;
                int ignored = (int) Native.Win.GET_WINDOW_THREAD_PROCESS_ID.invokeExact(hwnd, pid);
                if (Integer.toUnsignedLong(pid.get(JAVA_INT, 0)) == self) return hwnd;
                previous = hwnd;
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    /** La ventana en primer plano del sistema, o 0. */
    public static long foreground() {
        if (!Native.WINDOWS) return 0;
        try {
            return (long) Native.Win.GET_FOREGROUND_WINDOW.invokeExact();
        } catch (Throwable t) {
            return 0;
        }
    }
}
