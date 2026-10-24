package bi.windows.shell;

/**
 * Un elemento propio que se añade al final del menú de la shell, tras una línea separadora.
 *
 * @param label lo que se lee en el menú
 * @param run   lo que se hace al elegirlo (se ejecuta en el hilo que mostró el menú)
 */
public record ShellMenuExtra(String label, Runnable run) {
}
