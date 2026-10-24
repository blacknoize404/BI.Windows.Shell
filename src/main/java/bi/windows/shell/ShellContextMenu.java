package bi.windows.shell;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * El menú contextual nativo de Windows ({@code IContextMenu} de la shell): el mismo que abre el Explorador,
 * con los verbos del sistema y las extensiones de terceros (7-Zip y compañía), y con elementos propios
 * añadidos al final tras una línea separadora.
 * <p>Es de mejor esfuerzo y lo declara: ningún fallo del interop llega al anfitrión como excepción, sino
 * como un {@link ShellMenuResult}. Hay que llamarlo desde un hilo de apartamento COM monohilo (el hilo de
 * JavaFX y el de eventos de AWT lo son).
 * <p>Detalles que se aprendieron probándolo:
 * <ul>
 *   <li>El pidl hijo que devuelve {@code SHBindToParent} vive dentro del pidl padre: no se libera aparte.</li>
 *   <li>Los elementos propios usan identificadores lejos del rango de la shell (primero + 0x7000), porque con
 *       muchas extensiones el rango continuo puede chocar.</li>
 *   <li>Para que las extensiones pinten sus elementos hay que reenviar los mensajes de dibujo del menú a
 *       {@code IContextMenu2}; la biblioteca subclasifica la ventana mientras el menú está abierto, o se
 *       puede hacer a mano con {@link #tryHandleMenuMessage}.</li>
 * </ul>
 */
public final class ShellContextMenu {

    private static final int CMF_NORMAL = 0x0;
    private static final int CMF_EXPLORE = 0x4;
    private static final int TPM_RIGHTBUTTON = 0x2;
    private static final int TPM_RETURNCMD = 0x100;
    private static final int MF_STRING = 0x0;
    private static final int MF_SEPARATOR = 0x800;
    private static final int GWLP_WNDPROC = -4;

    private static final int WM_INITMENUPOPUP = 0x117;
    private static final int WM_DRAWITEM = 0x2B;
    private static final int WM_MEASUREITEM = 0x2C;
    private static final int WM_ENTERIDLE = 0x121;
    private static final int WM_MENUCHAR = 0x120;

    private static final String IID_ISHELLFOLDER = "000214E6-0000-0000-C000-000000000046";
    private static final String IID_ICONTEXTMENU = "000214E4-0000-0000-C000-000000000046";
    private static final String IID_ICONTEXTMENU2 = "000214F4-0000-0000-C000-000000000046";

    private static final int FIRST_ID = 1;
    private static final int LAST_ID = FIRST_ID + 0x6FFF;
    private static final int EXTRA_BASE = FIRST_ID + 0x7000;
    private static final int MAX_EXTRAS = 0x7FF;

    /** IContextMenu2 del menú que está en pantalla, 0 si ninguno (un menú a la vez). */
    private static volatile long live;
    /** El procedimiento de ventana que había antes de subclasificar. */
    private static long previousProc;

    private ShellContextMenu() {
    }

    /** Si el anfitrión quiere evidencia (una línea JSON por corrida), la engancha aquí; {@code null} no escribe nada. */
    public static void setTelemetry(Consumer<String> sink) {
        Native.telemetry = sink;
    }

    /** Los mensajes de dibujo del menú de la shell que un anfitrión con su propio gancho debe reenviar a {@link #tryHandleMenuMessage}. */
    public static boolean isMenuMessage(int msg) {
        return msg == WM_INITMENUPOPUP || msg == WM_DRAWITEM || msg == WM_MEASUREITEM
                || msg == WM_ENTERIDLE || msg == WM_MENUCHAR;
    }

    /**
     * Reenvía un mensaje de dibujo a {@code IContextMenu2}: con esto las extensiones se pintan y no salen en blanco.
     *
     * @return si la shell lo atendió; si no, el anfitrión sigue con su tratamiento normal
     */
    public static boolean tryHandleMenuMessage(int msg, long wParam, long lParam) {
        long menu = live;
        if (menu == 0) return false;
        try {
            int hr = (int) Native.method(menu, 6, FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG))
                    .invokeExact(menu, msg, wParam, lParam);
            return hr >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Abre el menú de la shell para la ruta, en las coordenadas de pantalla dadas (píxeles del dispositivo). */
    public static ShellMenuResult show(String path, long hwndOwner, int screenX, int screenY, List<ShellMenuExtra> extras) {
        return show(path, hwndOwner, screenX, screenY, extras, true);
    }

    /**
     * Igual que {@link #show(String, long, int, int, List)}, eligiendo si la ventana se subclasifica para
     * reenviar los mensajes de dibujo (con {@code false} lo hace el anfitrión con {@link #tryHandleMenuMessage}).
     */
    public static ShellMenuResult show(String path, long hwndOwner, int screenX, int screenY,
                                       List<ShellMenuExtra> extras, boolean subclass) {
        if (!Native.WINDOWS) return ShellMenuResult.UNSUPPORTED;
        try {
            return showCore(path, hwndOwner, screenX, screenY, extras == null ? List.of() : extras, subclass);
        } catch (Throwable t) {
            Native.emit("Show", "err", t.getClass().getSimpleName() + ": " + t.getMessage());
            return ShellMenuResult.INTEROP_FAILED;
        }
    }

    /** Abre el menú donde está el cursor: el cursor se lee dentro de la biblioteca, así que sirve con monitores de escala distinta de 100 %. */
    public static ShellMenuResult showAtCursor(String path, long hwndOwner, List<ShellMenuExtra> extras) {
        if (!Native.WINDOWS) return ShellMenuResult.UNSUPPORTED;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment point = arena.allocate(8, 4);
            int ok = (int) Native.Win.GET_CURSOR_POS.invokeExact(point);
            if (ok == 0) {
                Native.emit("ShowAtCursor", "err", "GetCursorPos falló");
                return ShellMenuResult.INTEROP_FAILED;
            }
            return show(path, hwndOwner, point.get(JAVA_INT, 0), point.get(JAVA_INT, 4), extras, true);
        } catch (Throwable t) {
            Native.emit("ShowAtCursor", "err", t.getClass().getSimpleName() + ": " + t.getMessage());
            return ShellMenuResult.INTEROP_FAILED;
        }
    }

    // ---------------------------------------------------------------- Interno

    /** Los objetos COM y el menú abiertos para una ruta; se cierra todo junto. */
    private static final class Opened implements AutoCloseable {
        ShellMenuResult failure;
        long pidl, folder, menu, menu2, hMenu;
        MemorySegment apidl;
        int verbs;
        final Arena arena = Arena.ofConfined();

        @Override
        public void close() {
            if (hMenu != 0) try {
                int ignored = (int) Native.Win.DESTROY_MENU.invokeExact(hMenu);
            } catch (Throwable ignored) {
                // mejor esfuerzo
            }
            if (menu2 != 0) Native.release(menu2);   // QueryInterface añadió su propia referencia
            if (menu != 0) Native.release(menu);
            if (folder != 0) Native.release(folder);
            if (pidl != 0) try {
                Native.Win.IL_FREE.invokeExact(pidl);   // el pidl hijo está dentro de este: no se libera aparte
            } catch (Throwable ignored) {
                // mejor esfuerzo
            }
            arena.close();
        }
    }

    private static boolean isStaThread() throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment type = arena.allocate(JAVA_INT);
            MemorySegment qualifier = arena.allocate(JAVA_INT);
            int hr = (int) Native.Win.CO_GET_APARTMENT_TYPE.invokeExact(type, qualifier);
            // APTTYPE_STA 0, APTTYPE_MTA 1, APTTYPE_NA 2, APTTYPE_MAINSTA 3
            int kind = type.get(JAVA_INT, 0);
            return hr >= 0 && (kind == 0 || kind == 3);
        }
    }

    /** Resuelve la ruta, pide el menú a la shell y lo rellena con sus verbos; deja en {@code failure} lo que salga mal. */
    private static Opened open(String path, long hwndOwner, List<ShellMenuExtra> extras) throws Throwable {
        Opened o = new Opened();
        Arena arena = o.arena;
        MemorySegment out = arena.allocate(8, 8);
        MemorySegment childOut = arena.allocate(8, 8);
        MemorySegment sfgao = arena.allocate(JAVA_INT);

        int hr = (int) Native.Win.SH_PARSE_DISPLAY_NAME.invokeExact(Native.wide(arena, path), 0L, out, 0, sfgao);
        o.pidl = out.get(JAVA_LONG, 0);
        if (hr != 0 || o.pidl == 0) {
            o.pidl = 0;
            o.failure = ShellMenuResult.NO_FILE;
            return o;
        }
        hr = (int) Native.Win.SH_BIND_TO_PARENT.invokeExact(o.pidl, Native.guid(arena, IID_ISHELLFOLDER), out, childOut);
        o.folder = out.get(JAVA_LONG, 0);
        if (hr != 0 || o.folder == 0) {
            o.folder = 0;
            o.failure = ShellMenuResult.INTEROP_FAILED;
            return o;
        }
        long child = childOut.get(JAVA_LONG, 0);
        o.apidl = arena.allocate(8, 8);
        o.apidl.set(JAVA_LONG, 0, child);

        // IShellFolder::GetUIObjectOf es el método 10 de la tabla virtual
        hr = (int) Native.method(o.folder, 10, FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
                .invokeExact(o.folder, hwndOwner, 1, o.apidl, Native.guid(arena, IID_ICONTEXTMENU), MemorySegment.NULL, out);
        o.menu = out.get(JAVA_LONG, 0);
        if (hr != 0 || o.menu == 0) {
            o.menu = 0;
            o.failure = ShellMenuResult.INTEROP_FAILED;
            return o;
        }
        // QueryInterface de IContextMenu2: si no lo hay, el menú se abre igual pero sin elementos pintados por extensiones
        hr = (int) Native.method(o.menu, 0, FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS))
                .invokeExact(o.menu, Native.guid(arena, IID_ICONTEXTMENU2), out);
        o.menu2 = hr == 0 ? out.get(JAVA_LONG, 0) : 0;

        o.hMenu = (long) Native.Win.CREATE_POPUP_MENU.invokeExact();
        // IContextMenu::QueryContextMenu es el método 3
        int count = (int) Native.method(o.menu, 3, FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT))
                .invokeExact(o.menu, o.hMenu, 0, FIRST_ID, LAST_ID, CMF_NORMAL | CMF_EXPLORE);
        if (count < 0) {
            o.failure = ShellMenuResult.INTEROP_FAILED;
            return o;
        }
        o.verbs = count & 0xFFFF;

        if (!extras.isEmpty()) {
            int ignored = (int) Native.Win.APPEND_MENU.invokeExact(o.hMenu, MF_SEPARATOR, 0L, MemorySegment.NULL);
            for (int i = 0; i < extras.size(); i++) {
                ignored = (int) Native.Win.APPEND_MENU.invokeExact(o.hMenu, MF_STRING, (long) (EXTRA_BASE + i), Native.wide(arena, extras.get(i).label()));
            }
        }
        return o;
    }

    /**
     * Los verbos que la shell pone para la ruta, sin mostrar nada: sirve para comprobar que el interop funciona.
     *
     * @return el número de verbos, o un valor negativo con el código de {@link ShellMenuResult}
     */
    static int probe(String path, long hwndOwner) {
        if (!Native.WINDOWS) return -ShellMenuResult.UNSUPPORTED.ordinal() - 1;
        try (Opened o = open(path, hwndOwner, List.of())) {
            return o.failure != null ? -o.failure.ordinal() - 1 : o.verbs;
        } catch (Throwable t) {
            return -ShellMenuResult.INTEROP_FAILED.ordinal() - 1;
        }
    }

    private static ShellMenuResult showCore(String path, long hwndOwner, int screenX, int screenY,
                                            List<ShellMenuExtra> extras, boolean subclass) throws Throwable {
        if (hwndOwner == 0) return ShellMenuResult.INTEROP_FAILED;
        if (!isStaThread()) return ShellMenuResult.NOT_STA;
        if (extras.size() > MAX_EXTRAS) return ShellMenuResult.INTEROP_FAILED;   // el rango de extras no puede crecer sin límite

        try (Opened o = open(path, hwndOwner, extras)) {
            if (o.failure != null) return o.failure;
            live = o.menu2;
            boolean hooked = subclass && o.menu2 != 0 && subclassWindow(hwndOwner);
            try {
                WindowsDarkMode.flushMenus();   // la shell invalida la caché del tema a veces
                int ignored = (int) Native.Win.SET_FOREGROUND_WINDOW.invokeExact(hwndOwner);
                int chosen = (int) Native.Win.TRACK_POPUP_MENU.invokeExact(o.hMenu, TPM_RETURNCMD | TPM_RIGHTBUTTON, screenX, screenY, hwndOwner, 0L);
                ignored = (int) Native.Win.POST_MESSAGE.invokeExact(hwndOwner, 0, 0L, 0L);   // WM_NULL: desbloquea el menú de la shell
                if (chosen == 0) return ShellMenuResult.SHOWN;   // se cerró sin elegir: nada que invocar
                if (chosen >= EXTRA_BASE && chosen < EXTRA_BASE + extras.size()) {
                    extras.get(chosen - EXTRA_BASE).run().run();
                    return ShellMenuResult.SHOWN;
                }
                invoke(o, hwndOwner, chosen - FIRST_ID);
                return ShellMenuResult.SHOWN;
            } finally {
                if (hooked) restoreWindow(hwndOwner);
                live = 0;
            }
        }
    }

    /** IContextMenu::InvokeCommand (método 4) con CMINVOKECOMMANDINFO; el verbo es el desplazamiento del elemento. */
    private static void invoke(Opened o, long hwnd, int verbOffset) throws Throwable {
        MemorySegment info = o.arena.allocate(56, 8);   // CMINVOKECOMMANDINFO en 64 bits
        info.set(JAVA_INT, 0, 56);          // cbSize
        info.set(JAVA_LONG, 8, hwnd);       // hwnd
        info.set(JAVA_LONG, 16, verbOffset);   // lpVerb: MAKEINTRESOURCE del desplazamiento
        info.set(JAVA_INT, 40, 1);          // nShow = SW_SHOWNORMAL
        int hr = (int) Native.method(o.menu, 4, FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS))
                .invokeExact(o.menu, info);
        if (hr < 0) Native.emit("Invoke", "err", "InvokeCommand devolvió " + hr);
    }

    // ---------------------------------------------------------------- Subclasificación de la ventana

    private static final MethodHandle WINDOW_PROC = windowProc();
    private static MemorySegment stub;

    private static MethodHandle windowProc() {
        try {
            return MethodHandles.lookup().findStatic(ShellContextMenu.class, "onMessage",
                    MethodType.methodType(long.class, long.class, int.class, long.class, long.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static boolean subclassWindow(long hwnd) {
        try {
            if (stub == null) {
                stub = Native.Win.LINKER.upcallStub(WINDOW_PROC,
                        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG), Arena.global());
            }
            long old = (long) Native.Win.SET_WINDOW_LONG_PTR.invokeExact(hwnd, GWLP_WNDPROC, stub.address());
            if (old == 0) return false;
            previousProc = old;
            return true;
        } catch (Throwable t) {
            Native.emit("Subclass", "err", t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static void restoreWindow(long hwnd) {
        try {
            long ignored = (long) Native.Win.SET_WINDOW_LONG_PTR.invokeExact(hwnd, GWLP_WNDPROC, previousProc);
        } catch (Throwable ignored) {
            // mejor esfuerzo
        }
    }

    /** El procedimiento de ventana mientras el menú está abierto: los mensajes de dibujo van a la shell, el resto a la ventana. */
    private static long onMessage(long hwnd, int msg, long wParam, long lParam) {
        try {
            if (isMenuMessage(msg) && tryHandleMenuMessage(msg, wParam, lParam)) {
                return msg == WM_MEASUREITEM || msg == WM_DRAWITEM ? 1 : 0;   // TRUE: lo dibujó la extensión
            }
            return (long) Native.Win.CALL_WINDOW_PROC.invokeExact(previousProc, hwnd, msg, wParam, lParam);
        } catch (Throwable t) {
            return 0;   // una excepción no puede cruzar a código nativo
        }
    }
}
