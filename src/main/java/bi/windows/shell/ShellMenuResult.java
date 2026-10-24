package bi.windows.shell;

/** Cómo terminó la presentación del menú: todo declarado, nada silencioso. */
public enum ShellMenuResult {
    /** El menú se mostró (se haya elegido algo o no). */
    SHOWN,
    /** La ruta no existe o la shell no la entiende. */
    NO_FILE,
    /** El hilo no es de apartamento COM monohilo: el menú de la shell lo exige (el hilo de JavaFX lo es). */
    NOT_STA,
    /** Algún paso del interop falló; el motivo va a la telemetría. */
    INTEROP_FAILED,
    /** No es Windows. */
    UNSUPPORTED
}
