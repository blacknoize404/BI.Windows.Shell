package bi.windows.shell;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Lo común del interop: el enlazador, las funciones de Win32 que se usan y las llamadas a métodos de COM
 * por su tabla virtual. Los identificadores (HWND, HMENU, pidl, HMODULE) viajan como {@code long}.
 * <p>Solo se toca en Windows: {@link Win} se carga la primera vez que se usa, no antes.
 */
final class Native {

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    static volatile Consumer<String> telemetry;

    private Native() {
    }

    /** Una línea JSON para la telemetría; los valores se escriben como texto o número. */
    static void emit(String op, Object... pairs) {
        Consumer<String> sink = telemetry;
        if (sink == null) return;
        try {
            StringBuilder json = new StringBuilder("{\"app\":\"BI.Windows.Shell\",\"op\":").append(quote(op));
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                json.append(',').append(quote(String.valueOf(pairs[i]))).append(':');
                Object value = pairs[i + 1];
                if (value instanceof Number || value instanceof Boolean) json.append(value);
                else json.append(quote(String.valueOf(value)));
            }
            sink.accept(json.append('}').toString());
        } catch (RuntimeException ignored) {
            // la telemetría nunca tumba al anfitrión
        }
    }

    private static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    /** Escribe un GUID ({@code 000214E6-0000-0000-C000-000000000046}) con la disposición que espera Windows. */
    static MemorySegment guid(Arena arena, String text) {
        String[] parts = text.split("-");
        MemorySegment seg = arena.allocate(16, 4);
        seg.set(ValueLayout.JAVA_INT_UNALIGNED, 0, (int) Long.parseLong(parts[0], 16));
        seg.set(ValueLayout.JAVA_SHORT_UNALIGNED, 4, (short) Integer.parseInt(parts[1], 16));
        seg.set(ValueLayout.JAVA_SHORT_UNALIGNED, 6, (short) Integer.parseInt(parts[2], 16));
        String tail = parts[3] + parts[4];
        for (int i = 0; i < 8; i++) seg.set(ValueLayout.JAVA_BYTE, 8 + i, (byte) Integer.parseInt(tail.substring(2 * i, 2 * i + 2), 16));
        return seg;
    }

    /** Cadena terminada en cero en UTF-16, como las que esperan las funciones {@code W}. */
    static MemorySegment wide(Arena arena, String text) {
        return arena.allocateFrom(text, java.nio.charset.StandardCharsets.UTF_16LE);
    }

    /** El método {@code index} de la tabla virtual del objeto COM; la llamada recibe {@code this} primero. */
    static MethodHandle method(long object, int index, FunctionDescriptor descriptor) {
        long table = MemorySegment.ofAddress(object).reinterpret(8).get(JAVA_LONG, 0);
        long function = MemorySegment.ofAddress(table).reinterpret(8L * (index + 1)).get(JAVA_LONG, 8L * index);
        return Win.LINKER.downcallHandle(MemorySegment.ofAddress(function), descriptor);
    }

    static int release(long object) {
        try {
            return (int) method(object, 2, FunctionDescriptor.of(JAVA_INT, JAVA_LONG)).invokeExact(object);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Todo lo de Win32 que se usa, enlazado una vez. */
    static final class Win {
        static final Linker LINKER = Linker.nativeLinker();
        private static final Arena LIBS = Arena.global();
        private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32.dll", LIBS);
        private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", LIBS);
        private static final SymbolLookup SHELL32 = SymbolLookup.libraryLookup("shell32.dll", LIBS);
        private static final SymbolLookup DWMAPI = SymbolLookup.libraryLookup("dwmapi.dll", LIBS);
        private static final SymbolLookup OLE32 = SymbolLookup.libraryLookup("ole32.dll", LIBS);

        static final MethodHandle LOAD_LIBRARY = bind(KERNEL32, "LoadLibraryW", JAVA_LONG, ADDRESS);
        static final MethodHandle GET_PROC_ADDRESS = bind(KERNEL32, "GetProcAddress", JAVA_LONG, JAVA_LONG, JAVA_LONG);
        static final MethodHandle DWM_SET_WINDOW_ATTRIBUTE = bind(DWMAPI, "DwmSetWindowAttribute", JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT);
        static final MethodHandle SH_PARSE_DISPLAY_NAME = bind(SHELL32, "SHParseDisplayName", JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS);
        static final MethodHandle SH_BIND_TO_PARENT = bind(SHELL32, "SHBindToParent", JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS);
        static final MethodHandle IL_FREE = bindVoid(SHELL32, "ILFree", JAVA_LONG);
        static final MethodHandle CREATE_POPUP_MENU = bind(USER32, "CreatePopupMenu", JAVA_LONG);
        static final MethodHandle DESTROY_MENU = bind(USER32, "DestroyMenu", JAVA_INT, JAVA_LONG);
        static final MethodHandle APPEND_MENU = bind(USER32, "AppendMenuW", JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, ADDRESS);
        static final MethodHandle GET_MENU_ITEM_COUNT = bind(USER32, "GetMenuItemCount", JAVA_INT, JAVA_LONG);
        static final MethodHandle TRACK_POPUP_MENU = bind(USER32, "TrackPopupMenuEx", JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG);
        static final MethodHandle SET_FOREGROUND_WINDOW = bind(USER32, "SetForegroundWindow", JAVA_INT, JAVA_LONG);
        static final MethodHandle POST_MESSAGE = bind(USER32, "PostMessageW", JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);
        static final MethodHandle GET_CURSOR_POS = bind(USER32, "GetCursorPos", JAVA_INT, ADDRESS);
        static final MethodHandle FIND_WINDOW_EX = bind(USER32, "FindWindowExW", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS);
        static final MethodHandle GET_WINDOW_THREAD_PROCESS_ID = bind(USER32, "GetWindowThreadProcessId", JAVA_INT, JAVA_LONG, ADDRESS);
        static final MethodHandle GET_FOREGROUND_WINDOW = bind(USER32, "GetForegroundWindow", JAVA_LONG);
        static final MethodHandle SET_WINDOW_LONG_PTR = bind(USER32, "SetWindowLongPtrW", JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG);
        static final MethodHandle CALL_WINDOW_PROC = bind(USER32, "CallWindowProcW", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);
        static final MethodHandle CO_GET_APARTMENT_TYPE = bind(OLE32, "CoGetApartmentType", JAVA_INT, ADDRESS, ADDRESS);

        private Win() {
        }

        private static MethodHandle bind(SymbolLookup lib, String name, java.lang.foreign.MemoryLayout result, java.lang.foreign.MemoryLayout... args) {
            MemorySegment symbol = lib.find(name).orElseThrow(() -> new IllegalStateException("No existe " + name));
            return LINKER.downcallHandle(symbol, FunctionDescriptor.of(result, args));
        }

        private static MethodHandle bindVoid(SymbolLookup lib, String name, java.lang.foreign.MemoryLayout... args) {
            MemorySegment symbol = lib.find(name).orElseThrow(() -> new IllegalStateException("No existe " + name));
            return LINKER.downcallHandle(symbol, FunctionDescriptor.ofVoid(args));
        }
    }
}
