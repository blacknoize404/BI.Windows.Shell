package bi.windows.shell;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

class ShellTest {

    private static void windowsOnly() {
        Assumptions.assumeTrue(Native.WINDOWS, "solo en Windows");
    }

    /** Ejecuta en un hilo nuevo, con o sin apartamento COM monohilo, y devuelve lo que calcule. */
    private static <T> T onThread(boolean sta, java.util.function.Supplier<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                if (sta) initSta();
                result.set(work.get());
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.start();
        thread.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    private static void initSta() throws Throwable {
        SymbolLookup ole32 = SymbolLookup.libraryLookup("ole32.dll", Arena.global());
        var init = Linker.nativeLinker().downcallHandle(ole32.find("CoInitializeEx").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        int hr = (int) init.invokeExact(MemorySegment.NULL, 2);   // COINIT_APARTMENTTHREADED
        assert hr >= 0 : "CoInitializeEx " + hr;
    }

    @Test
    void outsideWindowsEverythingIsDeclaredUnsupported() {
        Assumptions.assumeFalse(Native.WINDOWS, "solo fuera de Windows");
        assert ShellContextMenu.show("/tmp", 1, 0, 0, List.of()) == ShellMenuResult.UNSUPPORTED;
        assert !WindowsDarkMode.apply(0).applied();
        assert WindowHandles.findOwnWindow("x") == 0;
    }

    @Test
    void darkModeIsAppliedAndReportsItsOrdinalAndTelemetry() {
        windowsOnly();
        List<String> lines = new ArrayList<>();
        WindowsDarkMode.setTelemetry(lines::add);
        try {
            DarkModeResult dark = WindowsDarkMode.set(0, true, false);
            assert dark.error() == null : dark;
            assert dark.applied() && dark.ordinalUsed() == 135 : dark;
            DarkModeResult light = WindowsDarkMode.set(0, false, false);
            assert light.applied() && light.previousMode() == 2 : "el modo anterior era oscuro (2): " + light;
            assert lines.size() == 2 && lines.get(0).startsWith("{\"app\":\"BI.Windows.Shell\",\"op\":\"DarkMode.Apply\"") : lines;
            assert lines.get(0).contains("\"dark\":true") && lines.get(1).contains("\"dark\":false") : lines;
        } finally {
            WindowsDarkMode.setTelemetry(null);
        }
    }

    @Test
    void aWindowThatDoesNotExistIsNotFound() {
        windowsOnly();
        assert WindowHandles.findOwnWindow("BI.Windows.Shell: ventana que no existe") == 0;
    }

    @Test
    void showRefusesWithoutOwnerAndOffTheStaThread() throws Exception {
        windowsOnly();
        assert ShellContextMenu.show("C:\\Windows", 0, 0, 0, List.of()) == ShellMenuResult.INTEROP_FAILED;
        ShellMenuResult mta = onThread(false, () -> ShellContextMenu.show("C:\\Windows", 1, 0, 0, List.of()));
        assert mta == ShellMenuResult.NOT_STA : mta;
    }

    @Test
    void shellMenuForAnExistingFileHasVerbsAndAMissingOneIsDeclared() throws Exception {
        windowsOnly();
        String notepad = System.getenv("windir") + "\\System32\\notepad.exe";
        int verbs = onThread(true, () -> ShellContextMenu.probe(notepad, 0));
        assert verbs > 0 : "verbos de la shell: " + verbs;
        int missing = onThread(true, () -> ShellContextMenu.probe("C:\\no\\existe\\este\\archivo.txt", 0));
        assert missing == -ShellMenuResult.NO_FILE.ordinal() - 1 : missing;
    }

    @Test
    void menuMessagesAreRecognisedButNothingIsHandledWithoutAMenuOnScreen() {
        for (int msg : new int[]{0x117, 0x2B, 0x2C, 0x121, 0x120}) assert ShellContextMenu.isMenuMessage(msg) : msg;
        assert !ShellContextMenu.isMenuMessage(0x0F);
        assert !ShellContextMenu.tryHandleMenuMessage(0x117, 0, 0);
    }
}
